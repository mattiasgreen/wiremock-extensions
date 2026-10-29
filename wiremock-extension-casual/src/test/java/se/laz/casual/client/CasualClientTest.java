package se.laz.casual.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.mattiasgreen.wiremock.casual.CasualWireMockGateway;
import com.github.tomakehurst.wiremock.WireMockServer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.laz.casual.api.buffer.CasualBufferUtils;
import se.laz.casual.api.flags.ErrorState;
import se.laz.casual.network.protocol.messages.domain.CasualDomainDiscoveryReplyMessage;

class CasualClientTest {

    private WireMockServer wireMockServer;
    private CasualWireMockGateway gateway;
    private int gatewayPort;

    @BeforeEach
    void setUp() throws Exception {
        wireMockServer = new WireMockServer(wireMockConfig().dynamicPort());
        wireMockServer.start();

        wireMockServer.stubFor(post(urlEqualTo("/__casual/services/echoService"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"echoed\": true, \"received\": 123}")));

        gateway = new CasualWireMockGateway(0, wireMockServer.baseUrl());
        gateway.start();
        gatewayPort = gateway.getPort();
    }

    @AfterEach
    void tearDown() {
        if (gateway != null) {
            gateway.close();
        }
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
    }

    @Test
    @DisplayName("Should successfully ping and discover services programmatically")
    void shouldPingAndDiscoverProgrammatically() throws Exception {
        try (CasualClient client = new CasualClient("127.0.0.1", gatewayPort)) {
            assertThat(client.ping()).isTrue();
            assertThat(client.isConnected()).isTrue();

            CasualDomainDiscoveryReplyMessage discovery = client.discover("echoService");
            assertThat(discovery).isNotNull();
            assertThat(discovery.getDomainName()).isEqualTo("wiremock-casual-domain");
            assertThat(discovery.getServices()).extracting("name").contains("echoService");
        }
    }

    @Test
    @DisplayName("Should execute tpcall successfully with parsed CasualCallResult")
    void shouldExecuteTpCallSuccessfully() throws Exception {
        try (CasualClient client = CasualClient.create("127.0.0.1", gatewayPort)) {
            CasualCallResult result = client.call("echoService", "{\"test\": 123}");

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.errorState()).isEqualTo(ErrorState.OK);
            assertThat(result.serviceName()).isEqualTo("echoService");
            assertThat(CasualBufferUtils.isJsonType(result.bufferType())).isTrue();
            assertThat(result.payload()).contains("\"echoed\": true");
            assertThat(result.durationMs()).isGreaterThanOrEqualTo(0);
        }
    }

    @Test
    @DisplayName("Should return TPENOENT result when service is not stubbed")
    void shouldHandleUnmatchedService() throws Exception {
        try (CasualClient client = CasualClient.create("127.0.0.1", gatewayPort)) {
            CasualCallResult result = client.call("unknownService", "{}");

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.errorState()).isEqualTo(ErrorState.TPENOENT);
        }
    }

    @Test
    @DisplayName("CLI: should run ping command successfully")
    void cliShouldRunPing() {
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);

        int exitCode =
                CasualClient.run(new String[] {"ping", "--port", String.valueOf(gatewayPort), "--no-color"}, out, err);

        assertThat(exitCode).isEqualTo(0);
        assertThat(outBytes.toString(StandardCharsets.UTF_8)).contains("Connected successfully");
    }

    @Test
    @DisplayName("CLI: should run call command with inline payload")
    void cliShouldRunCallWithInlinePayload() {
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);

        int exitCode = CasualClient.run(
                new String[] {
                    "call", "echoService", "{\"input\": 42}", "--port", String.valueOf(gatewayPort), "--no-color"
                },
                out,
                err);

        assertThat(exitCode).isEqualTo(0);
        String output = outBytes.toString(StandardCharsets.UTF_8);
        assertThat(output).contains("Status:     OK");
        assertThat(output).contains("\"echoed\": true");
    }

    @Test
    @DisplayName("CLI: should run call command with file payload (@file)")
    void cliShouldRunCallWithFilePayload(@TempDir Path tempDir) throws Exception {
        Path jsonFile = tempDir.resolve("request.json");
        Files.writeString(jsonFile, "{\"fromFile\": true}", StandardCharsets.UTF_8);

        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);

        int exitCode = CasualClient.run(
                new String[] {
                    "call",
                    "echoService",
                    "@" + jsonFile.toAbsolutePath(),
                    "--port",
                    String.valueOf(gatewayPort),
                    "--no-color"
                },
                out,
                err);

        assertThat(exitCode).isEqualTo(0);
        assertThat(outBytes.toString(StandardCharsets.UTF_8)).contains("Status:     OK");
    }

    @Test
    @DisplayName("CLI: should run call command with stdin payload (-)")
    void cliShouldRunCallWithStdinPayload() {
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);
        ByteArrayInputStream in = new ByteArrayInputStream("{\"fromStdin\": 99}".getBytes(StandardCharsets.UTF_8));

        int exitCode = CasualClient.run(
                new String[] {"call", "echoService", "-", "--port", String.valueOf(gatewayPort), "--no-color"},
                out,
                err,
                in);

        assertThat(exitCode).isEqualTo(0);
        assertThat(outBytes.toString(StandardCharsets.UTF_8)).contains("Status:     OK");
    }

    @Test
    @DisplayName("CLI: should exit with error code when call fails with TPENOENT")
    void cliShouldExitWithErrorWhenUnmatched() {
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);

        int exitCode = CasualClient.run(
                new String[] {"call", "nonExistent", "{}", "--port", String.valueOf(gatewayPort), "--no-color"},
                out,
                err);

        assertThat(exitCode).isEqualTo(2);
        assertThat(outBytes.toString(StandardCharsets.UTF_8)).contains("TPENOENT");
    }

    @Test
    @DisplayName("CLI: should print help when --help flag is provided")
    void cliShouldPrintHelp() {
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);

        int exitCode = CasualClient.run(new String[] {"--help"}, out, err);

        assertThat(exitCode).isEqualTo(0);
        assertThat(outBytes.toString(StandardCharsets.UTF_8)).contains("Usage:");
        assertThat(outBytes.toString(StandardCharsets.UTF_8)).contains("Commands:");
    }
}
