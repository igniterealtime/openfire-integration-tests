package org.igniterealtime.openfire.integration.federation;

import org.jivesoftware.smack.AbstractXMPPConnection;
import org.jivesoftware.smack.SmackException;
import org.jivesoftware.smack.sasl.SASLErrorException;
import org.jivesoftware.smack.tcp.XMPPTCPConnection;
import org.jivesoftware.smack.tcp.XMPPTCPConnectionConfiguration;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.igniterealtime.openfire.integration.federation.FederatedTestEnvironment.*;

/**
 * Integration tests that verify basic connectivity and authentication functionality
 * in a federated XMPP environment. Tests both successful and failed authentication scenarios.
 */
public class ConnectAndAuthenticateIT extends BaseFederationIT {
    private static final Logger logger = LoggerFactory.getLogger(ConnectAndAuthenticateIT.class);

    /**
     * Tests basic federation functionality by:
     * 1. Connecting and authenticating a user to the first XMPP server
     * 2. Connecting and authenticating a different user to the second XMPP server
     * Both connections are made with valid credentials and should succeed.
     */
    @Test
    void basicFederationTest() throws Exception {
        logger.info("Starting basic federation test...");

        // Configure first user connection
        XMPPTCPConnectionConfiguration config1 = XMPPTCPConnectionConfiguration.builder()
                .setHost("localhost")
                .setPort(XMPP1_PORT)
                .setXmppDomain(XMPP1_DOMAIN)
                .setUsernameAndPassword(USER_1, PASSWORD)
                .setSecurityMode(XMPPTCPConnectionConfiguration.SecurityMode.disabled)
                .setConnectTimeout(5000)  // 5 second timeout
                .build();

        // Connect first user
        logger.info("Attempting to connect to XMPP server 1...");
        AbstractXMPPConnection connection1 = new XMPPTCPConnection(config1);

        try {
            connection1.connect();
            logger.info("Successfully connected to server 1");

            connection1.login();
            logger.info("Successfully logged in as user1");

            // Add a small delay before disconnecting
            Thread.sleep(1000);
        } catch (SmackException e) {
            logger.error("XMPP Connection to server 1 failed", e);
            throw e;
        } finally {
            if (connection1.isConnected()) {
                connection1.disconnect();
                logger.info("Successfully disconnected from server 1");
            }
        }

        // Configure second user connection
        XMPPTCPConnectionConfiguration config2 = XMPPTCPConnectionConfiguration.builder()
                .setHost("localhost")
                .setPort(XMPP2_PORT)
                .setXmppDomain(XMPP2_DOMAIN)
                .setUsernameAndPassword(USER_3, PASSWORD)
                .setSecurityMode(XMPPTCPConnectionConfiguration.SecurityMode.disabled)
                .setConnectTimeout(5000)  // 5 second timeout
                .build();

        // Connect second user
        logger.info("Attempting to connect to XMPP server 2...");
        AbstractXMPPConnection connection2 = new XMPPTCPConnection(config2);

        try {
            connection2.connect();
            logger.info("Successfully connected to server 2");

            connection2.login();
            logger.info("Successfully logged in as user3");

            // Add a small delay before disconnecting
            Thread.sleep(1000);
        } catch (SmackException e) {
            logger.error("XMPP Connection to server 2 failed", e);
            throw e;
        } finally {
            if (connection2.isConnected()) {
                connection2.disconnect();
                logger.info("Successfully disconnected from server 2");
            }
        }
    }

    /**
     * Tests authentication failure handling by attempting to connect with invalid credentials.
     * Expects a SASLErrorException to be thrown during the login attempt.
     */
    @Test
    void unauthorizedUserTest() throws Exception {
        logger.info("Starting unauthorized user test...");

        // Configure connection with invalid credentials
        XMPPTCPConnectionConfiguration config = XMPPTCPConnectionConfiguration.builder()
                .setHost("localhost")
                .setPort(XMPP1_PORT)
                .setXmppDomain("xmpp1.localhost.example")
                .setUsernameAndPassword("invalidUser", "invalidPassword")
                .setSecurityMode(XMPPTCPConnectionConfiguration.SecurityMode.disabled)
                .setConnectTimeout(5000)  // 5 second timeout
                .build();

        // Attempt to connect and login
        logger.info("Attempting to connect to XMPP server with invalid credentials...");
        AbstractXMPPConnection connection = new XMPPTCPConnection(config);

        Assertions.assertThrows(SASLErrorException.class, () -> {
            try {
                connection.connect();
                logger.info("Successfully connected to server, will attempt to login with invalid credentials");

                connection.login();
                logger.error("Unexpectedly logged in with invalid credentials");
            } finally {
                if (connection.isConnected()) {
                    connection.disconnect();
                    logger.info("Successfully disconnected from server");
                }
            }
        }, "Expected SASLErrorException with not-authorized error");
    }
}