package com.github.mattiasgreen.wiremock.logging;

import com.github.tomakehurst.wiremock.admin.Router;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.extension.AdminApiExtension;
import com.github.tomakehurst.wiremock.http.RequestMethod;
import com.github.tomakehurst.wiremock.http.ResponseDefinition;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Admin API Extension providing runtime inspection, updates, and reset of Logback logger levels
 * under {@code /__admin/logging/levels} and {@code /__admin/logging/reset}.
 */
public final class LoggingAdminEndpoint implements AdminApiExtension {

    public static final String EXTENSION_NAME = "logging-admin-endpoint";
    private static final Logger LOG = LoggerFactory.getLogger(LoggingAdminEndpoint.class);

    private final LogbackLevelManager levelManager;

    public LoggingAdminEndpoint() {
        this(new LogbackLevelManager());
    }

    public LoggingAdminEndpoint(LogbackLevelManager levelManager) {
        this.levelManager = Objects.requireNonNull(levelManager, "levelManager must not be null");
    }

    @Override
    public String getName() {
        return EXTENSION_NAME;
    }

    @Override
    public void contributeAdminApiRoutes(Router router) {
        router.add(
                RequestMethod.GET,
                "/logging/levels",
                (admin, serveEvent, pathParams) -> plainTextResponse(200, levelManager.formatConfiguredLevels()));
        router.add(
                RequestMethod.PUT,
                "/logging/levels",
                (admin, serveEvent, pathParams) ->
                        updateLevels(serveEvent.getRequest().getBodyAsString()));
        router.add(
                RequestMethod.POST,
                "/logging/levels",
                (admin, serveEvent, pathParams) ->
                        updateLevels(serveEvent.getRequest().getBodyAsString()));
        router.add(
                RequestMethod.POST,
                "/logging/reset",
                (admin, serveEvent, pathParams) -> plainTextResponse(200, levelManager.reset()));
    }

    public LogbackLevelManager getLevelManager() {
        return levelManager;
    }

    private ResponseDefinition updateLevels(String body) {
        try {
            String updated = levelManager.applyLevels(body);
            return plainTextResponse(200, updated);
        } catch (IllegalArgumentException e) {
            String sanitizedMsg = LogbackLevelManager.sanitize(e.getMessage());
            LOG.warn("Rejected invalid log level update: {}", sanitizedMsg);
            return plainTextResponse(400, "Invalid log level specification: " + sanitizedMsg);
        }
    }

    private static ResponseDefinition plainTextResponse(int status, String body) {
        return ResponseDefinitionBuilder.responseDefinition()
                .withStatus(status)
                .withHeader("Content-Type", "text/plain; charset=utf-8")
                .withBody(body)
                .build();
    }
}
