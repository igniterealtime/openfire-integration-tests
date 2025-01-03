package org.igniterealtime.openfire.integration.federation;

import org.junit.jupiter.api.BeforeAll;

public abstract class BaseFederationIT {

    @BeforeAll
    public static void setupEnvironment() throws Exception {
        FederatedTestEnvironment.start();
    }
}