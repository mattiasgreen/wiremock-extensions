package com.github.mattiasgreen.wiremock.logging;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import com.github.mattiasgreen.wiremock.logging.testutil.TestLogAppender;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

public class LoggingAdminEndpointTest {

    private WireMockServer server;
    private HttpClient httpClient;
    private LogbackLevelManager levelManager;

    @BeforeEach
    void setUp() {
        TestLogAppender.clear();
        System.clearProperty(LogbackLevelManager.PROP_LOG_LEVEL);
        System.clearProperty(LogbackLevelManager.PROP_WIREMOCK_LOG_LEVEL);

        levelManager = new LogbackLevelManager(key -> null);
        levelManager.restoreBaseline();

        server = new WireMockServer(
                WireMockConfiguration.options().dynamicPort().extensions(new LoggingAdminEndpoint(levelManager)));
        server.start();

        httpClient = HttpClient.newHttpClient();
    }

    @AfterEach
    void tearDown() {
        if (server != null && server.isRunning()) {
            server.stop();
        }
        System.clearProperty(LogbackLevelManager.PROP_LOG_LEVEL);
        System.clearProperty(LogbackLevelManager.PROP_WIREMOCK_LOG_LEVEL);
        if (levelManager != null) {
            levelManager.restoreBaseline();
        }
    }

    @Test
    void shouldGetInitialConfiguredLevelsAndUpdateAtRuntimeViaPut() throws Exception {
        HttpResponse<String> initialResp = sendGet("/__admin/logging/levels");
        assertThat(initialResp.statusCode()).isEqualTo(200);
        assertThat(initialResp.headers().firstValue("Content-Type").orElse("")).contains("text/plain");
        assertThat(initialResp.body()).startsWith("ROOT=");

        HttpResponse<String> putResp =
                sendPut("/__admin/logging/levels", "com.github.mattiasgreen=TRACE,se.laz.casual=TRACE");
        assertThat(putResp.statusCode()).isEqualTo(200);
        assertThat(putResp.body()).contains("com.github.mattiasgreen=TRACE").contains("se.laz.casual=TRACE");

        LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger mgLogger = ctx.getLogger("com.github.mattiasgreen");
        Logger casualLogger = ctx.getLogger("se.laz.casual");
        assertThat(mgLogger.getLevel()).isEqualTo(Level.TRACE);
        assertThat(casualLogger.getLevel()).isEqualTo(Level.TRACE);
        assertThat(mgLogger.isTraceEnabled()).isTrue();
        assertThat(casualLogger.isTraceEnabled()).isTrue();

        // Merge update: change se.laz.casual to DEBUG while keeping com.github.mattiasgreen at TRACE
        HttpResponse<String> mergeResp = sendPut("/__admin/logging/levels", "\"se.laz.casual=DEBUG\"");
        assertThat(mergeResp.statusCode()).isEqualTo(200);
        assertThat(mergeResp.body()).contains("com.github.mattiasgreen=TRACE").contains("se.laz.casual=DEBUG");
        assertThat(mgLogger.getLevel()).isEqualTo(Level.TRACE);
        assertThat(casualLogger.getLevel()).isEqualTo(Level.DEBUG);

        // Unset logger via INHERITED / NULL
        HttpResponse<String> unsetResp = sendPut("/__admin/logging/levels", "se.laz.casual=INHERITED");
        assertThat(unsetResp.statusCode()).isEqualTo(200);
        assertThat(unsetResp.body()).contains("com.github.mattiasgreen=TRACE").doesNotContain("se.laz.casual=");
        assertThat(casualLogger.getLevel()).isNull();
    }

    @Test
    void shouldRejectInvalidPutAtomicallyWithoutPartialMutation() throws Exception {
        LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger mgLogger = ctx.getLogger("com.github.mattiasgreen");
        assertThat(mgLogger.getLevel()).isNull();

        HttpResponse<String> badLevelResp =
                sendPut("/__admin/logging/levels", "com.github.mattiasgreen=TRACE,se.laz.casual=BOGUS");
        assertThat(badLevelResp.statusCode()).isEqualTo(400);
        assertThat(badLevelResp.body()).contains("Unsupported log level 'BOGUS'");
        assertThat(mgLogger.getLevel()).isNull();

        HttpResponse<String> badFormatResp = sendPut("/__admin/logging/levels", "com.github.mattiasgreen");
        assertThat(badFormatResp.statusCode()).isEqualTo(400);

        HttpResponse<String> emptyResp = sendPut("/__admin/logging/levels", "   ");
        assertThat(emptyResp.statusCode()).isEqualTo(400);

        HttpResponse<String> rootNullResp = sendPut("/__admin/logging/levels", "ROOT=NULL");
        assertThat(rootNullResp.statusCode()).isEqualTo(400);
    }

