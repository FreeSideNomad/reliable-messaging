package com.acme.reliable.e2e;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

final class EnvironmentSupport {
    private static final Path PROJECT_DIR = Paths.get("").toAbsolutePath();
    private static final Path TARGET_DIR = PROJECT_DIR.resolve("target");
    private static final Path APP_JAR = TARGET_DIR.resolve("reliable-single-1.0.0.jar");
    private static final Path APP_LOG = TARGET_DIR.resolve("e2e-app.log");

    private final Map<String, String> env;
    private Process appProcess;
    private boolean composeStarted;

    private EnvironmentSupport(Map<String, String> env) {
        this.env = env;
    }

    static EnvironmentSupport load() {
        Map<String, String> merged = new HashMap<>(System.getenv());
        Path envFile = PROJECT_DIR.resolve(".env");
        if (Files.exists(envFile)) {
            try {
                List<String> lines = Files.readAllLines(envFile, StandardCharsets.UTF_8);
                for (String line : lines) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                        continue;
                    }
                    int idx = trimmed.indexOf('=');
                    if (idx <= 0) {
                        continue;
                    }
                    String key = trimmed.substring(0, idx).trim();
                    String value = trimmed.substring(idx + 1).trim();
                    merged.put(key, value);
                }
            } catch (IOException e) {
                throw new IllegalStateException("Failed to read .env file", e);
            }
        }
        return new EnvironmentSupport(merged);
    }

    Map<String, String> env() {
        return env;
    }

    void ensureInfrastructureReady() {
        startDockerCompose();
        waitForPort(host("POSTGRES_HOST", "localhost"), port("POSTGRES_PORT", 5432), Duration.ofSeconds(60));
        waitForPort(host("MQ_HOST", "localhost"), port("MQ_PORT", 1414), Duration.ofSeconds(60));
        waitForPort(host("KAFKA_BOOTSTRAP_HOST", "localhost"), port("KAFKA_PORT", 9092), Duration.ofSeconds(90));
        startApplication();
        waitForHttpReady(Duration.ofSeconds(90));
    }

    void shutdownInfrastructure() {
        stopApplication();
        if (composeStarted) {
            runCommand(List.of("docker-compose", "down", "--remove-orphans"), Duration.ofMinutes(2));
        }
    }

    private void startDockerCompose() {
        runCommand(List.of("docker-compose", "up", "-d"), Duration.ofMinutes(2));
        composeStarted = true;
    }

    private void startApplication() {
        if (!Files.exists(APP_JAR)) {
            throw new IllegalStateException("Application JAR not found at " + APP_JAR + ". Run mvn package before e2e tests.");
        }
        if (appProcess != null && appProcess.isAlive()) {
            return;
        }
        try {
            Files.createDirectories(TARGET_DIR);
            ProcessBuilder pb = new ProcessBuilder("java", "-jar", APP_JAR.toString());
            pb.directory(PROJECT_DIR.toFile());
            Map<String, String> pbEnv = pb.environment();
            pbEnv.putAll(env);
            pb.redirectErrorStream(true);
            pb.redirectOutput(APP_LOG.toFile());
            appProcess = pb.start();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to start application process", e);
        }
    }

    private void stopApplication() {
        if (appProcess != null && appProcess.isAlive()) {
            appProcess.destroy();
            try {
                if (!appProcess.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)) {
                    appProcess.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void waitForHttpReady(Duration timeout) {
        var client = HttpClientSupport.httpClient();
        Instant deadline = Instant.now().plus(timeout);
        var uri = HttpClientSupport.baseUri(port("APP_PORT", 8080));
        while (Instant.now().isBefore(deadline)) {
            try {
                var req = java.net.http.HttpRequest.newBuilder(uri).GET().build();
                java.net.http.HttpResponse<Void> resp = client.send(req, java.net.http.HttpResponse.BodyHandlers.discarding());
                if (resp.statusCode() > 0) {
                    return;
                }
            } catch (IOException | InterruptedException e) {
                sleep(Duration.ofSeconds(1));
                continue;
            }
            sleep(Duration.ofSeconds(1));
        }
        throw new IllegalStateException("Application did not become ready within " + timeout);
    }

    private void waitForPort(String host, int port, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            try (java.net.Socket socket = new java.net.Socket()) {
                socket.connect(new java.net.InetSocketAddress(host, port), (int) Duration.ofSeconds(3).toMillis());
                return;
            } catch (IOException e) {
                sleep(Duration.ofSeconds(2));
            }
        }
        throw new IllegalStateException("Port %s:%d did not open in time".formatted(host, port));
    }

    private void runCommand(List<String> command, Duration timeout) {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(PROJECT_DIR.toFile());
        pb.environment().putAll(env);
        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to run command: " + command, e);
        }
        try {
            boolean finished = process.waitFor(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IllegalStateException("Command timed out: " + command);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while running command: " + command, e);
        }
        if (process.exitValue() != 0) {
            String output = readStream(process.getInputStream()) + readStream(process.getErrorStream());
            throw new IllegalStateException("Command failed: " + command + System.lineSeparator() + output);
        }
    }

    private static String readStream(java.io.InputStream inputStream) {
        try {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String host(String envKey, String fallback) {
        return Optional.ofNullable(env.get(envKey))
                .orElseGet(() -> Optional.ofNullable(env.get(envKey.toUpperCase(Locale.US)))
                        .orElse(fallback));
    }

    private int port(String envKey, int fallback) {
        return Optional.ofNullable(env.get(envKey))
                .or(() -> Optional.ofNullable(env.get(envKey.toUpperCase(Locale.US))))
                .map(Integer::parseInt)
                .orElse(fallback);
    }
}
