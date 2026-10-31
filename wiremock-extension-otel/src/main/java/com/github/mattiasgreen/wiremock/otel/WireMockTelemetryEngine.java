package com.github.mattiasgreen.wiremock.otel;

import com.github.mattiasgreen.wiremock.netem.engine.TcpNetEmProxy;
import com.github.mattiasgreen.wiremock.netem.engine.TcpNetEmRegistry;
import com.github.mattiasgreen.wiremock.otel.tracing.WireMockRequestTextMapGetter;
import com.github.tomakehurst.wiremock.core.Admin;
import com.github.tomakehurst.wiremock.extension.WireMockServices;
import com.github.tomakehurst.wiremock.http.Request;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.exporter.prometheus.PrometheusMetricReader;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.SdkMeterProviderBuilder;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SdkTracerProviderBuilder;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.prometheus.metrics.expositionformats.PrometheusTextFormatWriter;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.MDC;

/**
 * Deep, instance-scoped telemetry engine combining OpenTelemetry metrics, distributed tracing,
 * W3C context propagation, asynchronous runtime/NetEm gauges, and Prometheus exposition.
 */
public class WireMockTelemetryEngine implements Closeable, AutoCloseable {

    public static final String INSTRUMENTATION_NAME = "wiremock-otel";

    // Standard Semantic Metric Attribute Keys
    public static final AttributeKey<String> ATTR_PROTOCOL = AttributeKey.stringKey("protocol");
    public static final AttributeKey<String> ATTR_TRAFFIC_TYPE = AttributeKey.stringKey("traffic_type");
    public static final AttributeKey<String> ATTR_WIREMOCK_MATCHED = AttributeKey.stringKey("wiremock_matched");
    public static final AttributeKey<String> ATTR_WIREMOCK_STUB_NAME = AttributeKey.stringKey("wiremock_stub_name");
    public static final AttributeKey<String> ATTR_HTTP_METHOD = AttributeKey.stringKey("http_request_method");
    public static final AttributeKey<String> ATTR_HTTP_STATUS = AttributeKey.stringKey("http_response_status_code");
    public static final AttributeKey<String> ATTR_TARGET_UPSTREAM = AttributeKey.stringKey("target_upstream");
    public static final AttributeKey<String> ATTR_RPC_SERVICE = AttributeKey.stringKey("rpc_service");
    public static final AttributeKey<String> ATTR_CASUAL_ERROR_STATE = AttributeKey.stringKey("casual_error_state");

    // Standard Semantic Trace Attribute Keys
    private static final AttributeKey<String> TRACE_HTTP_METHOD = AttributeKey.stringKey("http.request.method");
    private static final AttributeKey<Long> TRACE_HTTP_STATUS = AttributeKey.longKey("http.response.status_code");
    private static final AttributeKey<String> TRACE_URL_PATH = AttributeKey.stringKey("url.path");
    private static final AttributeKey<String> TRACE_URL_QUERY = AttributeKey.stringKey("url.query");
    private static final AttributeKey<String> TRACE_URL_FULL = AttributeKey.stringKey("url.full");
    private static final AttributeKey<String> TRACE_CLIENT_ADDRESS = AttributeKey.stringKey("client.address");
    private static final AttributeKey<Boolean> TRACE_WIREMOCK_MATCHED = AttributeKey.booleanKey("wiremock.matched");
    private static final AttributeKey<String> TRACE_WIREMOCK_STUB_ID = AttributeKey.stringKey("wiremock.stub_id");
    private static final AttributeKey<String> TRACE_WIREMOCK_STUB_NAME = AttributeKey.stringKey("wiremock.stub_name");
    private static final AttributeKey<String> TRACE_WIREMOCK_SCENARIO_NAME =
            AttributeKey.stringKey("wiremock.scenario_name");
    private static final AttributeKey<String> TRACE_WIREMOCK_SCENARIO_STATE =
            AttributeKey.stringKey("wiremock.scenario_state");
    private static final AttributeKey<String> TRACE_WIREMOCK_TRAFFIC_TYPE =
            AttributeKey.stringKey("wiremock.traffic_type");
    private static final AttributeKey<String> TRACE_WIREMOCK_PROTOCOL = AttributeKey.stringKey("wiremock.protocol");
    private static final AttributeKey<String> TRACE_WIREMOCK_TARGET_UPSTREAM =
            AttributeKey.stringKey("wiremock.target_upstream");
    private static final AttributeKey<String> TRACE_RPC_SYSTEM = AttributeKey.stringKey("rpc.system");
    private static final AttributeKey<String> TRACE_RPC_SERVICE = AttributeKey.stringKey("rpc.service");
    private static final AttributeKey<String> TRACE_RPC_METHOD = AttributeKey.stringKey("rpc.method");
    private static final AttributeKey<String> TRACE_CASUAL_ERROR_STATE = AttributeKey.stringKey("casual.error_state");
    private static final AttributeKey<String> TRACE_CASUAL_XID = AttributeKey.stringKey("casual.xid");
    private static final AttributeKey<String> TRACE_CASUAL_XID_GTRID = AttributeKey.stringKey("casual.xid.gtrid");
    private static final AttributeKey<String> TRACE_CASUAL_XID_BQUAL = AttributeKey.stringKey("casual.xid.bqual");
    private static final AttributeKey<Long> TRACE_CASUAL_XID_FORMAT_ID = AttributeKey.longKey("casual.xid.format_id");

