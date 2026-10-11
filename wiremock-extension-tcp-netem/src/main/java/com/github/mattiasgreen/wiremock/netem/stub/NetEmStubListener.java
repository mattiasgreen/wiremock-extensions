package com.github.mattiasgreen.wiremock.netem.stub;

import com.github.mattiasgreen.wiremock.netem.engine.TcpNetEmProxy;
import com.github.mattiasgreen.wiremock.netem.engine.TcpNetEmRegistry;
import com.github.mattiasgreen.wiremock.netem.model.NetEmPreset;
import com.github.mattiasgreen.wiremock.netem.model.NetEmStreamDirection;
import com.github.mattiasgreen.wiremock.netem.model.NetEmToxicConfig;
import com.github.mattiasgreen.wiremock.netem.model.NetEmToxicType;
import com.github.tomakehurst.wiremock.common.Metadata;
import com.github.tomakehurst.wiremock.extension.Parameters;
import com.github.tomakehurst.wiremock.extension.ServeEventListener;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * WireMock {@link ServeEventListener} that dynamically applies NetEm impairments
 * declared in stub mapping metadata ({@code metadata.netem}).
 *
 * <p>Supports:
 * <ul>
 *   <li>{@code metadata.netem.preset}: String name of a preset (e.g. {@code "MOBILE_3G"}, {@code "FLAKY_WIFI"})</li>
 *   <li>{@code metadata.netem.toxics}: List of custom toxic definitions</li>
 * </ul>
 * Impairments are scoped to the incoming request's {@code X-Correlation-Id} (if present),
 * ensuring zero blast radius to concurrent test sessions, and automatically cleaned up in {@link #afterComplete}.
 */
public class NetEmStubListener implements ServeEventListener {

    public static final String EXTENSION_NAME = "netem-stub-listener";
    private static final String CORRELATION_HEADER = "X-Correlation-Id";

    // Maps serveEvent ID to list of added toxic names for auto-cleanup
    private final ConcurrentMap<String, List<String>> activeStubToxics = new ConcurrentHashMap<>();

    @Override
    public String getName() {
        return EXTENSION_NAME;
    }

    @Override
    public boolean applyGlobally() {
        return true;
    }

    @Override
    public void beforeResponseSent(ServeEvent serveEvent, Parameters parameters) {
        if (serveEvent == null) {
            return;
        }

        StubMapping stubMapping = serveEvent.getStubMapping();
        if (stubMapping == null || stubMapping.getMetadata() == null) {
            return;
        }

        Metadata metadata = stubMapping.getMetadata();
        if (!metadata.containsKey("netem")) {
            return;
        }

        Object netemObj = metadata.get("netem");
        if (!(netemObj instanceof Map<?, ?> netemMap)) {
            return;
        }

        String correlationId = null;
        if (serveEvent.getRequest() != null && serveEvent.getRequest().containsHeader(CORRELATION_HEADER)) {
            correlationId = serveEvent.getRequest().getHeader(CORRELATION_HEADER);
        }

        List<NetEmToxicConfig> toxicsToApply = new ArrayList<>();

        // 1. Check for preset
        Object presetObj = netemMap.get("preset");
        if (presetObj instanceof String presetName) {
            List<NetEmToxicConfig> presetToxics = NetEmPreset.getPresetToxics(presetName);
            for (NetEmToxicConfig p : presetToxics) {
                String dynamicName = "stub-" + serveEvent.getId() + "-" + p.getName();
                toxicsToApply.add(new NetEmToxicConfig(
                        dynamicName,
                        p.getType(),
                        p.getStream(),
                        p.getToxicity(),
                        correlationId != null ? correlationId : p.getCorrelationId(),
                        p.getAttributes()));
            }
        }

        // 2. Check for explicit toxics array
        Object toxicsObj = netemMap.get("toxics");
        if (toxicsObj instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> tMap) {
                    toxicsToApply.add(parseToxicConfig(serveEvent.getId(), tMap, correlationId));
                }
            }
        }

        if (toxicsToApply.isEmpty()) {
            return;
        }

        List<String> addedNames = new ArrayList<>();
        for (TcpNetEmProxy proxy : TcpNetEmRegistry.getInstance().list()) {
            for (NetEmToxicConfig toxic : toxicsToApply) {
                proxy.addToxic(toxic);
                addedNames.add(toxic.getName());
            }
        }

        activeStubToxics.put(serveEvent.getId().toString(), addedNames);
    }

    @Override
    public void afterComplete(ServeEvent serveEvent, Parameters parameters) {
        if (serveEvent == null) {
            return;
        }

        List<String> addedNames = activeStubToxics.remove(serveEvent.getId().toString());
        if (addedNames != null) {
            for (TcpNetEmProxy proxy : TcpNetEmRegistry.getInstance().list()) {
                for (String name : addedNames) {
                    proxy.removeToxic(name);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private NetEmToxicConfig parseToxicConfig(Object eventId, Map<?, ?> map, String defaultCorrelationId) {
        String baseName = map.get("name") instanceof String s ? s : "toxic";
        String uniqueName = "stub-" + eventId + "-" + baseName;

        String typeStr = map.get("type") instanceof String s ? s : "latency";
        NetEmToxicType type;
        try {
            type = NetEmToxicType.fromString(typeStr);
        } catch (IllegalArgumentException e) {
            type = NetEmToxicType.LATENCY;
        }

        String streamStr = map.get("stream") instanceof String s ? s : "downstream";
        NetEmStreamDirection stream;
        try {
            stream = NetEmStreamDirection.fromString(streamStr);
        } catch (IllegalArgumentException e) {
            stream = NetEmStreamDirection.DOWNSTREAM;
        }

        double toxicity = 1.0;
        if (map.get("toxicity") instanceof Number n) {
            toxicity = n.doubleValue();
        }

        String corrId = defaultCorrelationId;
        if (map.get("correlationId") instanceof String c && !c.isBlank()) {
            corrId = c;
        }

        Map<String, Object> attributes = Map.of();
        if (map.get("attributes") instanceof Map<?, ?> attrMap) {
            attributes = (Map<String, Object>) attrMap;
        }

        return new NetEmToxicConfig(uniqueName, type, stream, toxicity, corrId, attributes);
    }
}
