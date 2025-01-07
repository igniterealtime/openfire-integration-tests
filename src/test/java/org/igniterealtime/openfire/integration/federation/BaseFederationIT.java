package org.igniterealtime.openfire.integration.federation;

import org.junit.jupiter.api.BeforeAll;

/**
 * Base class for federation integration tests. All federation-related tests should extend this class
 * to ensure proper test environment setup.
 */
public abstract class BaseFederationIT {

    @BeforeAll
    public static void setupEnvironment() throws Exception {
        FederatedTestEnvironment.start();
    }
}