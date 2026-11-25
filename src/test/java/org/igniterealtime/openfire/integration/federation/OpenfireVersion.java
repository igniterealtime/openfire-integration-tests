package org.igniterealtime.openfire.integration.federation;

import org.jivesoftware.smack.AbstractXMPPConnection;
import org.jivesoftware.smack.tcp.XMPPTCPConnection;
import org.jivesoftware.smack.tcp.XMPPTCPConnectionConfiguration;
import org.jivesoftware.smackx.iqversion.VersionManager;
import org.jxmpp.jid.impl.JidCreate;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.igniterealtime.openfire.integration.federation.FederatedTestEnvironment.PASSWORD;

/**
 * The version of Openfire under test, for tests of a feature that older versions don't have: Openfire's maintenance
 * branches run these tests too, and such a test skips itself there.
 */
final class OpenfireVersion {

    private OpenfireVersion() {
    }

    /**
     * The version that a server reports (XEP-0092), such as "5.2.0 Alpha", asked by one of its users.
     */
    static String reportedBy(int port, String domain, String user) throws Exception {
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
        try {
            connection.login();
            return VersionManager.getInstanceFor(connection).getVersion(JidCreate.domainBareFrom(domain)).getVersion();
        } finally {
            connection.disconnect();
        }
    }

    /**
     * Whether a version string, such as "5.2.0 Alpha" or "5.1.1", is at least a version such as "5.2.0". Only the
     * numbers at its start are compared.
     */
    static boolean isAtLeast(String version, String minimum) {
        final int[] actual = numbers(version);
        final int[] required = numbers(minimum);
        for (int i = 0; i < required.length; i++) {
            final int part = i < actual.length ? actual[i] : 0;
            if (part != required[i]) {
                return part > required[i];
            }
        }
        return true;
    }

    private static int[] numbers(String version) {
        final Matcher matcher = Pattern.compile("^\\s*(\\d+(?:\\.\\d+)*)").matcher(version);
        if (!matcher.find()) {
            throw new IllegalArgumentException("Not a version: " + version);
        }
        final String[] parts = matcher.group(1).split("\\.");
        final int[] result = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            result[i] = Integer.parseInt(parts[i]);
        }
        return result;
    }
}
