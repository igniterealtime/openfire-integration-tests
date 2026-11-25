package org.igniterealtime.openfire.integration.federation;

import org.jivesoftware.smack.AbstractXMPPConnection;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.tcp.XMPPTCPConnection;
import org.jivesoftware.smack.tcp.XMPPTCPConnectionConfiguration;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
 * connection between the two servers breaks, the stream is resumed on a new connection, and every message arrives once,
 * in order.
 *
 * The tests show that the stream was resumed, and not only that the messages were sent again: before the break, the
 * acknowledgements of some messages that did arrive are lost. Only on resumption does the sending server learn that
 * they arrived (from the 'h' of {@code <resumed/>}), so that it doesn't send them again. Without resumption, it sends
 * every unacknowledged message again on the new connection, and those messages arrive twice.
 *
 * Runs only when {@value FederatedTestEnvironment#STREAM_MANAGEMENT_PROPERTY} is {@code true}, which enables Stream
 * Management with resumption on both servers and routes their connections through a relay that the tests control, and
 * only against Openfire {@value #FIRST_VERSION} or later, which has Stream Management on server-to-server connections
 * (OF-2425). Against an earlier version, such as on Openfire's maintenance branches, the tests are skipped.
 */
@Tag("federation")
@Tag("stream-management")
public class StreamManagementFederationIT extends BaseFederationIT {
    private static final Logger logger = LoggerFactory.getLogger(StreamManagementFederationIT.class);

    // The first Openfire version with Stream Management on server-to-server connections.
    static final String FIRST_VERSION = "5.2.0";

    // Messages that arrive before the connection breaks, but whose acknowledgements are lost; that are lost in flight;
    // and that are sent while the connection is broken.
    private static final int UNACKNOWLEDGED = 4;
    private static final int IN_FLIGHT = 3;
    private static final int WHILE_BROKEN = 2;

    // Time for all messages to arrive after the break: the stream is resumed on a new connection.
    private static final Duration DELIVERY_TIMEOUT = Duration.ofSeconds(60);

    // The version of Openfire under test, as the first server reports it.
    private static String version;

    @BeforeAll
    static void findServerVersion() throws Exception {
        if (isStreamManagement()) {
            version = OpenfireVersion.reportedBy(XMPP1_PORT, XMPP1_DOMAIN, USER_1);
        }
    }

    // Checked before each test, rather than once for the class, so that each skipped test is reported as skipped.
    @BeforeEach
    void requireStreamManagement() {
        Assumptions.assumeTrue(isStreamManagement(), "Stream Management on server-to-server connections is not enabled for this run");
        Assumptions.assumeTrue(OpenfireVersion.isAtLeast(version, FIRST_VERSION),
                "Openfire " + version + " has no Stream Management on server-to-server connections (added in " + FIRST_VERSION + ")");
    }

    /**
     * Messages from a user of the first server to a user of the second server, over the connection that the first server
     * opened: some arrive but their acknowledgements are lost, then some are lost in flight, then the connection breaks,
     * and more are sent while it is broken.
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
            // A synchronous listener is called in the order in which the stanzas arrive.
            recipientConnection.addStanzaListener(
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
            logger.info("Dropping what {} sends back: the next {} message(s) arrive, but are not acknowledged.", recipientDomain, UNACKNOWLEDGED);
            dropRelayed(recipientDomain, RelayDirection.TO_SENDING_SERVER);
            for (int i = 0; i < UNACKNOWLEDGED; i++) {
                sent.add(send(senderConnection, to));
            }
            waitFor(received, sent, Duration.ofSeconds(30));
            Assertions.assertTrue(received.containsAll(sent), "The messages sent before the connection was broken did not arrive.");

            logger.info("Dropping what {} sends: the next {} message(s) are lost in flight.", senderDomain, IN_FLIGHT);
            dropRelayed(recipientDomain, RelayDirection.TO_RECEIVING_SERVER);
            for (int i = 0; i < IN_FLIGHT; i++) {
                sent.add(send(senderConnection, to));
            }
            // Time for those messages to leave the sending server, so that they are lost rather than sent after the break.
            Thread.sleep(1000);

            logger.info("Breaking the connection, and sending {} message(s) while it is broken.", WHILE_BROKEN);
            breakRelayedConnections();
            for (int i = 0; i < WHILE_BROKEN; i++) {
                sent.add(send(senderConnection, to));
            }

            waitFor(received, sent, DELIVERY_TIMEOUT);
            final List<String> arrived = new ArrayList<>(received);
            arrived.remove(first);
            Assertions.assertEquals(sent, arrived,
                    "Each message must arrive once, in the order in which it was sent, after the server-to-server connection broke. "
                    + "A message that arrives twice was sent again although it had arrived: the stream was not resumed.");
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
