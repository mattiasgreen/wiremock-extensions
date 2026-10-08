package com.github.mattiasgreen.wiremock.otel.tracing;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SdkTracerProviderBuilder;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import java.io.Closeable;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Registry and lifecycle manager for OpenTelemetry tracing in WireMock.
 * Supports default singleton usage or custom instance-scoped tracer providers for test isolation.
 */
public class OtelTracingRegistry implements Closeable {

    public static final String INSTRUMENTATION_NAME = "wiremock-otel";
    private static final OtelTracingRegistry INSTANCE = new OtelTracingRegistry();

    private final OpenTelemetry openTelemetry;
    private final SdkTracerProvider tracerProvider;
    private final Tracer tracer;
    private final boolean tracingEnabled;

    public static OtelTracingRegistry getInstance() {
        return INSTANCE;
    }

    public static OtelTracingRegistry create() {
        return new OtelTracingRegistry();
    }

    private OtelTracingRegistry() {
        this(resolveTracingEnabled(), resolveOtlpEndpoint(), null);
    }

    public static OtelTracingRegistry create(OpenTelemetry customOpenTelemetry) {
        return new OtelTracingRegistry(customOpenTelemetry);
    }

    public static OtelTracingRegistry create(boolean enabled, String otlpEndpoint, SpanExporter customExporter) {
        return new OtelTracingRegistry(enabled, otlpEndpoint, customExporter);
    }

    private OtelTracingRegistry(OpenTelemetry customOpenTelemetry) {
        this.openTelemetry = Objects.requireNonNull(customOpenTelemetry, "customOpenTelemetry must not be null");
        this.tracerProvider = null;
        this.tracer = customOpenTelemetry.getTracer(INSTRUMENTATION_NAME);
        this.tracingEnabled = true;
    }

    private OtelTracingRegistry(boolean enabled, String otlpEndpoint, SpanExporter customExporter) {
        this.tracingEnabled = enabled;

        SdkTracerProviderBuilder tracerProviderBuilder = SdkTracerProvider.builder()
                .setResource(Resource.getDefault()
                        .merge(Resource.create(Attributes.builder()
                                .put("service.name", resolveServiceName())
                                .build())));

        if (enabled) {
            tracerProviderBuilder.setSampler(Sampler.alwaysOn());
            if (customExporter != null) {
                tracerProviderBuilder.addSpanProcessor(SimpleSpanProcessor.create(customExporter));
            } else if (otlpEndpoint != null && !otlpEndpoint.isBlank()) {
                SpanExporter otlpExporter = OtlpGrpcSpanExporter.builder()
                        .setEndpoint(otlpEndpoint)
                        .setTimeout(3, TimeUnit.SECONDS)
                        .build();
                tracerProviderBuilder.addSpanProcessor(
                        BatchSpanProcessor.builder(otlpExporter).build());
            }
        } else {
            tracerProviderBuilder.setSampler(Sampler.alwaysOff());
        }

        this.tracerProvider = tracerProviderBuilder.build();
        this.openTelemetry = OpenTelemetrySdk.builder()
                .setTracerProvider(this.tracerProvider)
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .build();
        this.tracer = this.openTelemetry.getTracer(INSTRUMENTATION_NAME);
    }

    public OtelTracingRegistry(SdkTracerProvider tracerProvider) {
        this.tracingEnabled = true;
        this.tracerProvider = Objects.requireNonNull(tracerProvider, "tracerProvider must not be null");
        this.openTelemetry = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .build();
        this.tracer = this.openTelemetry.getTracer(INSTRUMENTATION_NAME);
    }

    private static boolean resolveTracingEnabled() {
        String prop = System.getProperty("wiremock.otel.tracing.enabled");
        if (prop != null) {
            return Boolean.parseBoolean(prop);
        }
        String env = System.getenv("WIREMOCK_OTEL_TRACING_ENABLED");
        if (env != null) {
            return Boolean.parseBoolean(env);
        }
        return true;
    }

    private static String resolveOtlpEndpoint() {
        String prop = System.getProperty("wiremock.otel.exporter.otlp.endpoint");
        if (prop != null && !prop.isBlank()) {
            return prop;
        }
        String otelEndpoint = System.getenv("OTEL_EXPORTER_OTLP_ENDPOINT");
        if (otelEndpoint != null && !otelEndpoint.isBlank()) {
            return otelEndpoint;
        }
        return System.getenv("WIREMOCK_OTEL_EXPORTER_OTLP_ENDPOINT");
    }

    private static String resolveServiceName() {
        String prop = System.getProperty("wiremock.otel.service.name");
        if (prop != null && !prop.isBlank()) {
            return prop;
        }
        String otelName = System.getenv("OTEL_SERVICE_NAME");
        if (otelName != null && !otelName.isBlank()) {
            return otelName;
        }
        return "wiremock";
    }

    public OpenTelemetry getOpenTelemetry() {
        return openTelemetry;
    }

    public Tracer getTracer() {
        return tracer;
    }

    public boolean isTracingEnabled() {
        return tracingEnabled;
    }

    @Override
    public void close() {
        if (tracerProvider != null) {
            tracerProvider.close();
        }
    }
}
