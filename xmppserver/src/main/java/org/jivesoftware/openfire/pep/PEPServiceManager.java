/*
 * Copyright (C) 2004-2008 Jive Software. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jivesoftware.openfire.pep;

import org.dom4j.Element;
import org.jivesoftware.database.DbConnectionManager;
import org.jivesoftware.openfire.XMPPServer;
import org.jivesoftware.openfire.pubsub.*;
import org.jivesoftware.openfire.user.User;
import org.jivesoftware.openfire.user.UserManager;
import org.jivesoftware.openfire.user.UserNotFoundException;
import org.jivesoftware.openfire.vcard.xep0398.PEPAvatar;
import org.jivesoftware.util.CacheableOptional;
import org.jivesoftware.util.JiveGlobals;
import org.jivesoftware.util.cache.Cache;
import org.jivesoftware.util.cache.CacheFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmpp.packet.IQ;
import org.xmpp.packet.JID;
import org.xmpp.packet.Presence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.locks.Lock;

/**
 * Manages the creation, persistence and removal of {@link PEPService}
 * instances.
 *
 * @author Guus der Kinderen, guus.der.kinderen@gmail.com
 *
 */
public class PEPServiceManager {

    public static final Logger Log = LoggerFactory
            .getLogger(PEPServiceManager.class);

    /**
     * Cache of PEP services. Table, Key: bare JID; Value: PEPService
     */
    private final Cache<JID, CacheableOptional<PEPService>> pepServices = CacheFactory
        .createLocalCache("PEPServiceManager");

    private PubSubEngine pubSubEngine = null;

    /**
     * Retrieves a PEP service -- attempting first from memory, then from the
     * database.
     *
     * This method will automatically create a PEP service if one does not exist.
     *
     * @param uniqueIdentifier
     *            the unique identifier of the PEP service.
     * @return the requested PEP service.
     */
    public PEPService getPEPService( PubSubService.UniqueIdentifier uniqueIdentifier )
    {
        return getPEPService( uniqueIdentifier, true );
    }

    /**
     * Retrieves a PEP service -- attempting first from memory, then from the
     * database.
     *
     * This method can automatically create a PEP service if one does not exist.
     *
     * @param uniqueIdentifier
     *            the unique identifier of the PEP service.
     * @param autoCreate
     *            true if a PEP service that does not yet exist needs to be created.
     * @return the requested PEP service if found or null if not found.
     */
    public PEPService getPEPService( PubSubService.UniqueIdentifier uniqueIdentifier, boolean autoCreate )
    {
        // PEP Services use the JID as their service identifier.
        final JID needle;
        try {
            needle = new JID(uniqueIdentifier.getServiceId());
        } catch (IllegalArgumentException ex) {
            Log.warn( "Unable to get PEP service. Provided unique identifier does not contain a valid JID: " + uniqueIdentifier, ex );
            return null;
        }
        return getPEPService( needle, autoCreate );
    }

    /**
     * Retrieves a PEP service -- attempting first from memory, then from the
     * database.
     *
     * This method will automatically create a PEP service if one does not exist.
     *
     * @param jid
     *            the JID of the user that owns the PEP service.
     * @return the requested PEP service.
     */
    public PEPService getPEPService( JID jid ) {
        return getPEPService( jid, true );
    }

    /**
     * Retrieves a PEP service -- attempting first from memory, then from the
     * database.
     *
     * This method can automatically create a PEP service if one does not exist.
     *
     * @param jid
     *            the JID of the user that owns the PEP service.
     * @param autoCreate
     *            true if a PEP service that does not yet exist needs to be created.
     * @return the requested PEP service if found or null if not found.
     */
    public PEPService getPEPService( JID jid, boolean autoCreate ) {
        jid = jid.asBareJID();
        PEPService pepService;

        final Lock lock = pepServices.getLock(jid);
        lock.lock();
        try {
            if (pepServices.containsKey(jid)) {
                // lookup in cache
                if ( pepServices.get(jid).isAbsent() && autoCreate ) {
                    // needs auto-create despite negative cache.
                    pepService = null;
                } else {
                    return pepServices.get(jid).get();
                }
            } else {
                // lookup in database.
                pepService = PubSubPersistenceProviderManager.getInstance().getProvider().loadPEPServiceFromDB(jid);
                pepServices.put(jid, CacheableOptional.of(pepService));
                if ( pepService != null ) {
                    pepService.initialize();
                }
            }

            if ( pepService != null ) {
                Log.debug("PEP: Restored service for {} from the database.", jid);
                pubSubEngine.start(pepService);
            } else if (autoCreate) {
                Log.debug("PEP: Auto-created service for {}.", jid);
                pepService = this.create(jid);

                // Probe presences
                pubSubEngine.start(pepService);

                // Those who already have presence subscriptions to jidFrom
                // will now automatically be subscribed to this new
                // PEPService.
                XMPPServer.getInstance().getIQPEPHandler().addSubscriptionForRosterItems( pepService );
            }
        } finally {
            lock.unlock();
        }

        return pepService;
    }