    private static final WireMockTelemetryEngine GLOBAL_INSTANCE = new WireMockTelemetryEngine();

    private final OpenTelemetrySdk openTelemetry;
    private final SdkMeterProvider meterProvider;
    private final SdkTracerProvider tracerProvider;
    private final PrometheusMetricReader prometheusReader;
    private final Meter meter;
    private final Tracer tracer;
    private final boolean tracingEnabled;

    private final LongCounter requestsTotal;
    private final DoubleHistogram requestDuration;
    private final DoubleHistogram upstreamDuration;
    private final LongCounter unmatchedTotal;
    private final Supplier<Admin> adminSupplier;
    private volatile Admin admin;

    public static WireMockTelemetryEngine getInstance() {
        return GLOBAL_INSTANCE;
    }

    public static WireMockTelemetryEngine create() {
        return new WireMockTelemetryEngine();
    }

    public static WireMockTelemetryEngine create(WireMockServices services) {
        Supplier<Admin> adminSupplier = services != null ? services::getAdmin : null;
        return new WireMockTelemetryEngine(
                resolveTracingEnabled(), resolveOtlpEndpoint(), null, adminSupplier, defaultNetEmSupplier());
    }

    public static WireMockTelemetryEngine create(
            boolean tracingEnabled,
            String otlpEndpoint,
            SpanExporter customSpanExporter,
            Supplier<Admin> adminSupplier) {
        return new WireMockTelemetryEngine(
                tracingEnabled, otlpEndpoint, customSpanExporter, adminSupplier, defaultNetEmSupplier());
    }

    private WireMockTelemetryEngine() {
        this(resolveTracingEnabled(), resolveOtlpEndpoint(), null, null, defaultNetEmSupplier());
    }

