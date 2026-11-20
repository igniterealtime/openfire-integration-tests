package org.igniterealtime.openfire.integration.federation;

import org.jivesoftware.smack.AbstractXMPPConnection;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.tcp.XMPPTCPConnection;
import org.jivesoftware.smack.tcp.XMPPTCPConnectionConfiguration;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.jxmpp.jid.impl.JidCreate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.igniterealtime.openfire.integration.federation.FederatedTestEnvironment.*;

/**
 * Integration tests of Stream Management (XEP-0198) on server-to-server connections, with resumption: when the
 * connection between the two servers breaks while stanzas are in flight, the stream is resumed on a new connection, and
 * every message arrives once, in order.
 *
 * Runs only when {@value FederatedTestEnvironment#STREAM_MANAGEMENT_PROPERTY} is {@code true}, which enables Stream
 * Management with resumption on both servers and routes their connections through a relay that the tests can break.
 * Requires an Openfire version with Stream Management on server-to-server connections (OF-2425).
 */
@Tag("federation")
@Tag("stream-management")
public class StreamManagementFederationIT extends BaseFederationIT {
    private static final Logger logger = LoggerFactory.getLogger(StreamManagementFederationIT.class);

    // Messages sent before the connection breaks (lost in flight), and while it is broken.
    private static final int IN_FLIGHT = 5;
    private static final int WHILE_BROKEN = 3;

    // Time for all messages to arrive after the break: the stream is resumed on a new connection.
    private static final Duration DELIVERY_TIMEOUT = Duration.ofSeconds(60);

    @BeforeAll
    static void requireStreamManagement() {
        Assumptions.assumeTrue(isStreamManagement(), "Stream Management on server-to-server connections is not enabled for this run");
    }

    /**
     * Messages from a user of the first server to a user of the second server: the connection that the first server
     * opened breaks while messages are in flight, and more are sent while it is broken.
     */
    @Test
    void messagesArriveOnceAfterBrokenConnection() throws Exception {
        assertDeliveredOnceAfterBreak(XMPP1_PORT, XMPP1_DOMAIN, USER_1, XMPP2_PORT, XMPP2_DOMAIN, USER_3);
    }

    /**
     * As {@link #messagesArriveOnceAfterBrokenConnection()}, from the second server to the first, so that each server
     * is tested as the server that resumes its outgoing stream and as the server on which the stream is resumed.
     */
    @Test
    void messagesArriveOnceAfterBrokenConnectionFromSecondServer() throws Exception {
        assertDeliveredOnceAfterBreak(XMPP2_PORT, XMPP2_DOMAIN, USER_4, XMPP1_PORT, XMPP1_DOMAIN, USER_2);
    }

    private void assertDeliveredOnceAfterBreak(int senderPort, String senderDomain, String sender,
                                               int recipientPort, String recipientDomain, String recipient) throws Exception {
        final AbstractXMPPConnection senderConnection = connect(senderPort, senderDomain, sender);
        final AbstractXMPPConnection recipientConnection = connect(recipientPort, recipientDomain, recipient);
        final List<String> received = Collections.synchronizedList(new ArrayList<>());
        try {
            recipientConnection.addAsyncStanzaListener(
                    stanza -> received.add(((Message) stanza).getBody()),
                    stanza -> stanza instanceof Message message && message.getType() == Message.Type.chat);

            // Messages go to the recipient's full JID: one to the bare JID can be stored offline if it arrives before the
            // recipient's initial presence has been processed.
            final String to = recipientConnection.getUser().toString();

            // The first message sets up the server-to-server connection, on which Stream Management is enabled.
            final String first = send(senderConnection, to);
            waitFor(received, List.of(first), Duration.ofSeconds(30));
            Assertions.assertTrue(received.contains(first), "The first message, which sets up the server-to-server connection, was not received.");

            final List<String> sent = new ArrayList<>();
            logger.info("Freezing the relay: the next {} message(s) stay in flight.", IN_FLIGHT);
            freezeRelay();
            for (int i = 0; i < IN_FLIGHT; i++) {
                sent.add(send(senderConnection, to));
            }
            Thread.sleep(1000);
            logger.info("Breaking the connection: what is in flight is lost.");
            breakRelay();
            for (int i = 0; i < WHILE_BROKEN; i++) {
                sent.add(send(senderConnection, to));
            }

            waitFor(received, sent, DELIVERY_TIMEOUT);
            final List<String> arrived = new ArrayList<>(received);
            arrived.remove(first);
            Assertions.assertEquals(sent, arrived,
                    "Each message must arrive once, in the order in which it was sent, after the server-to-server connection broke.");
        } finally {
            if (senderConnection.isConnected()) {
                senderConnection.disconnect();
            }
            if (recipientConnection.isConnected()) {
                recipientConnection.disconnect();
            }
        }
    }

    private static AbstractXMPPConnection connect(int port, String domain, String user) throws Exception {
        final XMPPTCPConnectionConfiguration config = XMPPTCPConnectionConfiguration.builder()
                .setHost("localhost")
                .setPort(port)
                .setXmppDomain(domain)
                .setUsernameAndPassword(user, PASSWORD)
                .setSecurityMode(XMPPTCPConnectionConfiguration.SecurityMode.disabled)
                .setConnectTimeout(5000)
                .build();
        final AbstractXMPPConnection connection = new XMPPTCPConnection(config);
        connection.connect();
        connection.login();
        return connection;
    }

    private static String send(AbstractXMPPConnection connection, String to) throws Exception {
        // The body identifies the message: a copy that is sent again carries the same body.
        final String id = "sm-" + UUID.randomUUID();
        final Message message = connection.getStanzaFactory()
                .buildMessageStanza()
                .to(JidCreate.from(to))
                .ofType(Message.Type.chat)
                .setBody(id)
                .build();
        connection.sendStanza(message);
        return id;
    }

    /**
     * Waits until every expected ID was received, or the timeout passes, and then a little longer, so that a message
     * that arrives twice is seen.
     */
    private static void waitFor(List<String> received, List<String> expected, Duration timeout) throws InterruptedException {
        final Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            synchronized (received) {
                if (received.containsAll(expected)) {
                    break;
                }
            }
            Thread.sleep(200);
        }
        Thread.sleep(2000);
    }
}