    /**
     * Retrieves a PEP service -- attempting first from memory, then from the
     * database.
     *
     * This method will automatically create a PEP service if one does not exist.
     *
     * @param jid
     *            the bare JID of the user that owns the PEP service.
     * @return the requested PEP service.
     * @deprecated Replaced by {@link #getPEPService(JID)}
     */
    @Deprecated
    public PEPService getPEPService( String jid ) {
        return getPEPService( jid, true );
    }

    /**
     * Retrieves a PEP service -- attempting first from memory, then from the
     * database.
     *
     * This method can automatically create a PEP service if one does not exist.
     *
     * @param jid
     *            the bare JID of the user that owns the PEP service.
     * @param autoCreate
     *            true if a PEP service that does not yet exist needs to be created.
     * @return the requested PEP service if found or null if not found.
     * @deprecated Replaced by {@link #getPEPService(JID, boolean)}
     */
    @Deprecated
    public PEPService getPEPService( String jid, boolean autoCreate ) {
        return getPEPService( new JID(jid), autoCreate );
    }

    public PEPService create(JID owner) {
        // Return an error if the packet is from an anonymous, unregistered user
        // or remote user
        if (!XMPPServer.getInstance().isLocal(owner)
                || !UserManager.getInstance().isRegisteredUser(owner.getNode())) {
            throw new IllegalArgumentException(
                    "Request must be initiated by a local, registered user, but is not: "
                            + owner);
        }

        PEPService pepService = null;
        final JID bareJID = owner.asBareJID();
        final Lock lock = pepServices.getLock(bareJID);
        lock.lock();
        try {

            if (pepServices.get(bareJID) != null) {
                pepService = pepServices.get(bareJID).get();
            }

            if (pepService == null) {
                pepService = new PEPService(XMPPServer.getInstance(), bareJID);
                pepServices.put(bareJID, CacheableOptional.of(pepService));

                if (Log.isDebugEnabled()) {
                    Log.debug("PEPService created for : " + bareJID);
                }
            }
        } finally {
            lock.unlock();
        }

        return pepService;
    }

    /**
     * Deletes the {@link PEPService} belonging to the specified owner.
     *
     * @param owner
     *            The JID of the owner of the service to be deleted.
     */
    public void remove(JID owner) {

        final Lock lock = pepServices.getLock(owner.asBareJID());
        lock.lock();
        try {

            // To remove individual nodes, the PEPService must still be registered. Do not remove the service until
            // after all nodes are deleted.
            final CacheableOptional<PEPService> optional = pepServices.get(owner.asBareJID());
            if ( optional == null ) {
                return;
            }

            if ( optional.isPresent() )
            {
                // Delete the user's PEP nodes from memory and the database.
                CollectionNode rootNode = optional.get().getRootCollectionNode();
                for ( final Node node : optional.get().getNodes() )
                {
                    if ( rootNode.isChildNode(node) )
                    {
                        node.delete();
                    }
                }
                rootNode.delete();
            }

            // All nodes are now deleted. The service itself can now be deleted.
            pepServices.remove(owner.asBareJID()).get();
        } finally {
            lock.unlock();
        }
    }

    public void start(PEPService pepService) {
        pubSubEngine.start(pepService);
    }

    public void start() {
        pubSubEngine = new PubSubEngine(XMPPServer.getInstance()
                .getPacketRouter());
    }