    public WireMockTelemetryEngine(
            boolean tracingEnabled,
            String otlpEndpoint,
            SpanExporter customSpanExporter,
            Supplier<Admin> adminSupplier,
            Supplier<Collection<TcpNetEmProxy>> netEmSupplier) {

        this.tracingEnabled = tracingEnabled;
        this.adminSupplier = adminSupplier;

        Resource resource = Resource.getDefault()
                .merge(Resource.create(Attributes.builder()
                        .put("service.name", resolveServiceName())
                        .put("service.namespace", "wiremock")
                        .build()));

        // 1. Metrics setup: populateTargetInfo=true, filter out resource attributes from metric labels
        this.prometheusReader = new PrometheusMetricReader(false, name -> false);
        SdkMeterProviderBuilder meterProviderBuilder =
                SdkMeterProvider.builder().setResource(resource).registerMetricReader(prometheusReader);
        this.meterProvider = meterProviderBuilder.build();

        // 2. Tracing setup
        SdkTracerProviderBuilder tracerProviderBuilder =
                SdkTracerProvider.builder().setResource(resource);

        if (tracingEnabled) {
            tracerProviderBuilder.setSampler(Sampler.alwaysOn());
            if (customSpanExporter != null) {
                tracerProviderBuilder.addSpanProcessor(SimpleSpanProcessor.create(customSpanExporter));
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
                .setMeterProvider(meterProvider)
                .setTracerProvider(tracerProvider)
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .build();

        this.meter = openTelemetry.getMeter(INSTRUMENTATION_NAME);
        this.tracer = openTelemetry.getTracer(INSTRUMENTATION_NAME);

        this.requestsTotal = meter.counterBuilder("wiremock_requests_total")
                .setDescription("Total requests handled by WireMock")
                .build();

        this.requestDuration = meter.histogramBuilder("wiremock_request_duration_ms")
                .setDescription("WireMock request execution duration in milliseconds")
                .setUnit("ms")
                .build();

        this.upstreamDuration = meter.histogramBuilder("wiremock_upstream_duration_ms")
                .setDescription("Upstream target execution duration in milliseconds for proxied requests")
                .setUnit("ms")
                .build();

        this.unmatchedTotal = meter.counterBuilder("wiremock_requests_unmatched_total")
                .setDescription("Total requests that did not match any stub")
                .build();

        // 3. Register native observable gauges for runtime health
        registerRuntimeGauges(adminSupplier);

        // 4. Register native observable instruments for NetEm if available
        registerNetEmInstruments(netEmSupplier);
    }

    public void setAdmin(Admin admin) {
        if (admin != null) {
            this.admin = admin;
        }
    }

    private Admin getAdmin() {
        if (adminSupplier != null) {
            Admin a = adminSupplier.get();
            if (a != null) {
                return a;
            }
        }
        return admin;
    }

    private void registerRuntimeGauges(Supplier<Admin> adminSupplier) {
        meter.gaugeBuilder("wiremock_journal_size_requests")
                .setDescription("Current count of requests in WireMock journal")
                .ofLongs()
                .buildWithCallback(measurement -> {
                    try {
                        Admin currentAdmin = getAdmin();
                        if (currentAdmin != null && currentAdmin.getServeEvents() != null) {
                            measurement.record(currentAdmin
                                    .getServeEvents()
                                    .getServeEvents()
                                    .size());
                        }
                    } catch (RuntimeException ignored) {
                    }
                });

        meter.gaugeBuilder("wiremock_journal_capacity")
                .setDescription("Maximum configured capacity for WireMock request journal (-1 if unbounded)")
                .ofLongs()
                .buildWithCallback(measurement -> {
                    try {
                        Admin currentAdmin = getAdmin();
                        if (currentAdmin != null
                                && currentAdmin.getOptions() != null
                                && currentAdmin.getOptions().maxRequestJournalEntries() != null) {
                            measurement.record(currentAdmin
                                    .getOptions()
                                    .maxRequestJournalEntries()
                                    .orElse(-1));
                        }
                    } catch (RuntimeException ignored) {
                    }
                });

        meter.gaugeBuilder("wiremock_stubs_total")
                .setDescription("Total number of stub mappings in WireMock")
                .ofLongs()
                .buildWithCallback(measurement -> {
                    try {
                        Admin currentAdmin = getAdmin();
                        if (currentAdmin != null && currentAdmin.listAllStubMappings() != null) {
                            measurement.record(
                                    currentAdmin
                                            .listAllStubMappings()
                                            .getMappings()
                                            .size(),
                                    Attributes.of(AttributeKey.stringKey("status"), "active"));
                        }
                    } catch (RuntimeException ignored) {
                    }
                });
    }

    private void registerNetEmInstruments(Supplier<Collection<TcpNetEmProxy>> netEmSupplier) {
        if (netEmSupplier == null) {
            return;
        }

        meter.gaugeBuilder("wiremock_netem_connections_active")
                .setDescription("Active client connections on NetEm proxy")
                .ofLongs()
                .buildWithCallback(measurement -> {
                    try {
                        Collection<TcpNetEmProxy> proxies = netEmSupplier.get();
                        if (proxies != null && !proxies.isEmpty()) {
                            for (TcpNetEmProxy proxy : proxies) {
                                measurement.record(
                                        proxy.getStats().getActiveConnections(),
                                        Attributes.of(
                                                AttributeKey.stringKey("proxy"),
                                                proxy.getConfig().getName()));
                            }
                        }
                    } catch (LinkageError | RuntimeException ignored) {
                    }
                });

        meter.counterBuilder("wiremock_netem_transfers_total")
                .setDescription("Total transfers processed through NetEm proxy")
                .buildWithCallback(measurement -> {
                    try {
                        Collection<TcpNetEmProxy> proxies = netEmSupplier.get();
                        if (proxies != null && !proxies.isEmpty()) {
                            for (TcpNetEmProxy proxy : proxies) {
                                String name = proxy.getConfig().getName();
                                measurement.record(
                                        proxy.getStats().getNormalTransfers(),
                                        Attributes.builder()
                                                .put("proxy", name)
                                                .put("outcome", "normal")
                                                .put("toxic", "none")
                                                .build());
                                measurement.record(
                                        proxy.getStats().getImpairedTransfers(),
                                        Attributes.builder()
                                                .put("proxy", name)
                                                .put("outcome", "impaired")
                                                .put("toxic", "any")
                                                .build());
                                proxy.getStats().getToxicCounters().forEach((toxic, count) -> {
                                    measurement.record(
                                            count,
                                            Attributes.builder()
                                                    .put("proxy", name)
                                                    .put("outcome", "impaired")
                                                    .put("toxic", toxic)
                                                    .build());
                                });
                            }
                        }
                    } catch (LinkageError | RuntimeException ignored) {
                    }
                });

        meter.counterBuilder("wiremock_netem_bytes_transferred_total")
                .setDescription("Total bytes transferred through NetEm proxy")
                .buildWithCallback(measurement -> {
                    try {
                        Collection<TcpNetEmProxy> proxies = netEmSupplier.get();
                        if (proxies != null && !proxies.isEmpty()) {
                            for (TcpNetEmProxy proxy : proxies) {
                                String name = proxy.getConfig().getName();
                                measurement.record(
                                        proxy.getStats().getBytesUpstreamNormal(),
                                        Attributes.builder()
                                                .put("proxy", name)
                                                .put("direction", "upstream")
                                                .put("outcome", "normal")
                                                .build());
                                measurement.record(
                                        proxy.getStats().getBytesDownstreamNormal(),
                                        Attributes.builder()
                                                .put("proxy", name)
                                                .put("direction", "downstream")
                                                .put("outcome", "normal")
                                                .build());
                                measurement.record(
                                        proxy.getStats().getBytesUpstreamImpaired(),
                                        Attributes.builder()
                                                .put("proxy", name)
                                                .put("direction", "upstream")
                                                .put("outcome", "impaired")
                                                .build());
                                measurement.record(
                                        proxy.getStats().getBytesDownstreamImpaired(),
                                        Attributes.builder()
                                                .put("proxy", name)
                                                .put("direction", "downstream")
                                                .put("outcome", "impaired")
                                                .build());
                            }
                        }
                    } catch (LinkageError | RuntimeException ignored) {
                    }
                });
    }

    public void recordMetrics(TrafficClassification tc) {
        if (tc == null) {
            return;
        }

        String safeProtocol = tc.protocol() != null && !tc.protocol().isBlank() ? tc.protocol() : "http";
        String safeTrafficType = tc.trafficType() != null && !tc.trafficType().isBlank() ? tc.trafficType() : "stubbed";

        AttributesBuilder builder = Attributes.builder()
                .put(ATTR_PROTOCOL, safeProtocol)
                .put(ATTR_TRAFFIC_TYPE, safeTrafficType)
                .put(ATTR_WIREMOCK_MATCHED, String.valueOf(tc.matched()))
                .put(ATTR_WIREMOCK_STUB_NAME, tc.stubName() != null ? tc.stubName() : "none");

        if ("casual".equalsIgnoreCase(safeProtocol)) {
            if (tc.rpcService() != null && !tc.rpcService().isBlank()) {
                builder.put(ATTR_RPC_SERVICE, tc.rpcService());
            }
            if (tc.rpcStatus() != null && !tc.rpcStatus().isBlank()) {
                builder.put(ATTR_CASUAL_ERROR_STATE, tc.rpcStatus());
            }
        } else {
            builder.put(ATTR_HTTP_METHOD, tc.method() != null ? tc.method() : "UNKNOWN");
            builder.put(ATTR_HTTP_STATUS, String.valueOf(tc.statusCode()));
        }

        if ("proxied".equalsIgnoreCase(safeTrafficType)
                && tc.targetUpstream() != null
                && !tc.targetUpstream().isBlank()
                && !"none".equals(tc.targetUpstream())) {
            builder.put(ATTR_TARGET_UPSTREAM, tc.targetUpstream());
        }

        Attributes attributes = builder.build();

        requestsTotal.add(1, attributes);
        requestDuration.record(tc.durationMs(), attributes);

        if ("proxied".equalsIgnoreCase(safeTrafficType) && tc.upstreamDurationMs() > 0) {
            Attributes upstreamAttrs = Attributes.of(
                    ATTR_PROTOCOL,
                    safeProtocol,
                    ATTR_TARGET_UPSTREAM,
                    tc.targetUpstream() != null ? tc.targetUpstream() : "none",
                    ATTR_HTTP_STATUS,
                    String.valueOf(tc.statusCode()));
            upstreamDuration.record(tc.upstreamDurationMs(), upstreamAttrs);
        }

        if (!tc.matched()) {
            AttributesBuilder unmatchedAttrs = Attributes.builder().put(ATTR_PROTOCOL, safeProtocol);
            if ("casual".equalsIgnoreCase(safeProtocol) && tc.rpcService() != null) {
                unmatchedAttrs.put(ATTR_RPC_SERVICE, tc.rpcService());
            } else {
                unmatchedAttrs.put(ATTR_HTTP_METHOD, tc.method() != null ? tc.method() : "UNKNOWN");
            }
            unmatchedTotal.add(1, unmatchedAttrs.build());
        }
    }

    public Span recordSpan(TrafficClassification tc, ServeEvent serveEvent) {
        if (!tracingEnabled || tc == null || serveEvent == null) {
            return null;
        }

        Request request = serveEvent.getRequest();
        if (request == null) {
            return null;
        }

        Context extractedContext = openTelemetry
                .getPropagators()
                .getTextMapPropagator()
                .extract(Context.current(), request, WireMockRequestTextMapGetter.INSTANCE);

        String method = tc.method() != null ? tc.method() : "HTTP";
        String spanName;
        if ("casual".equals(tc.protocol())) {
            spanName = "casual." + (tc.rpcService() != null ? tc.rpcService() : "unknown");
        } else {
            spanName = method + " " + tc.urlPath();
        }

        Span span = tracer.spanBuilder(spanName)
                .setParent(extractedContext)
                .setSpanKind(SpanKind.SERVER)
                .setStartTimestamp(tc.startTimestamp())
                .startSpan();

        String traceId = span.getSpanContext().getTraceId();
        String spanId = span.getSpanContext().getSpanId();

        try {
            if (traceId != null && !traceId.isBlank()) {
                MDC.put("trace_id", traceId);
            }
            if (spanId != null && !spanId.isBlank()) {
                MDC.put("span_id", spanId);
            }

            span.setAttribute(TRACE_HTTP_METHOD, method);
            span.setAttribute(TRACE_URL_FULL, tc.urlFull());
            span.setAttribute(TRACE_URL_PATH, tc.urlPath());

            if (tc.urlQuery() != null && !tc.urlQuery().isEmpty()) {
                span.setAttribute(TRACE_URL_QUERY, tc.urlQuery());
            }

            if (tc.clientIp() != null) {
                span.setAttribute(TRACE_CLIENT_ADDRESS, tc.clientIp());
            }

            if (tc.statusCode() > 0) {
                span.setAttribute(TRACE_HTTP_STATUS, (long) tc.statusCode());
                if (tc.statusCode() >= 500) {
                    span.setStatus(StatusCode.ERROR, "HTTP " + tc.statusCode());
                }
            }

            span.setAttribute(TRACE_WIREMOCK_MATCHED, tc.matched());
            if (!tc.matched() && tc.statusCode() == 404) {
                span.setStatus(StatusCode.ERROR, "Request unmatched by any WireMock stub");
            }

            if (tc.stubId() != null) {
                span.setAttribute(TRACE_WIREMOCK_STUB_ID, tc.stubId());
            }
            if (tc.stubName() != null && !"none".equals(tc.stubName())) {
                span.setAttribute(TRACE_WIREMOCK_STUB_NAME, tc.stubName());
            }
            if (tc.scenarioName() != null) {
                span.setAttribute(TRACE_WIREMOCK_SCENARIO_NAME, tc.scenarioName());
            }
            if (tc.scenarioState() != null) {
                span.setAttribute(TRACE_WIREMOCK_SCENARIO_STATE, tc.scenarioState());
            }

            span.setAttribute(TRACE_WIREMOCK_TRAFFIC_TYPE, tc.trafficType());
            if (tc.targetUpstream() != null) {
                span.setAttribute(TRACE_WIREMOCK_TARGET_UPSTREAM, tc.targetUpstream());
            }

            span.setAttribute(TRACE_WIREMOCK_PROTOCOL, tc.protocol());

            if ("casual".equals(tc.protocol())) {
                span.setAttribute(TRACE_RPC_SYSTEM, "casual");
                if (tc.rpcService() != null) {
                    span.setAttribute(TRACE_RPC_SERVICE, tc.rpcService());
                }
                span.setAttribute(TRACE_RPC_METHOD, "tpcall");
                if (tc.rpcStatus() != null) {
                    span.setAttribute(TRACE_CASUAL_ERROR_STATE, tc.rpcStatus());
                    if (!"OK".equalsIgnoreCase(tc.rpcStatus())) {
                        span.setStatus(StatusCode.ERROR, "Casual error: " + tc.rpcStatus());
                    }
                }
                if (request.header("X-Casual-Xid").isPresent()) {
                    span.setAttribute(
                            TRACE_CASUAL_XID, request.header("X-Casual-Xid").firstValue());
                }
                if (request.header("X-Casual-Gtrid").isPresent()) {
                    span.setAttribute(
                            TRACE_CASUAL_XID_GTRID,
                            request.header("X-Casual-Gtrid").firstValue());
                }
                if (request.header("X-Casual-Bqual").isPresent()) {
                    span.setAttribute(
                            TRACE_CASUAL_XID_BQUAL,
                            request.header("X-Casual-Bqual").firstValue());
                }
                if (request.header("X-Casual-Format-Id").isPresent()) {
                    try {
                        span.setAttribute(
                                TRACE_CASUAL_XID_FORMAT_ID,
                                Long.parseLong(
                                        request.header("X-Casual-Format-Id").firstValue()));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        } finally {
            span.end(tc.endTimestamp());
            MDC.remove("trace_id");
            MDC.remove("span_id");
        }

        return span;
    }

    public String scrapePrometheusText() {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrometheusTextFormatWriter writer = new PrometheusTextFormatWriter(false);
        try {
            writer.write(baos, prometheusReader.collect());
            return baos.toString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to format Prometheus metrics", e);
        }
    }

    public OpenTelemetrySdk getOpenTelemetry() {
        return openTelemetry;
    }

    public PrometheusMetricReader getPrometheusReader() {
        return prometheusReader;
    }

    public Tracer getTracer() {
        return tracer;
    }

    public Meter getMeter() {
        return meter;
    }

    public boolean isTracingEnabled() {
        return tracingEnabled;
    }

    private static Supplier<Collection<TcpNetEmProxy>> defaultNetEmSupplier() {
        return () -> {
            try {
                return TcpNetEmRegistry.getInstance().list();
            } catch (LinkageError | RuntimeException ignored) {
                return null;
            }
        };
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

    @Override
    public void close() {
        if (tracerProvider != null) {
            tracerProvider.close();
        }
        if (meterProvider != null) {
            meterProvider.close();
        }
        openTelemetry.close();
    }
}
