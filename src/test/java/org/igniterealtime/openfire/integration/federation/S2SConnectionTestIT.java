package org.igniterealtime.openfire.integration.federation;

import org.igniterealtime.openfire.integration.federation.AdminConsole.Check;
import org.igniterealtime.openfire.integration.federation.AdminConsole.S2SConnectionTest;
import org.igniterealtime.openfire.integration.federation.AdminConsole.Status;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.igniterealtime.openfire.integration.federation.FederatedTestEnvironment.*;

/**
 * Integration tests of the admin console's server-to-server connection test, which shows a checklist of the steps of a
 * connection to another domain, and which of them failed, and why (OF-3439).
 *
 * Tagged {@code federation}, so the checklist of a working connection is tested with each way the servers authenticate
 * to each other. With SASL EXTERNAL, every check passes. With dialback, server-to-server TLS is disabled: the TLS and
 * authentication checks warn that the connection is not encrypted and not authenticated with a certificate, and the
 * certificate check is not reached.
 *
 * Runs only against Openfire {@value #FIRST_VERSION} or later, which has the checklist. Against an earlier version,
 * such as on Openfire's maintenance branches, the tests are skipped.
 */
@Tag("federation")
public class S2SConnectionTestIT extends BaseFederationIT {

    // The first Openfire version with the checklist.
    static final String FIRST_VERSION = "5.2.0";

    // The checks, in the order the checklist shows them, that these tests refer to.
    private static final int CHECKS = 11;
    private static final int DNS = 2;
    private static final int TCP = 3;
    private static final int TLS = 5;
    private static final int CERTIFICATE = 6;
    private static final int AUTHENTICATION = 7;
    // The last check reviews what was logged for the attempt, whether or not it stopped at an earlier check.
    private static final int LOG = 11;

    // The version of Openfire under test, as the first server reports it.
    private static String version;

    @BeforeAll
    static void findServerVersion() throws Exception {
        version = OpenfireVersion.reportedBy(XMPP1_PORT, XMPP1_DOMAIN, USER_1);
    }

    // Checked before each test, rather than once for the class, so that each skipped test is reported as skipped.
    @BeforeEach
    void requireChecklist() {
        Assumptions.assumeTrue(OpenfireVersion.isAtLeast(version, FIRST_VERSION),
                "Openfire " + version + " has no server-to-server connection test checklist (added in " + FIRST_VERSION + ")");
    }

    /**
     * The first server tests its connection to the second: every check passes, or, with dialback, warns as the
     * configuration requires.
     */
    @Test
    void workingConnectionPassesEveryCheck() throws Exception {
        assertWorkingConnection(AdminConsole.loginTo(XMPP1_DOMAIN).testConnection(XMPP2_DOMAIN));
    }

    /**
     * As {@link #workingConnectionPassesEveryCheck()}, from the second server to the first.
     */
    @Test
    void workingConnectionPassesEveryCheckFromSecondServer() throws Exception {
        assertWorkingConnection(AdminConsole.loginTo(XMPP2_DOMAIN).testConnection(XMPP1_DOMAIN));
    }

    /**
     * A domain that has no address in DNS fails at the DNS check, which says so, and the test stops there.
     */
    @Test
    void unresolvableDomainFailsAtDnsCheck() throws Exception {
        assertFailsAt(DNS, "No address was found", AdminConsole.loginTo(XMPP1_DOMAIN).testConnection(DOMAIN_WITHOUT_ADDRESS));
    }

    /**
     * A domain whose address refuses the connection fails at the TCP check, which says that the connection was refused,
     * and the test stops there.
     */
    @Test
    void refusedConnectionFailsAtTcpCheck() throws Exception {
        assertFailsAt(TCP, "was refused", AdminConsole.loginTo(XMPP1_DOMAIN).testConnection(HOST_WITHOUT_XMPP));
    }

    private static void assertWorkingConnection(S2SConnectionTest test) {
        // With dialback, server-to-server TLS is disabled on both servers.
        final Map<Integer, Status> unencrypted = Map.of(TLS, Status.WARNING, CERTIFICATE, Status.NOT_REACHED, AUTHENTICATION, Status.WARNING);
        Assertions.assertEquals(CHECKS, test.checks().size(), "Expected " + CHECKS + " checks in " + test);
        for (final Check check : test.checks()) {
            final Status expected = isDialback() ? unencrypted.getOrDefault(check.number(), Status.PASS) : Status.PASS;
            Assertions.assertEquals(expected, check.status(), "Unexpected status of check " + check.number() + " in " + test);
            Assertions.assertFalse(check.firstFailure(), "Check " + check.number() + " is marked as a failure in " + test);
        }
    }

    /**
     * Asserts that the checks before the failing one passed, that it failed for the reason given (a phrase of its
     * detail, as the admin console shows it in English), and that the test stopped there.
     */
    private static void assertFailsAt(int failing, String reason, S2SConnectionTest test) {
        Assertions.assertEquals(CHECKS, test.checks().size(), "Expected " + CHECKS + " checks in " + test);
        for (final Check check : test.checks()) {
            if (check.number() < failing) {
                Assertions.assertEquals(Status.PASS, check.status(), "Check " + check.number() + " did not pass in " + test);
            } else if (check.number() == failing) {
                Assertions.assertEquals(Status.FAIL, check.status(), "Check " + failing + " did not fail in " + test);
                Assertions.assertTrue(check.firstFailure(), "Check " + failing + " is not marked as the first failure in " + test);
                Assertions.assertTrue(check.detail().contains(reason), "Check " + failing + " does not say \"" + reason + "\" in " + test);
            } else if (check.number() != LOG) {
                Assertions.assertEquals(Status.NOT_REACHED, check.status(), "Check " + check.number() + " was reached in " + test);
            }
        }
    }
}
