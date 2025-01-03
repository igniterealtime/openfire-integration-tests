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

public class FederatedTestEnvironment {
    private static final Logger logger = LoggerFactory.getLogger(FederatedTestEnvironment.class);
    private static boolean initialized = false;
    private static final Duration STARTUP_WAIT = Duration.of(10, ChronoUnit.SECONDS);

    public static final int XMPP1_PORT = 5221;
    public static final int XMPP2_PORT = 5222;
    public static final String XMPP1_DOMAIN = "xmpp1.localhost.example";
    public static final String XMPP2_DOMAIN = "xmpp2.localhost.example";

    public static final String USER_1 = "user1";
    public static final String USER_2 = "user2";
    public static final String USER_3 = "user3";
    public static final String USER_4 = "user4";
    public static final String PASSWORD = "password";

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

    public static synchronized void stop() throws Exception {
        if (initialized) {
            cleanupSqlOverlay();
            stopFederatedEnvironment();
            initialized = false;
        }
    }

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

    private static void startFederatedEnvironment() throws IOException, InterruptedException {
        logger.info("Starting federated environment...");

        File startScript = new File("openfire-docker-compose/federation/start.sh");
        startScript.setExecutable(true);

        ProcessBuilder processBuilder = new ProcessBuilder("/bin/bash", startScript.getPath())
                .directory(new File("."))
                .inheritIO();

        // TODO - check external environment first before setting this
        processBuilder.environment().put("OPENFIRE_TAG", "latest");

        Process process = processBuilder.start();
        int exitCode = process.waitFor();

        if (exitCode != 0) {
            throw new RuntimeException("Failed to start federated environment, exit code: " + exitCode);
        }
    }

    private static void cleanupSqlOverlay() throws IOException {
        logger.info("Cleaning up SQL overlay files...");
        for (int i = 1; i <= 2; i++) {
            Files.deleteIfExists(Path.of("openfire-docker-compose/federation/sql/" + i + "/zz-disable-starttls.sql"));
        }
    }

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