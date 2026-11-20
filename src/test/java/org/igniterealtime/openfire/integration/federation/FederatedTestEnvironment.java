package org.igniterealtime.openfire.integration.federation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
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
 *
 * The system property {@value #S2S_AUTHENTICATION_PROPERTY} selects how the servers authenticate to each other:
 * {@code dialback} (the default), with server-to-server TLS disabled, or {@code sasl-external}, with TLS enabled and
 * dialback disabled, so that the servers authenticate with the certificates of the federation environment.
 *
 * When the system property {@value #STREAM_MANAGEMENT_PROPERTY} is {@code true}, both servers use Stream Management
 * (XEP-0198) with resumption on their server-to-server connections, and each reaches the other through a relay
 * container, which a test can freeze and break ({@link #freezeRelay()}, {@link #breakRelay()}) to break a connection
 * without closing its stream.
 */
public class FederatedTestEnvironment {
    private static final Logger logger = LoggerFactory.getLogger(FederatedTestEnvironment.class);
    private static boolean initialized = false;

    /**
     * The system property that selects how the servers authenticate to each other: {@code dialback} or
     * {@code sasl-external}.
     */
    public static final String S2S_AUTHENTICATION_PROPERTY = "openfire.it.s2s-authentication";

    // The SQL overlay that configures each way of authenticating, and the name under which it is copied.
    private static final String DIALBACK_OVERLAY = "disable-starttls.sql";
    private static final String SASL_EXTERNAL_OVERLAY = "sasl-external.sql";
    private static final String OVERLAY_TARGET = "zz-s2s-authentication.sql";

    /**
     * The system property that, when {@code true}, enables Stream Management with resumption on server-to-server
     * connections, and routes them through the relay container.
     */
    public static final String STREAM_MANAGEMENT_PROPERTY = "openfire.it.stream-management";

    // The SQL overlay of each server that enables Stream Management (stream-management-1.sql, -2.sql), and its target.
    private static final String STREAM_MANAGEMENT_OVERLAY = "stream-management-%d.sql";
    private static final String STREAM_MANAGEMENT_OVERLAY_TARGET = "zz-stream-management.sql";

    // The relay container, on the network of the federated environment. Port 5269 leads to xmpp2, port 5270 to xmpp1.
    private static final String RELAY_CONTAINER = "openfire-s2s-relay";
    private static final String RELAY_IMAGE = "python:3.12.7-alpine3.20";
    private static final String RELAY_SCRIPT = "src/test/resources/docker/federation/s2s-relay.py";
    private static final String RELAY_ADDRESS = "172.50.0.30";
    private static final String FEDERATED_NETWORK = "openfire_openfire-federated-net";

    // The name under which earlier versions of these tests copied the overlay that disables server-to-server TLS.
    private static final String LEGACY_OVERLAY_TARGET = "zz-disable-starttls.sql";

    /**
     * The system property that names the directory to which the Openfire logs are exported when the environment
     * stops. Defaults to the working directory.
     */
    public static final String LOG_DIRECTORY_PROPERTY = "openfire.it.log-directory";

    // Time to wait for services to start up completely
    private static final Duration STARTUP_WAIT = Duration.of(3, ChronoUnit.MINUTES);

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
            if (isStreamManagement()) {
                startRelay();
            }
            waitForFederatedEnvironment();
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
     * 3. Removes the data directory that the containers wrote to
     *
     * The method is idempotent - subsequent calls will have no effect
     * if the environment is already stopped.
     *
     * @throws Exception if environment cleanup fails
     */
    public static synchronized void stop() throws Exception {
        if (initialized) {
            exportLogs();
            cleanupSqlOverlay();
            removeRelay();
            stopFederatedEnvironment();
            removeDataDirectory();
            initialized = false;
        }
    }

    /**
     * Returns the name of the SQL overlay for the way of authenticating that {@value #S2S_AUTHENTICATION_PROPERTY}
     * selects.
     *
     * @return the file name of the overlay in {@code src/test/resources/docker/federation}
     * @throws IllegalArgumentException if the property has a value other than {@code dialback} or {@code sasl-external}
     */
    static String s2sAuthenticationOverlay() {
        final String mode = System.getProperty(S2S_AUTHENTICATION_PROPERTY, "dialback");
        return switch (mode) {
            case "dialback" -> DIALBACK_OVERLAY;
            case "sasl-external" -> SASL_EXTERNAL_OVERLAY;
            default -> throw new IllegalArgumentException("Unsupported value for " + S2S_AUTHENTICATION_PROPERTY + ": " + mode);
        };
    }

    /**
     * Whether {@value #STREAM_MANAGEMENT_PROPERTY} enables Stream Management on server-to-server connections.
     *
     * @return {@code true} if the property is {@code true}
     */
    static boolean isStreamManagement() {
        return Boolean.parseBoolean(System.getProperty(STREAM_MANAGEMENT_PROPERTY, "false"));
    }

    /**
     * Sets up SQL overlay directories with configuration files.
     *
     * Copies the script that configures how the servers authenticate to each other: with dialback, which disables
     * server-to-server TLS, or with SASL EXTERNAL, which disables dialback. Removes the overlay under the name that
     * earlier versions of these tests used, so that a file left behind by an interrupted run can't affect either run.
     *
     * @throws IOException if file operations fail
     */
    private static void setupSqlOverlay() throws IOException {
        final String overlay = s2sAuthenticationOverlay();
        logger.info("Setting up SQL overlay directories for server-to-server authentication with {}...", overlay);
        for (int i = 1; i <= 2; i++) {
            Files.createDirectories(Path.of("openfire-docker-compose/federation/sql/" + i));
            Files.deleteIfExists(Path.of("openfire-docker-compose/federation/sql/" + i + "/" + LEGACY_OVERLAY_TARGET));
            Files.copy(
                    Path.of("src/test/resources/docker/federation/" + overlay),
                    Path.of("openfire-docker-compose/federation/sql/" + i + "/" + OVERLAY_TARGET),
                    StandardCopyOption.REPLACE_EXISTING
            );
            final Path streamManagementTarget = Path.of("openfire-docker-compose/federation/sql/" + i + "/" + STREAM_MANAGEMENT_OVERLAY_TARGET);
            if (isStreamManagement()) {
                Files.copy(
                        Path.of("src/test/resources/docker/federation/" + String.format(STREAM_MANAGEMENT_OVERLAY, i)),
                        streamManagementTarget,
                        StandardCopyOption.REPLACE_EXISTING
                );
            } else {
                Files.deleteIfExists(streamManagementTarget);
            }
        }
    }

    /**
     * Starts the relay container, through which each server reaches the other when Stream Management is enabled: port
     * 5269 leads to xmpp2, port 5270 to xmpp1. A container left behind by an interrupted run is removed first.
     *
     * @throws IOException if the docker command cannot be run
     * @throws InterruptedException if the thread is interrupted while waiting for the command
     */
    private static void startRelay() throws IOException, InterruptedException {
        logger.info("Starting the relay container for server-to-server connections...");
        docker(false, "rm", "-f", RELAY_CONTAINER);
        docker(true, "run", "-d", "--name", RELAY_CONTAINER, "--network", FEDERATED_NETWORK, "--ip", RELAY_ADDRESS,
                RELAY_IMAGE, "python3", "-u", "-c", Files.readString(Path.of(RELAY_SCRIPT)));
    }

    /**
     * Freezes the connections through the relay: what either server sends on them from now on is dropped, so it stays
     * unacknowledged.
     *
     * @throws IOException if the docker command cannot be run
     * @throws InterruptedException if the thread is interrupted while waiting for the command
     */
    public static void freezeRelay() throws IOException, InterruptedException {
        docker(true, "kill", "--signal", "USR1", RELAY_CONTAINER);
    }

    /**
     * Breaks every connection through the relay, without closing the streams on them: each is closed in an orderly way
     * (TCP FIN) towards both servers. What was in flight is lost. Connections made later are relayed as normal.
     *
     * A reset connection is not used: Openfire does not keep a session for resumption when its connection is reset
     * (OF-3464).
     *
     * @throws IOException if the docker command cannot be run
     * @throws InterruptedException if the thread is interrupted while waiting for the command
     */
    public static void breakRelay() throws IOException, InterruptedException {
        docker(true, "kill", "--signal", "USR2", RELAY_CONTAINER);
    }

    private static void removeRelay() throws IOException, InterruptedException {
        if (isStreamManagement()) {
            docker(false, "rm", "-f", RELAY_CONTAINER);
        }
    }

    private static void docker(final boolean mustSucceed, final String... args) throws IOException, InterruptedException {
        final String[] command = new String[args.length + 1];
        command[0] = "docker";
        System.arraycopy(args, 0, command, 1, args.length);
        final Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        final String output = new String(process.getInputStream().readAllBytes()).trim();
        final int exitCode = process.waitFor();
        if (mustSucceed && exitCode != 0) {
            throw new IllegalStateException("Command failed (exit code " + exitCode + "): " + String.join(" ", command) + ": " + output);
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

        // Set Openfire image name
        // Check environment variable first, fallback to "openfire" if not set
        String openFireImage = System.getenv("OPENFIRE_IMAGE");
        if (openFireImage == null || openFireImage.trim().isEmpty()) {
            openFireImage = "openfire";
        }

        // Set Openfire version tag
        // Check environment variable first, fallback to "latest" if not set
        String openFireTag = System.getenv("OPENFIRE_TAG");
        if (openFireTag == null || openFireTag.trim().isEmpty()) {
            openFireTag = "latest";
        }

        logger.info("Configured to use Openfire Docker image {}:{}", openFireImage, openFireTag);
        processBuilder.environment().put("OPENFIRE_IMAGE", openFireImage);
        processBuilder.environment().put("OPENFIRE_TAG", openFireTag);

        Process process = processBuilder.start();
        int exitCode = process.waitFor();

        if (exitCode != 0) {
            throw new RuntimeException("Failed to start federated environment, exit code: " + exitCode);
        }
    }

    /**
     * Waits for the federated environment to have fully initialized and all servers to be healthy.
     * <p>
     * Blocks until all Openfire instances in the federated environment report a healthy status,
     * or until {@link #STARTUP_WAIT} has elapsed.
     *
     * @throws IllegalStateException if any server does not become healthy within the allotted time
     * @throws InterruptedException if the thread is interrupted while waiting
     * @throws IOException if an error occurs communicating with the Docker daemon
     */
    private static void waitForFederatedEnvironment() throws IllegalStateException, InterruptedException, IOException
    {
        logger.info("Waiting up to {} seconds for servers to initialize...", STARTUP_WAIT.toSeconds());
        final Instant deadline = Instant.now().plus(STARTUP_WAIT);
        waitForHealthy("openfire", "xmpp1", deadline);
        waitForHealthy("openfire", "xmpp2", deadline);
    }

    /**
     * Blocks until a specific Docker container reports a healthy status, or the deadline is reached.
     *
     * The container is identified by the standard Compose naming convention: {@code <project>-<service>-1}.
     *
     * Health status is polled using the Docker inspect command.
     *
     * @param project  the Docker Compose project name
     * @param service  the service name within the Compose project
     * @param deadline the point in time after which the wait is abandoned
     * @throws IllegalStateException if the container does not become healthy before the deadline
     * @throws InterruptedException  if the thread is interrupted while waiting
     * @throws IOException           if an error occurs communicating with the Docker daemon
     */
    private static void waitForHealthy(String project, String service, Instant deadline) throws IOException, InterruptedException
    {
        System.out.print("Waiting for " + service + "...");
        while (Instant.now().isBefore(deadline)) {
            if (isContainerHealthy(project, service)) {
                System.out.println(" healthy.");
                return;
            }
            Thread.sleep(1_000);
            System.out.print(".");
        }
        throw new IllegalStateException("Timed out waiting for " + service + " to become healthy");
    }

    /**
     * Checks whether a Docker container is currently reporting a healthy status.
     *
     * Invokes {@code docker inspect} to retrieve the container's health status. Returns {@code false} for any status
     * other than {@code healthy}, including {@code starting}, {@code unhealthy}, or if the container cannot be found.
     *
     * @param project the Docker Compose project name
     * @param service the service name within the Compose project
     * @return {@code true} if the container's health status is {@code healthy}, {@code false} otherwise
     * @throws InterruptedException if the thread is interrupted while waiting for the {@code docker inspect} process to complete
     * @throws IOException if the {@code docker} executable cannot be found or an I/O error occurs
     */
    private static boolean isContainerHealthy(String project, String service) throws IOException, InterruptedException
    {
        final String containerName = project + "-" + service + "-1";
        final ProcessBuilder pb = new ProcessBuilder("docker", "inspect", "--format", "{{.State.Health.Status}}", containerName);
        pb.redirectErrorStream(true);

        final Process process = pb.start();
        final String output = new String(process.getInputStream().readAllBytes()).trim();
        process.waitFor();

        return "healthy".equals(output);
    }

    /**
     * Invokes the get_logs.sh script to export Openfire logs from the images, into the directory that
     * {@value #LOG_DIRECTORY_PROPERTY} names, so that each run's logs are kept next to its test reports.
     *
     * @throws IOException if script execution fails
     * @throws InterruptedException if script execution is interrupted
     */
    private static void exportLogs() throws IOException, InterruptedException {
        final Path logDirectory = Path.of(System.getProperty(LOG_DIRECTORY_PROPERTY, ".")).toAbsolutePath();
        Files.createDirectories(logDirectory);
        logger.info("Exporting Openfire logs to {}...", logDirectory);

        final File script = new File("openfire-docker-compose/scripts/get_logs.sh").getAbsoluteFile();
        script.setExecutable(true);

        // The script writes to relative paths, so it is run from the log directory.
        final ProcessBuilder processBuilder = new ProcessBuilder("/bin/bash", script.getPath())
            .directory(logDirectory.toFile())
            .inheritIO();
        processBuilder.start().waitFor();
    }

    /**
     * Removes SQL overlay files created during setup.
     *
     * @throws IOException if file deletion fails
     */
    private static void cleanupSqlOverlay() throws IOException {
        logger.info("Cleaning up SQL overlay files...");
        for (int i = 1; i <= 2; i++) {
            Files.deleteIfExists(Path.of("openfire-docker-compose/federation/sql/" + i + "/" + OVERLAY_TARGET));
            Files.deleteIfExists(Path.of("openfire-docker-compose/federation/sql/" + i + "/" + STREAM_MANAGEMENT_OVERLAY_TARGET));
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

    /**
     * Removes the {@code _data} directory of the federated environment, which the Openfire containers write to.
     *
     * On Linux, files that the containers create there are owned by the user that the containers run as, so the user
     * that runs the tests cannot always delete them. The start script deletes this directory, so without this, the
     * environment could not be started again in the same build, as the run with SASL EXTERNAL does after the run with
     * dialback. The directory is therefore deleted from a container, as root.
     *
     * @throws IOException if the docker command cannot be run
     * @throws InterruptedException if the thread is interrupted while waiting for the command
     * @throws IllegalStateException if the directory could not be deleted
     */
    private static void removeDataDirectory() throws IOException, InterruptedException {
        final Path federation = Path.of("openfire-docker-compose/federation").toAbsolutePath();
        if (!Files.exists(federation.resolve("_data"))) {
            return;
        }
        logger.info("Removing the data directory of the federated environment...");
        final ProcessBuilder processBuilder = new ProcessBuilder("docker", "run", "--rm",
                "-v", federation + ":/federation", "alpine:3.20", "rm", "-rf", "/federation/_data")
                .inheritIO();
        final int exitCode = processBuilder.start().waitFor();
        if (exitCode != 0 || Files.exists(federation.resolve("_data"))) {
            throw new IllegalStateException("Failed to remove " + federation.resolve("_data") + ", exit code: " + exitCode);
        }
    }
}