    public void stop() {

        for (final CacheableOptional<PEPService> service : pepServices.values()) {
            if (service.isPresent()) {
                pubSubEngine.shutdown(service.get());
            }
        }

        pubSubEngine = null;
    }

    private void deleteVCardAvatar(JID from)
    {
        Element vcard = XMPPServer.getInstance().getVCardManager().getVCard(from.getNode());
        Element vcardphoto = vcard.element("PHOTO");

        if (vcardphoto!=null)
        {
            vcard.remove(vcardphoto);
            try
            {
                XMPPServer.getInstance().getVCardManager().setVCard(from.getNode(), vcard);
            }
            catch (Exception e)
            {
                Log.error("Could not update vcard: "+e.getMessage());
            }
        }
    }

    //Send VCARD Presence
    private void sendVCardPresence(JID from, String id)
    {
        User usr;
        try
        {
            usr = XMPPServer.getInstance().getUserManager().getUser(from.getNode());
            Presence presenceStanza = XMPPServer.getInstance().getPresenceManager().getPresence(usr);
            presenceStanza.setID(UUID.randomUUID().toString());
            if (presenceStanza.getFrom()==null)
            {
                presenceStanza.setFrom(from);
            }

            Element x = presenceStanza.addChildElement("x", PEPAvatar.NAMESPACE_VCARDUPDATE);
            Element photo = x.addElement("photo");

            if (id!=null)
            {
                photo.setText(id);
            }

            XMPPServer.getInstance().getPresenceRouter().route(presenceStanza);
        }
        catch (UserNotFoundException e)
        {
            Log.error("Could not send presence: "+e.getMessage());
        }
    }

    public void process(PEPService service, IQ iq)
    {
        pubSubEngine.process(service, iq);

        if (PEPAvatar.XMPP_AVATARCONVERSION_ENABLED.getValue()&&iq!=null)
        {
            Element childElement = iq.getChildElement();
            if (childElement!=null)
            {
                String childns = childElement.attributeValue("xmlns");

                //Check if IQ stanza is a pep avatar metadata node
                if (childns!=null)
                {
                    //metadata node with new item
                    if (childns.equalsIgnoreCase("http://jabber.org/protocol/pubsub")&&
                       (childElement.element("publish")!=null&&
                        childElement.element("publish").attributeValue("xmlns").
                        equalsIgnoreCase(PEPAvatar.NAMESPACE_METADATA)))
                        {
                            Element publish = childElement.element("publish");
                            Element item = publish.element("item");
                            if (item!=null)
                            {
                                Element metadata=item.element("metadata");
                                if (metadata!=null&&metadata.element("info")!=null)
                                {
                                    if (PEPAvatar.XMPP_DELETEOTHERAVATAR_ENABLED.getValue())
                                    {
                                        sendVCardPresence(iq.getFrom(),metadata.element("info").attributeValue("id"));
                                    }
                                    else
                                    {
                                        PEPAvatar pavatar = PEPAvatar.load(iq.getFrom().getNode());
                                        sendVCardPresence(iq.getFrom(),PEPAvatar.getSHA1FromShrinkedImage(pavatar.getMimetype(),pavatar.getImage()));
                                    }
                                }
                            }
                        }
                        else //metadatanode which should be removed
                            if (childns.equalsIgnoreCase("http://jabber.org/protocol/pubsub")&&
                               ((childElement.element("retract")!=null&&
                                childElement.element("retract").attributeValue("xmlns").
                                equalsIgnoreCase(PEPAvatar.NAMESPACE_METADATA))||
                                (childElement.element("delete")!=null&&
                                 childElement.element("delete").attributeValue("xmlns").
                                 equalsIgnoreCase(PEPAvatar.NAMESPACE_METADATA))))
                                {
                                    if (PEPAvatar.XMPP_DELETEOTHERAVATAR_ENABLED.getValue())
                                    {
                                        deleteVCardAvatar(iq.getFrom());
                                    }
                                    sendVCardPresence(iq.getFrom(),null);
                                }
                }
            }
        }
    }

    public boolean hasCachedService(JID owner) {
        return pepServices.get(owner.asBareJID()) != null;
    }

    // mimics Shutdown, without killing the timer.
    public void unload(PEPService service) {
        pubSubEngine.shutdown(service);
    }
}
