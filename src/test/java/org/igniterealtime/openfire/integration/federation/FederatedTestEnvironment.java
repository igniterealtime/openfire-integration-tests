package org.igniterealtime.openfire.integration.federation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.temporal.ChronoUnit;

/**
 * Manages the test environment for federated XMPP integration tests.
 *
 * This environment consists of:
 * - Two Openfire XMPP servers in a federated configuration
 * - Two PostgreSQL databases (one for each server)
 * - Custom network configuration for server-to-server communication
 * - Pre-configured users and chat rooms on each server
 *
 * The environment is managed through Docker Compose, using configurations from
 * https://github.com/surevine/openfire-docker-compose which is included as a
 * Git submodule. This provides the container definitions, networking setup,
 * and base configuration for the federated environment.
 *
 * Local SQL overlays and additional configuration files are applied on top of
 * the base setup to customize it for integration testing.
 */
public class FederatedTestEnvironment {
    private static final Logger logger = LoggerFactory.getLogger(FederatedTestEnvironment.class);
    private static boolean initialized = false;

    // Time to wait for services to start up completely
    private static final Duration STARTUP_WAIT = Duration.of(10, ChronoUnit.SECONDS);

    // Port configuration for each XMPP server
    public static final int XMPP1_PORT = 5221;  // First server client port
    public static final int XMPP2_PORT = 5222;  // Second server client port

    // Domain names for the federated servers
    public static final String XMPP1_DOMAIN = "xmpp1.localhost.example";
    public static final String XMPP2_DOMAIN = "xmpp2.localhost.example";

    // Pre-configured test users
    // Server 1 users: user1, user2
    // Server 2 users: user3, user4
    public static final String USER_1 = "user1";
    public static final String USER_2 = "user2";
    public static final String USER_3 = "user3";
    public static final String USER_4 = "user4";
    public static final String PASSWORD = "password";

    /**
     * Initializes and starts the federated test environment.
     *
     * This method:
     * 1. Sets up SQL overlay directories with required configuration
     * 2. Launches the Docker containers using docker-compose
     * 3. Waits for services to initialize
     * 4. Registers a shutdown hook for cleanup
     *
     * The method is idempotent - subsequent calls will have no effect
     * if the environment is already initialized.
     *
     * @throws Exception if environment setup fails
     */
    public static synchronized void start() throws Exception {
        if (!initialized) {
            setupSqlOverlay();
            startFederatedEnvironment();
            logger.info("Waiting {} seconds for servers to initialize...", STARTUP_WAIT.toSeconds());
            Thread.sleep(STARTUP_WAIT.toMillis());
            initialized = true;
            // Register shutdown hook to ensure cleanup happens even if tests fail
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    stop();
                } catch (Exception e) {
                    logger.error("Error during environment cleanup", e);
                }
            }));
        }
    }

    /**
     * Stops the federated test environment and performs cleanup.
     *
     * This method:
     * 1. Removes SQL overlay files
     * 2. Stops and removes Docker containers
     *
     * The method is idempotent - subsequent calls will have no effect
     * if the environment is already stopped.
     *
     * @throws Exception if environment cleanup fails
     */
    public static synchronized void stop() throws Exception {
        if (initialized) {
            cleanupSqlOverlay();
            stopFederatedEnvironment();
            initialized = false;
        }
    }

    /**
     * Sets up SQL overlay directories with configuration files.
     *
     * Copies the STARTTLS disable script. This script is required as
     * there's currently a bug preventing self-signed certificates from
     * being used for server-to-server (S2S) connections.
     *
     * @throws IOException if file operations fail
     */
    private static void setupSqlOverlay() throws IOException {
        logger.info("Setting up SQL overlay directories...");
        for (int i = 1; i <= 2; i++) {
            Files.createDirectories(Path.of("openfire-docker-compose/federation/sql/" + i));
            Files.copy(
                    Path.of("src/test/resources/docker/federation/disable-starttls.sql"),
                    Path.of("openfire-docker-compose/federation/sql/" + i + "/zz-disable-starttls.sql"),
                    StandardCopyOption.REPLACE_EXISTING
            );
        }
    }

    /**
     * Launches the federated environment using Docker Compose.
     *
     * Executes the start script which:
     * 1. Sets up Docker networks
     * 2. Launches PostgreSQL databases
     * 3. Launches Openfire servers
     * 4. Configures federation between servers
     *
     * @throws IOException if script execution fails
     * @throws InterruptedException if script execution is interrupted
     * @throws RuntimeException if script exits with non-zero status
     */
    private static void startFederatedEnvironment() throws IOException, InterruptedException {
        logger.info("Starting federated environment...");

        File startScript = new File("openfire-docker-compose/federation/start.sh");
        startScript.setExecutable(true);

        ProcessBuilder processBuilder = new ProcessBuilder("/bin/bash", startScript.getPath())
                .directory(new File("."))
                .inheritIO();

        // Set Openfire version tag
        // TODO: Check external environment first before setting this
        processBuilder.environment().put("OPENFIRE_TAG", "latest");

        Process process = processBuilder.start();
        int exitCode = process.waitFor();

        if (exitCode != 0) {
            throw new RuntimeException("Failed to start federated environment, exit code: " + exitCode);
        }
    }

    /**
     * Removes SQL overlay files created during setup.
     *
     * @throws IOException if file deletion fails
     */
    private static void cleanupSqlOverlay() throws IOException {
        logger.info("Cleaning up SQL overlay files...");
        for (int i = 1; i <= 2; i++) {
            Files.deleteIfExists(Path.of("openfire-docker-compose/federation/sql/" + i + "/zz-disable-starttls.sql"));
        }
    }

    /**
     * Stops the federated environment by executing the stop script.
     * This terminates and removes all Docker containers.
     *
     * @throws IOException if script execution fails
     * @throws InterruptedException if script execution is interrupted
     */
    private static void stopFederatedEnvironment() throws IOException, InterruptedException {
        logger.info("Stopping federated environment...");

        File stopScript = new File("openfire-docker-compose/stop.sh");
        stopScript.setExecutable(true);

        ProcessBuilder processBuilder = new ProcessBuilder("/bin/bash", stopScript.getPath())
                .directory(new File("."))
                .inheritIO();

        Process process = processBuilder.start();
        process.waitFor();
    }
}