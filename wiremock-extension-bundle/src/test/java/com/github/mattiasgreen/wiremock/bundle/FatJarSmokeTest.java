package com.github.mattiasgreen.wiremock.bundle;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class FatJarSmokeTest {

    private static final HttpClient HTTP_CLIENT =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    @Test
    void testFatJarLaunchesStandaloneDevServerAndRespondsToExtensions() throws Exception {
        File fatJar = locateFatJar();
        int httpPort = findFreePort();
        int netemPort = findFreePort();
        int casualPort = findFreePort();

        String javaBin = ProcessHandle.current().info().command().orElse("java");
        ProcessBuilder pb =
                new ProcessBuilder(javaBin, "-jar", fatJar.getAbsolutePath(), "--port", String.valueOf(httpPort));
        pb.environment().put("WIREMOCK_NETEM_PORT", String.valueOf(netemPort));
        pb.environment().put("WIREMOCK_CASUAL_PORT", String.valueOf(casualPort));
        pb.redirectErrorStream(true);

        Process process = pb.start();
        StringBuilder outputLog = new StringBuilder();
        Thread logReader = new Thread(() -> {
            try (BufferedReader reader =
                    new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    outputLog.append(line).append("\n");
                }
            } catch (IOException ignored) {
            }
        });
        logReader.setDaemon(true);
        logReader.start();

        try {
            waitForServerReady(httpPort, 25);

            // 1. Verify WireMock Admin Mappings API
            HttpResponse<String> adminResp = HTTP_CLIENT.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://127.0.0.1:" + httpPort + "/__admin/mappings"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(adminResp.statusCode()).isEqualTo(200);

            // 2. Verify Embedded Web UI
            HttpResponse<String> uiResp = HTTP_CLIENT.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://127.0.0.1:" + httpPort + "/__admin/ui/"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(uiResp.statusCode()).isEqualTo(200);
            assertThat(uiResp.body()).contains("WireMock Console");

            // 3. Verify Prometheus Metrics Endpoint
            HttpResponse<String> promResp = HTTP_CLIENT.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://127.0.0.1:" + httpPort + "/__admin/metrics/prometheus"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(promResp.statusCode()).isEqualTo(200);

            // 4. Verify Casual Extension Route
            HttpResponse<String> casualResp = HTTP_CLIENT.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://127.0.0.1:" + httpPort + "/__admin/casual/config"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(casualResp.statusCode()).isEqualTo(200);

            // 5. Verify TCP NetEm Extension Route
            HttpResponse<String> netemResp = HTTP_CLIENT.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://127.0.0.1:" + httpPort + "/__admin/tcp-netem/proxies"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(netemResp.statusCode()).isEqualTo(200);

            // 6. Create and invoke a dynamic stub mapping
            String stubJson =
                    """
                    {
                      "request": {
                        "method": "GET",
                        "url": "/fat-jar-smoke-test"
                      },
                      "response": {
                        "status": 200,
                        "body": "fat-jar-verified"
                      }
                    }
                    """;
            HttpResponse<String> postStubResp = HTTP_CLIENT.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://127.0.0.1:" + httpPort + "/__admin/mappings"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(stubJson))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(postStubResp.statusCode()).isEqualTo(201);

            HttpResponse<String> invokeResp = HTTP_CLIENT.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://127.0.0.1:" + httpPort + "/fat-jar-smoke-test"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(invokeResp.statusCode()).isEqualTo(200);
            assertThat(invokeResp.body()).isEqualTo("fat-jar-verified");

        } finally {
            process.destroy();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        }
    }

    @Test
    void testFatJarLaunchesWireMockStandaloneRunnerViaWireMockRun() throws Exception {
        File fatJar = locateFatJar();
        int httpPort = findFreePort();

        String javaBin = ProcessHandle.current().info().command().orElse("java");
        ProcessBuilder pb = new ProcessBuilder(
                javaBin,
                "-cp",
                fatJar.getAbsolutePath(),
                "wiremock.Run",
                "--port",
                String.valueOf(httpPort),
                "--disable-banner");
        pb.redirectErrorStream(true);

        Process process = pb.start();
        StringBuilder outputLog = new StringBuilder();
        Thread logReader = new Thread(() -> {
            try (BufferedReader reader =
                    new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    outputLog.append(line).append("\n");
                }
            } catch (IOException ignored) {
            }
        });
        logReader.setDaemon(true);
        logReader.start();

        try {
            waitForServerReady(httpPort, 25);

            // Verify standard WireMock admin mapping responds
            HttpResponse<String> adminResp = HTTP_CLIENT.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://127.0.0.1:" + httpPort + "/__admin/mappings"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(adminResp.statusCode()).isEqualTo(200);

            // Verify that extensions are also auto-discovered via SPI under wiremock.Run
            HttpResponse<String> uiResp = HTTP_CLIENT.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://127.0.0.1:" + httpPort + "/__admin/ui/"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(uiResp.statusCode()).isEqualTo(200);
            assertThat(uiResp.body()).contains("WireMock Console");

        } finally {
            process.destroy();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        }
    }

    @Test
    void testFatJarLoadsMappingsFromWorkingDirectory() throws Exception {
        File fatJar = locateFatJar();
        int httpPort = findFreePort();
        Path tempDir = Files.createTempDirectory("wm-fatjar-mappings-test");
        Path mappingsDir = tempDir.resolve("mappings");
        Files.createDirectories(mappingsDir);

        String sampleStub =
                """
                {
                  "request": {
                    "method": "GET",
                    "url": "/hello-working-dir"
                  },
                  "response": {
                    "status": 200,
                    "body": "working-dir-mappings-verified"
                  }
                }
                """;
        Files.writeString(mappingsDir.resolve("test-stub.json"), sampleStub, StandardCharsets.UTF_8);

        String javaBin = ProcessHandle.current().info().command().orElse("java");
        ProcessBuilder pb =
                new ProcessBuilder(javaBin, "-jar", fatJar.getAbsolutePath(), "--port", String.valueOf(httpPort));
        pb.directory(tempDir.toFile());
        pb.redirectErrorStream(true);

        Process process = pb.start();
        Thread logReader = new Thread(() -> {
            try (BufferedReader reader =
                    new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                while (reader.readLine() != null) {
                    // discard output
                }
            } catch (IOException ignored) {
            }
        });
        logReader.setDaemon(true);
        logReader.start();

        try {
            waitForServerReady(httpPort, 25);

            HttpResponse<String> stubResp = HTTP_CLIENT.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://127.0.0.1:" + httpPort + "/hello-working-dir"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(stubResp.statusCode()).isEqualTo(200);
            assertThat(stubResp.body()).isEqualTo("working-dir-mappings-verified");

            HttpResponse<String> adminResp = HTTP_CLIENT.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create("http://127.0.0.1:" + httpPort + "/__admin/mappings"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(adminResp.statusCode()).isEqualTo(200);
            assertThat(adminResp.body()).contains("/hello-working-dir");
        } finally {
            process.destroy();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
            try (Stream<Path> walk = Files.walk(tempDir)) {
                walk.sorted(java.util.Comparator.reverseOrder())
                        .map(Path::toFile)
                        .forEach(File::delete);
            } catch (IOException ignored) {
            }
        }
    }

    private static File locateFatJar() {
        Path[] searchPaths = new Path[] {
            Paths.get("build", "libs"),
            Paths.get("wiremock-extension-bundle", "build", "libs"),
            Paths.get("..", "wiremock-extension-bundle", "build", "libs")
        };

        for (Path dir : searchPaths) {
            if (Files.isDirectory(dir)) {
                try (Stream<Path> stream = Files.list(dir)) {
                    Optional<Path> found = stream.filter(p -> {
                                String name = p.getFileName().toString();
                                return name.startsWith("wiremock-extension-bundle-")
                                        && name.endsWith("-standalone.jar");
                            })
                            .max(java.util.Comparator.comparingLong(
                                    p -> p.toFile().lastModified()));
                    if (found.isPresent()) {
                        return found.get().toFile();
                    }
                } catch (IOException ignored) {
                }
            }
        }
        throw new IllegalStateException(
                "Could not locate wiremock-extension-bundle-*-standalone.jar. Run ./gradlew fatJar first.");
    }

    private static void waitForServerReady(int port, int maxSeconds) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(maxSeconds);
        while (System.currentTimeMillis() < deadline) {
            try {
                HttpResponse<String> resp = HTTP_CLIENT.send(
                        HttpRequest.newBuilder()
                                .uri(URI.create("http://127.0.0.1:" + port + "/__admin/mappings"))
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) {
                    return;
                }
            } catch (Exception ignored) {
            }
            Thread.sleep(150);
        }
        throw new AssertionError("Server on port " + port + " did not become ready within " + maxSeconds + " seconds");
    }

    private static int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }
}
