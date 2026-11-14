package com.github.mattiasgreen.wiremock.ui;

import com.github.tomakehurst.wiremock.common.Json;
import com.github.tomakehurst.wiremock.common.Metadata;
import com.github.tomakehurst.wiremock.core.Admin;
import com.github.tomakehurst.wiremock.matching.RequestPattern;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import java.util.*;

/**
 * Canonical domain service managing native priority-tier stub lifecycle operations,
 * stateless project mode switching ({@code proxy} &lt;-&gt; {@code stubs}),
 * and deduplicated ServeEvent snapshot conversions.
 *
 * <p>Uses WireMock's native stub priority ordering directly:
 * <ul>
 *   <li>{@link #PRIORITY_ACTIVE_STUB} (5): active static/mock stubs</li>
 *   <li>{@link #PRIORITY_ACTIVE_PROXY} (10): active live service proxy catch-all rules</li>
 *   <li>{@link #PRIORITY_OFFLINE_GUARD} (50): optional {@code ANY .*} 501 offline guard ({@code metadata.role = "offline-guard"})</li>
 *   <li>{@link #PRIORITY_PARKED} (100+): parked (disabled) stubs that sit below active stubs/proxies</li>
 * </ul>
 */
public class StubLifecycleService {

    public static final int PRIORITY_ACTIVE_STUB = 5;
    public static final int PRIORITY_ACTIVE_PROXY = 10;
    public static final int PRIORITY_OFFLINE_GUARD = 50;
    public static final int PRIORITY_PARKED = 100;

    private static final String META_PREVIOUS_PRIORITY = "previousPriority";

    public record ProjectModeResult(String project, String activeMode, int stubsEnabled, int stubsDisabled) {}

    public record SnapshotCommand(
            Set<String> requestIds,
            String targetProject,
            boolean allProjects,
            boolean filterByProject,
            boolean onlyProxied,
            boolean activate,
            RecordedStubConverter.SnapshotOptions options) {

        public static SnapshotCommand forProject(String targetProject, RecordedStubConverter.SnapshotOptions options) {
            boolean all = isAllProjectsToken(targetProject);
            return new SnapshotCommand(
                    null,
                    targetProject != null ? targetProject : "",
                    all,
                    true,
                    true,
                    false,
                    options != null ? options : RecordedStubConverter.SnapshotOptions.defaults());
        }

        public static SnapshotCommand forRequests(
                Set<String> requestIds,
                String targetProject,
                boolean activate,
                boolean onlyProxied,
                RecordedStubConverter.SnapshotOptions options) {
            return new SnapshotCommand(
                    requestIds,
                    targetProject != null && !targetProject.isBlank() ? targetProject : "Recorded Traffic",
                    false,
                    false,
                    onlyProxied,
                    activate,
                    options != null ? options : RecordedStubConverter.SnapshotOptions.defaults());
        }
    }

    private final Admin admin;

    public StubLifecycleService() {
        this(null);
    }

    public StubLifecycleService(Admin admin) {
        this.admin = admin;
    }

    /**
     * Returns a new service instance bound to the given {@link Admin} seam.
     */
    public StubLifecycleService withAdmin(Admin admin) {
        return new StubLifecycleService(admin);
    }

    /**
     * Returns {@code true} if the stub is parked at priority {@value #PRIORITY_PARKED} or higher.
     */
    public static boolean isParked(StubMapping stub) {
        return stub != null && stub.getPriority() != null && stub.getPriority() >= PRIORITY_PARKED;
    }

