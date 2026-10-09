package com.github.mattiasgreen.wiremock.stateful.extension;

import com.github.mattiasgreen.wiremock.stateful.engine.StateEngine;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.extension.ResponseDefinitionTransformerV2;
import com.github.tomakehurst.wiremock.http.HttpHeader;
import com.github.tomakehurst.wiremock.http.Request;
import com.github.tomakehurst.wiremock.http.ResponseDefinition;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class StatefulResponseTransformer implements ResponseDefinitionTransformerV2 {

    public static final String EXTENSION_NAME = "stateful-transformer";
    public static final String CORRELATION_HEADER = "x-correlation-id";

    private final StateEngine stateEngine;

    public StatefulResponseTransformer(StateEngine stateEngine) {
        this.stateEngine = Objects.requireNonNull(stateEngine, "stateEngine must not be null");
    }

    @Override
    public String getName() {
        return EXTENSION_NAME;
    }

    @Override
    public boolean applyGlobally() {
        return true;
    }

    @Override
    public ResponseDefinition transform(ServeEvent serveEvent) {
        Request request = serveEvent.getRequest();

        String correlationId = null;
        if (request.containsHeader(CORRELATION_HEADER)) {
            correlationId = request.header(CORRELATION_HEADER).firstValue();
        }

        Map<String, String> headers = new HashMap<>();
        for (HttpHeader header : request.getHeaders().all()) {
            headers.put(header.key(), header.firstValue());
        }

        String method = request.getMethod().getName();
        String path = request.getUrl();
        // Strip query parameters if present
        int queryIdx = path.indexOf('?');
        if (queryIdx >= 0) {
            path = path.substring(0, queryIdx);
        }

        StateEngine.ExecutionResult result =
                stateEngine.execute(correlationId, method, path, request.getBodyAsString(), headers);

        if (result == null) {
            return serveEvent.getResponseDefinition();
        }

        ResponseDefinitionBuilder builder = ResponseDefinitionBuilder.responseDefinition()
                .withStatus(result.status())
                .withBody(result.body());

        result.headers().forEach(builder::withHeader);

        return builder.build();
    }
}