    @Test
    void shouldApplyStartupLogLevelFromEnvAndResetToStartupState() throws Exception {
        Map<String, String> fakeEnv = Map.of(
                LogbackLevelManager.ENV_LOG_LEVEL,
                "com.github.mattiasgreen=DEBUG,invalid_entry_ignored,se.laz.casual=TRACE");
        LogbackLevelManager envManager = new LogbackLevelManager(fakeEnv::get);

        LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
        assertThat(ctx.getLogger("com.github.mattiasgreen").getLevel()).isEqualTo(Level.DEBUG);
        assertThat(ctx.getLogger("se.laz.casual").getLevel()).isEqualTo(Level.TRACE);

        // Mutate at runtime
        envManager.applyLevels("com.github.mattiasgreen=ERROR,org.eclipse.jetty=WARN");
        assertThat(ctx.getLogger("com.github.mattiasgreen").getLevel()).isEqualTo(Level.ERROR);
        assertThat(ctx.getLogger("org.eclipse.jetty").getLevel()).isEqualTo(Level.WARN);

        // Reset restores the startup state (including LOG_LEVEL overrides) and clears runtime additions
        String afterReset = envManager.reset();
        assertThat(afterReset)
                .contains("com.github.mattiasgreen=DEBUG")
                .contains("se.laz.casual=TRACE")
                .doesNotContain("org.eclipse.jetty=");
        assertThat(ctx.getLogger("com.github.mattiasgreen").getLevel()).isEqualTo(Level.DEBUG);
        assertThat(ctx.getLogger("se.laz.casual").getLevel()).isEqualTo(Level.TRACE);
        assertThat(ctx.getLogger("org.eclipse.jetty").getLevel()).isNull();
    }

    @Test
    void shouldSupportSystemPropertyFallbackAndAdminResetEndpoint() throws Exception {
        System.setProperty(LogbackLevelManager.PROP_LOG_LEVEL, "se.laz.casual=DEBUG");
        levelManager.initializeFromEnvironment();

        HttpResponse<String> getResp = sendGet("/__admin/logging/levels");
        assertThat(getResp.statusCode()).isEqualTo(200);
        assertThat(getResp.body()).contains("se.laz.casual=DEBUG");

        sendPut("/__admin/logging/levels", "se.laz.casual=TRACE,com.github.mattiasgreen=TRACE");

        HttpResponse<String> resetResp = sendPost("/__admin/logging/reset", "");
        assertThat(resetResp.statusCode()).isEqualTo(200);
        assertThat(resetResp.body()).contains("se.laz.casual=DEBUG").doesNotContain("com.github.mattiasgreen=");
    }

    @Test
    void shouldVerifyActualLogEventEmissionAcrossRuntimeLevelChangesAndReset() throws Exception {
        org.slf4j.Logger mgSubLogger = LoggerFactory.getLogger("com.github.mattiasgreen.runtime.VerificationProbe");
        org.slf4j.Logger casualSubLogger = LoggerFactory.getLogger("se.laz.casual.network.ProtocolProbe");

        TestLogAppender.clear();
        mgSubLogger.trace("trace-before-enable-mg");
        casualSubLogger.trace("trace-before-enable-casual");
        assertThat(TestLogAppender.getEvents())
                .extracting(e -> e.getFormattedMessage())
                .doesNotContain("trace-before-enable-mg", "trace-before-enable-casual");

        // Enable TRACE at runtime via Admin API PUT
        HttpResponse<String> putResp =
                sendPut("/__admin/logging/levels", "com.github.mattiasgreen=TRACE,se.laz.casual=TRACE");
        assertThat(putResp.statusCode()).isEqualTo(200);

        TestLogAppender.clear();
        mgSubLogger.trace("trace-after-enable-mg");
        casualSubLogger.trace("trace-after-enable-casual");
        assertThat(TestLogAppender.getEvents())
                .extracting(e -> e.getFormattedMessage())
                .contains("trace-after-enable-mg", "trace-after-enable-casual");

        // Elevate se.laz.casual to WARN while keeping com.github.mattiasgreen at TRACE
        HttpResponse<String> warnResp = sendPut("/__admin/logging/levels", "se.laz.casual=WARN");
        assertThat(warnResp.statusCode()).isEqualTo(200);

        TestLogAppender.clear();
        mgSubLogger.trace("trace-still-enabled-mg");
        casualSubLogger.info("info-suppressed-casual");
        casualSubLogger.warn("warn-emitted-casual");
        assertThat(TestLogAppender.getEvents())
                .extracting(e -> e.getFormattedMessage())
                .contains("trace-still-enabled-mg", "warn-emitted-casual")
                .doesNotContain("info-suppressed-casual");

        // Reset back to startup state (INFO root) and verify TRACE is suppressed again
        HttpResponse<String> resetResp = sendPost("/__admin/logging/reset", "");
        assertThat(resetResp.statusCode()).isEqualTo(200);

        TestLogAppender.clear();
        mgSubLogger.trace("trace-after-reset-mg");
        casualSubLogger.trace("trace-after-reset-casual");
        assertThat(TestLogAppender.getEvents())
                .extracting(e -> e.getFormattedMessage())
                .doesNotContain("trace-after-reset-mg", "trace-after-reset-casual");
    }

    private HttpResponse<String> sendGet(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(server.baseUrl() + path))
                .GET()
                .build();
        return httpClient.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> sendPut(String path, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(server.baseUrl() + path))
                .header("Content-Type", "text/plain")
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return httpClient.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> sendPost(String path, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(server.baseUrl() + path))
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return httpClient.send(req, HttpResponse.BodyHandlers.ofString());
    }
}