    /**
     * Returns {@code true} if the stub declares {@code metadata.role = "offline-guard"}.
     */
    public static boolean isOfflineGuard(StubMapping stub) {
        if (stub == null || stub.getMetadata() == null) {
            return false;
        }
        Object roleObj = stub.getMetadata().get("role");
        return roleObj instanceof String role
                && "offline-guard".equals(role.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * Returns {@code true} if the stub forwards traffic to an upstream base URL
     * or declares {@code metadata.mode = "proxy"} (e.g. Casual 2PC proxy rules).
     */
    public static boolean isProxyStub(StubMapping stub) {
        if (stub == null) {
            return false;
        }
        if (stub.getResponse() != null
                && (stub.getResponse().isProxyResponse()
                        || (stub.getResponse().getProxyBaseUrl() != null
                                && !stub.getResponse().getProxyBaseUrl().isBlank()))) {
            return true;
        }
        return stub.getMetadata() != null
                && stub.getMetadata().get("mode") instanceof String mode
                && "proxy".equals(mode.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * Derives the canonical project name from {@code metadata.project} or the URL base path segment.
     */
    public static String getStubProject(StubMapping stub) {
        if (stub == null) {
            return "Ungrouped";
        }
        if (stub.getMetadata() != null && stub.getMetadata().get("project") instanceof String p && !p.isBlank()) {
            return p.trim();
        }
        String url = extractStubUrl(stub.getRequest());
        return deriveProjectFromUrl(url);
    }

    /**
     * Statelessly switches all non-guard stubs belonging to {@code targetProject} (or {@code _all}/{@code *})
     * between {@code "proxy"} mode (proxies at priority 10, static stubs parked at 100) and
     * {@code "stubs"} mode (static stubs active at priority 5 or restored custom priority, proxies parked at 100).
     */
    public ProjectModeResult switchProjectMode(String targetProject, String requestedMode) {
        return switchProjectMode(requireAdmin(), targetProject, requestedMode);
    }

    public ProjectModeResult switchProjectMode(Admin targetAdmin, String targetProject, String requestedMode) {
        Objects.requireNonNull(targetAdmin, "admin must not be null");
        String normalizedMode =
                requestedMode == null ? "" : requestedMode.trim().toLowerCase(Locale.ROOT);
        if (!"proxy".equals(normalizedMode) && !"stubs".equals(normalizedMode)) {
            throw new IllegalArgumentException(
                    "Invalid project mode '" + requestedMode + "'. Expected 'proxy' or 'stubs'.");
        }

        boolean allProjects = isAllProjectsToken(targetProject);
        boolean proxyMode = "proxy".equals(normalizedMode);
        List<StubMapping> mappings =
                new ArrayList<>(targetAdmin.listAllStubMappings().getMappings());

        int stubsEnabled = 0;
        int stubsDisabled = 0;

        for (StubMapping stub : mappings) {
            if (stub == null || isOfflineGuard(stub)) {
                continue;
            }
            if (!allProjects && !targetProject.equals(getStubProject(stub))) {
                continue;
            }

            boolean proxyStub = isProxyStub(stub);
            boolean currentlyParked = isParked(stub);
            boolean shouldBeActive = proxyMode ? proxyStub : !proxyStub;

            if (shouldBeActive && currentlyParked) {
                StubMapping updated = StubMapping.buildFrom(StubMapping.buildJsonStringFor(stub));
                unparkStub(updated, proxyStub);
                targetAdmin.editStubMapping(updated);
                stubsEnabled++;
            } else if (!shouldBeActive && !currentlyParked) {
                StubMapping updated = StubMapping.buildFrom(StubMapping.buildJsonStringFor(stub));
                parkStub(updated, proxyStub);
                targetAdmin.editStubMapping(updated);
                stubsDisabled++;
            }
        }

        return new ProjectModeResult(allProjects ? "_all" : targetProject, normalizedMode, stubsEnabled, stubsDisabled);
    }

    public List<StubMapping> snapshotRecordings(SnapshotCommand cmd) {
        return snapshotRecordings(requireAdmin(), cmd);
    }

    public List<StubMapping> snapshotRecordings(Admin targetAdmin, SnapshotCommand cmd) {
        Objects.requireNonNull(targetAdmin, "admin must not be null");
        Objects.requireNonNull(cmd, "SnapshotCommand must not be null");

        List<StubMapping> existingMappings = targetAdmin.listAllStubMappings().getMappings();
        Set<String> seenSignatures = new HashSet<>();
        for (StubMapping existing : existingMappings) {
            if (!isProxyStub(existing)) {
                seenSignatures.add(buildRequestSignature(existing));
            }
        }

        RecordedStubConverter.SnapshotOptions opts =
                cmd.options() != null ? cmd.options() : RecordedStubConverter.SnapshotOptions.defaults();
        List<ServeEvent> serveEvents = targetAdmin.getServeEvents().getServeEvents();
        List<StubMapping> created = new ArrayList<>();

        for (ServeEvent event : serveEvents) {
            if (event == null || !event.getWasMatched()) {
                continue;
            }
            if (cmd.requestIds() != null
                    && !cmd.requestIds().contains(event.getId().toString())) {
                continue;
            }
            if (cmd.onlyProxied() && !isProxiedEvent(event)) {
                continue;
            }

            String eventProject = deriveEventProject(event);
            if (cmd.filterByProject()
                    && !cmd.allProjects()
                    && !cmd.targetProject().equals(eventProject)) {
                continue;
            }

            String resolvedProject = (cmd.allProjects()
                            || cmd.targetProject() == null
                            || cmd.targetProject().isBlank())
                    ? eventProject
                    : cmd.targetProject();

            StubMapping converted = RecordedStubConverter.convert(event, resolvedProject, opts);
            if (converted == null) {
                continue;
            }

            converted.setPriority(cmd.activate() ? PRIORITY_ACTIVE_STUB : PRIORITY_PARKED);

            String sig = buildRequestSignature(converted);
            if (seenSignatures.add(sig)) {
                targetAdmin.addStubMapping(converted);
                created.add(converted);
            }
        }

        return created;
    }

    public Optional<StubMapping> generateSnapshotTemplate(
            String requestId, String targetProject, RecordedStubConverter.SnapshotOptions options) {
        return generateSnapshotTemplate(requireAdmin(), requestId, targetProject, options);
    }

    public Optional<StubMapping> generateSnapshotTemplate(
            Admin targetAdmin, String requestId, String targetProject, RecordedStubConverter.SnapshotOptions options) {
        Objects.requireNonNull(targetAdmin, "admin must not be null");
        if (requestId == null || requestId.isBlank()) {
            return Optional.empty();
        }

        ServeEvent foundEvent = null;
        for (ServeEvent event : targetAdmin.getServeEvents().getServeEvents()) {
            if (requestId.equals(event.getId().toString())) {
                foundEvent = event;
                break;
            }
        }

        if (foundEvent == null) {
            return Optional.empty();
        }

        String project = !isAllProjectsToken(targetProject) ? targetProject : "Recorded Traffic";
        RecordedStubConverter.SnapshotOptions opts =
                options != null ? options : RecordedStubConverter.SnapshotOptions.defaults();

        StubMapping stub = RecordedStubConverter.convert(foundEvent, project, opts);
        if (stub != null) {
            stub.setPriority(PRIORITY_PARKED);
        }
        return Optional.ofNullable(stub);
    }

    private Admin requireAdmin() {
        if (this.admin == null) {
            throw new IllegalStateException(
                    "StubLifecycleService was not initialized with an Admin instance. Use withAdmin(admin).");
        }
        return this.admin;
    }

    private static void parkStub(StubMapping stub, boolean proxyStub) {
        Integer currentPriority = stub.getPriority();
        int defaultActive = proxyStub ? PRIORITY_ACTIVE_PROXY : PRIORITY_ACTIVE_STUB;
        if (currentPriority != null && currentPriority < PRIORITY_PARKED && currentPriority != defaultActive) {
            Map<String, Object> nextMeta = copyMetadata(stub);
            nextMeta.put(META_PREVIOUS_PRIORITY, currentPriority);
            stub.setMetadata(new Metadata(nextMeta));
        }
        stub.setPriority(PRIORITY_PARKED);
    }

    private static void unparkStub(StubMapping stub, boolean proxyStub) {
        int restoredPriority = proxyStub ? PRIORITY_ACTIVE_PROXY : PRIORITY_ACTIVE_STUB;
        if (stub.getMetadata() != null && stub.getMetadata().containsKey(META_PREVIOUS_PRIORITY)) {
            Object prevObj = stub.getMetadata().get(META_PREVIOUS_PRIORITY);
            if (prevObj instanceof Number n && n.intValue() > 0 && n.intValue() < PRIORITY_PARKED) {
                restoredPriority = n.intValue();
            }
            Map<String, Object> nextMeta = copyMetadata(stub);
            nextMeta.remove(META_PREVIOUS_PRIORITY);
            stub.setMetadata(new Metadata(nextMeta));
        }
        stub.setPriority(restoredPriority);
    }

    private static Map<String, Object> copyMetadata(StubMapping stub) {
        return stub.getMetadata() != null ? new LinkedHashMap<>(stub.getMetadata()) : new LinkedHashMap<>();
    }

    private static boolean isAllProjectsToken(String targetProject) {
        return targetProject == null
                || targetProject.isBlank()
                || "*".equals(targetProject)
                || "_all".equalsIgnoreCase(targetProject);
    }

    private static boolean isProxiedEvent(ServeEvent event) {
        if (event == null) {
            return false;
        }
        if (event.getResponseDefinition() != null
                && (event.getResponseDefinition().isProxyResponse()
                        || (event.getResponseDefinition().getProxyBaseUrl() != null
                                && !event.getResponseDefinition()
                                        .getProxyBaseUrl()
                                        .isBlank()))) {
            return true;
        }
        return event.getStubMapping() != null && isProxyStub(event.getStubMapping());
    }

    private static String deriveEventProject(ServeEvent event) {
        if (event.getStubMapping() != null) {
            return getStubProject(event.getStubMapping());
        }
        if (event.getRequest() != null) {
            return deriveProjectFromUrl(event.getRequest().getUrl());
        }
        return "Ungrouped";
    }

    private static String deriveProjectFromUrl(String url) {
        if (url == null || url.isBlank() || "/".equals(url)) {
            return "api-Root";
        }
        String clean = url.replaceFirst("^https?://[^/]+", "");
        int qIdx = clean.indexOf('?');
        if (qIdx >= 0) {
            clean = clean.substring(0, qIdx);
        }
        String[] rawParts = clean.split("/");
        List<String> parts = new ArrayList<>();
        for (String p : rawParts) {
            if (!p.isBlank()) {
                parts.add(p);
            }
        }
        if (parts.isEmpty()) {
            return "api-Root";
        }
        if ("api".equalsIgnoreCase(parts.get(0)) && parts.size() > 1) {
            if (parts.get(1).matches("(?i)^v\\d+$") && parts.size() > 2) {
                return "api-" + parts.get(2).toLowerCase(Locale.ROOT);
            }
            return "api-" + parts.get(1).toLowerCase(Locale.ROOT);
        }
        return "api-" + parts.get(0).toLowerCase(Locale.ROOT);
    }

    private static String extractStubUrl(RequestPattern req) {
        if (req == null) {
            return "/";
        }
        if (req.getUrl() != null) return req.getUrl();
        if (req.getUrlPath() != null) return req.getUrlPath();
        if (req.getUrlPathTemplate() != null) return req.getUrlPathTemplate();
        if (req.getUrlPattern() != null) return req.getUrlPattern();
        if (req.getUrlPathPattern() != null) return req.getUrlPathPattern();
        return "/";
    }

    private static String buildRequestSignature(StubMapping stub) {
        if (stub == null || stub.getRequest() == null) {
            return "";
        }
        return Json.write(stub.getRequest());
    }
}
