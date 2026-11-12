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
     * Tests federated messaging from a user of the first server to a user of the second server.
     */
    @Test
    void federatedMessageTest() throws Exception {
        assertFederatedMessageDelivered(XMPP1_PORT, XMPP1_DOMAIN, USER_1, XMPP2_PORT, XMPP2_DOMAIN, USER_3);
    }

    /**
     * Tests federated messaging from a user of the second server to a user of the first server, so that each server
     * is tested both as the server that opens the server-to-server connection and as the server that receives it.
     */
    @Test
    void federatedMessageFromSecondServerTest() throws Exception {
        assertFederatedMessageDelivered(XMPP2_PORT, XMPP2_DOMAIN, USER_3, XMPP1_PORT, XMPP1_DOMAIN, USER_1);
    }

    /**
     * Tests federated messaging by:
     * 1. Connecting users to different servers in the federation
     * 2. Setting up message listeners
     * 3. Sending a message from one server to another
     * 4. Verifying message receipt and content
     */
    private void assertFederatedMessageDelivered(int senderPort, String senderDomain, String sender,
                                                 int recipientPort, String recipientDomain, String recipient) throws Exception {
        logger.info("Starting federated message test from {}@{} to {}@{}...", sender, senderDomain, recipient, recipientDomain);

        // Configure connections for both users on different servers
        XMPPTCPConnectionConfiguration senderConfig = XMPPTCPConnectionConfiguration.builder()
                .setHost("localhost")
                .setPort(senderPort)
                .setXmppDomain(senderDomain)
                .setUsernameAndPassword(sender, PASSWORD)
                .setSecurityMode(XMPPTCPConnectionConfiguration.SecurityMode.disabled)
                .setConnectTimeout(5000)
                .build();

        XMPPTCPConnectionConfiguration recipientConfig = XMPPTCPConnectionConfiguration.builder()
                .setHost("localhost")
                .setPort(recipientPort)
                .setXmppDomain(recipientDomain)
                .setUsernameAndPassword(recipient, PASSWORD)
                .setSecurityMode(XMPPTCPConnectionConfiguration.SecurityMode.disabled)
                .setConnectTimeout(5000)
                .build();

        AbstractXMPPConnection senderConnection = new XMPPTCPConnection(senderConfig);
        AbstractXMPPConnection recipientConnection = new XMPPTCPConnection(recipientConfig);

        // CompletableFuture to track message receipt
        CompletableFuture<Message> receivedMessage = new CompletableFuture<>();

        try {
            // Connect and login both users
            senderConnection.connect();
            senderConnection.login();
            logger.info("{} connected and logged in to {}", sender, senderDomain);

            recipientConnection.connect();
            recipientConnection.login();
            logger.info("{} connected and logged in to {}", recipient, recipientDomain);

            // Add debug listeners to monitor all XMPP stanzas
            senderConnection.addAsyncStanzaListener(
                    stanza -> logger.info("Sender stanza: {}", stanza.toXML()),
                    stanza -> true
            );

            recipientConnection.addAsyncStanzaListener(
                    stanza -> logger.info("Recipient stanza: {}", stanza.toXML()),
                    stanza -> true
            );

            // Allow time for server-to-server federation to establish
            logger.info("Waiting for federation setup...");
            Thread.sleep(5000);
            logger.info("Finished waiting for federation setup");

            // Set up message listener for the recipient to capture incoming messages
            recipientConnection.addAsyncStanzaListener(
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

            // Create and send test message from the sender to the recipient
            String testMessage = "Hello from federated server " + senderDomain + "!";
            Message message = senderConnection.getStanzaFactory()
                    .buildMessageStanza()
                    .to(JidCreate.entityBareFrom(recipient + "@" + recipientDomain))
                    .ofType(Message.Type.chat)
                    .setBody(testMessage)
                    .build();

            logger.info("Sending message...");
            senderConnection.sendStanza(message);
            logger.info("Message sent from {} to {}: {}", sender, recipient, message.toXML());

            // Wait for message receipt with timeout
            try {
                logger.info("Waiting for message to be received...");
                Message received = receivedMessage.get(30, TimeUnit.SECONDS);

                // Verify message contents and metadata
                Assertions.assertNotNull(received, "Message should be received");
                Assertions.assertEquals(testMessage, received.getBody(), "Message body should match");
                Assertions.assertEquals(sender + "@" + senderDomain, received.getFrom().asBareJid().toString(),
                        "Message should be from " + sender + "@" + senderDomain);

                logger.info("Message successfully received and verified");
            } catch (TimeoutException e) {
                logger.error("Timeout while waiting for message. Sender connection status: {}, recipient connection status: {}",
                        senderConnection.isConnected(), recipientConnection.isConnected());
                throw e;
            }

        } finally {
            // Clean up connections
            if (senderConnection.isConnected()) {
                senderConnection.disconnect();
                logger.info("{} disconnected", sender);
            }
            if (recipientConnection.isConnected()) {
                recipientConnection.disconnect();
                logger.info("{} disconnected", recipient);
            }
        }
    }
}
