package com.github.mattiasgreen.wiremock.async.trigger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.mattiasgreen.wiremock.async.model.AsyncMessage;
import com.github.mattiasgreen.wiremock.async.model.AsyncMetadata;
import com.github.mattiasgreen.wiremock.async.model.AsyncProduceDirective;
import com.github.tomakehurst.wiremock.extension.Parameters;
import com.github.tomakehurst.wiremock.extension.ServeEventListener;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * WireMock {@link ServeEventListener} that intercepts completed HTTP requests, inspects
 * {@code metadata.async.produce} directives, interpolates templates, and dispatches asynchronous
 * messages on virtual threads.
 *
 * <p><b>Note:</b> This is Beta functionality subject to evolution.
 */
public class AsyncMessagePublishingListener implements ServeEventListener {

    public static final String LISTENER_NAME = "async-message-publisher";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final BiConsumer<String, AsyncMessage> messagePublisher;

    public AsyncMessagePublishingListener() {
        this((dest, msg) -> {});
    }

    public AsyncMessagePublishingListener(BiConsumer<String, AsyncMessage> messagePublisher) {
        this.messagePublisher = Objects.requireNonNullElse(messagePublisher, (dest, msg) -> {});
    }

    @Override
    public String getName() {
        return LISTENER_NAME;
    }

    @Override
    public boolean applyGlobally() {
        return true;
    }

    @Override
    public void afterComplete(ServeEvent serveEvent, Parameters parameters) {
        if (serveEvent == null || serveEvent.getStubMapping() == null) {
            return;
        }

        Optional<AsyncMetadata> maybeMetadata =
                AsyncMetadata.from(serveEvent.getStubMapping().getMetadata());
        if (maybeMetadata.isEmpty()) {
            return;
        }

        AsyncMetadata metadata = maybeMetadata.get();
        if (metadata.feed() != null
                && "call-triggered".equalsIgnoreCase(metadata.feed().trigger())) {
            for (com.github.mattiasgreen.wiremock.async.model.AsyncFeedRecord rec :
                    metadata.feed().records()) {
                AsyncMessage message = AsyncMessage.builder()
                        .destination(metadata.feed().destination())
                        .protocol(metadata.feed().protocol())
                        .key(rec.key())
                        .payload(rec.payload())
                        .headers(rec.headers())
                        .build();
                messagePublisher.accept(metadata.feed().destination(), message);
            }
        }

        if (metadata.produce().isEmpty()) {
            return;
        }

        String requestBody =
                serveEvent.getRequest() != null ? serveEvent.getRequest().getBodyAsString() : "";
        String responseBody =
                serveEvent.getResponse() != null ? serveEvent.getResponse().getBodyAsString() : "";

        for (AsyncProduceDirective directive : metadata.produce()) {
            Thread.ofVirtual().name("async-produce-", 0).start(() -> {
                try {
                    if (directive.delayMs() > 0) {
                        Thread.sleep(directive.delayMs());
                    }

                    String evaluatedKey = interpolate(directive.key(), requestBody, responseBody);
                    String evaluatedPayload = interpolate(directive.payload(), requestBody, responseBody);

                    Map<String, String> evaluatedHeaders = new HashMap<>();
                    for (Map.Entry<String, String> h : directive.headers().entrySet()) {
                        evaluatedHeaders.put(h.getKey(), interpolate(h.getValue(), requestBody, responseBody));
                    }

                    AsyncMessage message = AsyncMessage.builder()
                            .id(UUID.randomUUID().toString())
                            .destination(directive.destination())
                            .protocol(directive.protocol())
                            .key(evaluatedKey)
                            .payload(evaluatedPayload)
                            .headers(evaluatedHeaders)
                            .timestamp(Instant.now())
                            .build();

                    messagePublisher.accept(directive.destination(), message);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (RuntimeException ignored) {
                }
            });
        }
    }

    private String interpolate(String template, String requestBody, String responseBody) {
        if (template == null || template.isBlank()) {
            return "";
        }
        String result = template;
        try {
            if (responseBody != null && !responseBody.isBlank()) {
                JsonNode resNode = MAPPER.readTree(responseBody);
                result = replacePlaceholders(result, "response.body", resNode);
            }
            if (requestBody != null && !requestBody.isBlank()) {
                JsonNode reqNode = MAPPER.readTree(requestBody);
                result = replacePlaceholders(result, "request.body", reqNode);
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) {
        }
        return result;
    }

    private String replacePlaceholders(String text, String prefix, JsonNode root) {
        String placeholderPrefix = "{{jsonPath " + prefix + " '$.";
        int idx = text.indexOf(placeholderPrefix);
        while (idx != -1) {
            int endQuote = text.indexOf("'", idx + placeholderPrefix.length());
            if (endQuote != -1) {
                int endBrace = text.indexOf("}}", endQuote);
                if (endBrace != -1) {
                    String fieldName = text.substring(idx + placeholderPrefix.length(), endQuote);
                    JsonNode val = root.get(fieldName);
                    String replacement = val != null ? (val.isValueNode() ? val.asText() : val.toString()) : "";
                    text = text.substring(0, idx) + replacement + text.substring(endBrace + 2);
                    idx = text.indexOf(placeholderPrefix);
                    continue;
                }
            }
            break;
        }
        return text;
    }
}
