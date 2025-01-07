package org.igniterealtime.openfire.integration.federation;

import org.jivesoftware.smack.AbstractXMPPConnection;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.tcp.XMPPTCPConnection;
import org.jivesoftware.smack.tcp.XMPPTCPConnectionConfiguration;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.jxmpp.jid.impl.JidCreate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.igniterealtime.openfire.integration.federation.FederatedTestEnvironment.*;

/**
 * Integration tests for federated chat functionality. Tests the ability to send and receive
 * messages between users on different XMPP servers in the federation.
 */
public class FederatedChatIT extends BaseFederationIT {
    private static final Logger logger = LoggerFactory.getLogger(FederatedChatIT.class);

    /**
     * Tests federated messaging by:
     * 1. Connecting users to different servers in the federation
     * 2. Setting up message listeners
     * 3. Sending a message from one server to another
     * 4. Verifying message receipt and content
     */
    @Test
    void federatedMessageTest() throws Exception {
        logger.info("Starting federated message test...");

        // Configure connections for both users on different servers
        XMPPTCPConnectionConfiguration xmpp1Config = XMPPTCPConnectionConfiguration.builder()
                .setHost("localhost")
                .setPort(XMPP1_PORT)
                .setXmppDomain(XMPP1_DOMAIN)
                .setUsernameAndPassword(USER_1, PASSWORD)
                .setSecurityMode(XMPPTCPConnectionConfiguration.SecurityMode.disabled)
                .setConnectTimeout(5000)
                .build();

        XMPPTCPConnectionConfiguration xmpp2Config = XMPPTCPConnectionConfiguration.builder()
                .setHost("localhost")
                .setPort(XMPP2_PORT)
                .setXmppDomain(XMPP2_DOMAIN)
                .setUsernameAndPassword(USER_3, PASSWORD)
                .setSecurityMode(XMPPTCPConnectionConfiguration.SecurityMode.disabled)
                .setConnectTimeout(5000)
                .build();

        AbstractXMPPConnection xmpp1Connection = new XMPPTCPConnection(xmpp1Config);
        AbstractXMPPConnection xmpp2Connection = new XMPPTCPConnection(xmpp2Config);

        // CompletableFuture to track message receipt
        CompletableFuture<Message> receivedMessage = new CompletableFuture<>();

        try {
            // Connect and login both users
            xmpp1Connection.connect();
            xmpp1Connection.login();
            logger.info("{} connected and logged in to {}", USER_1, XMPP1_DOMAIN);
            logger.info("{} connection status: {}", USER_1, xmpp1Connection.isConnected());
            logger.info("{} authenticated status: {}", USER_1, xmpp1Connection.isAuthenticated());

            xmpp2Connection.connect();
            xmpp2Connection.login();
            logger.info("{} connected and logged in to {}", USER_3, XMPP2_DOMAIN);
            logger.info("{} connection status: {}", USER_3, xmpp2Connection.isConnected());
            logger.info("{} authenticated status: {}", USER_3, xmpp2Connection.isAuthenticated());

            // Add debug listeners to monitor all XMPP stanzas
            xmpp1Connection.addAsyncStanzaListener(
                    stanza -> logger.info("Server1 stanza: {}", stanza.toXML()),
                    stanza -> true
            );

            xmpp2Connection.addAsyncStanzaListener(
                    stanza -> logger.info("Server2 stanza: {}", stanza.toXML()),
                    stanza -> true
            );

            // Allow time for server-to-server federation to establish
            logger.info("Waiting for federation setup...");
            Thread.sleep(5000);
            logger.info("Finished waiting for federation setup");

            // Set up message listener for user3 to capture incoming messages
            xmpp2Connection.addAsyncStanzaListener(
                    stanza -> {
                        logger.info("Message listener received stanza: {}", stanza.toXML());
                        if (stanza instanceof Message message) {
                            logger.info("Found message stanza of type: {}", message.getType());
                            if (message.getType() == Message.Type.chat) {
                                receivedMessage.complete(message);
                            }
                        }
                    },
                    stanza -> stanza instanceof Message
            );

            // Create and send test message from user1 to user3
            String testMessage = "Hello from federated server!";
            Message message = xmpp1Connection.getStanzaFactory()
                    .buildMessageStanza()
                    .to(JidCreate.entityBareFrom(USER_3 + "@" + XMPP2_DOMAIN))
                    .ofType(Message.Type.chat)
                    .setBody(testMessage)
                    .build();

            logger.info("Sending message...");
            xmpp1Connection.sendStanza(message);
            logger.info("Message sent from {} to {}: {}", USER_1, USER_3, message.toXML());

            // Wait for message receipt with timeout
            try {
                logger.info("Waiting for message to be received...");
                Message received = receivedMessage.get(30, TimeUnit.SECONDS);

                // Verify message contents and metadata
                Assertions.assertNotNull(received, "Message should be received");
                Assertions.assertEquals(testMessage, received.getBody(), "Message body should match");
                Assertions.assertEquals(USER_1 + "@" + XMPP1_DOMAIN, received.getFrom().asBareJid().toString(),
                        "Message should be from user1");

                logger.info("Message successfully received and verified");
            } catch (TimeoutException e) {
                logger.error("Timeout while waiting for message. Connection1 status: {}, Connection2 status: {}",
                        xmpp1Connection.isConnected(), xmpp2Connection.isConnected());
                throw e;
            }

        } finally {
            // Clean up connections
            if (xmpp1Connection.isConnected()) {
                xmpp1Connection.disconnect();
                logger.info("User1 disconnected");
            }
            if (xmpp2Connection.isConnected()) {
                xmpp2Connection.disconnect();
                logger.info("User3 disconnected");
            }
        }
    }
}