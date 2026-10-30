package com.github.mattiasgreen.wiremock.ui;

import com.github.tomakehurst.wiremock.admin.model.SingleStubMappingResult;
import com.github.tomakehurst.wiremock.core.Admin;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Thread-safe domain store and lifecycle coordinator for WireMock stub mappings.
 * Encapsulates parking/unparking disabled stubs, atomic state transitions, project mode switching,
 * and deduplicated serve-event snapshot conversion.
 */
public class DisabledStubStore {

    public record TransitionResult(boolean found, String status, UUID id, String name) {}

    public record ProjectModeResult(String project, String activeMode, int stubsEnabled, int stubsDisabled) {}

    private final ConcurrentMap<UUID, StubMapping> store = new ConcurrentHashMap<>();

    public void put(StubMapping stub) {
        Objects.requireNonNull(stub, "stub must not be null");
        UUID id = stub.getId();
        if (id == null && stub.getUuid() != null) {
            id = stub.getUuid();
        }
        if (id != null) {
            store.put(id, stub);
        }
    }

    public StubMapping get(UUID id) {
        if (id == null) return null;
        return store.get(id);
    }

    public StubMapping remove(UUID id) {
        if (id == null) return null;
        return store.remove(id);
    }

    public Collection<StubMapping> getAll() {
        return List.copyOf(store.values());
    }

    public int size() {
        return store.size();
    }

    public void clear() {
        store.clear();
    }

    public TransitionResult disable(Admin admin, UUID id) {
        StubMapping existingDisabled = get(id);
        if (existingDisabled != null) {
            return new TransitionResult(
                    true, "disabled", id, existingDisabled.getName() != null ? existingDisabled.getName() : "");
        }

        SingleStubMappingResult result = admin.getStubMapping(id);
        if (result == null || !result.isPresent()) {
            return new TransitionResult(false, null, id, null);
        }

        StubMapping stub = result.getItem();
        put(stub);
        admin.removeStubMapping(id);
        return new TransitionResult(true, "disabled", id, stub.getName() != null ? stub.getName() : "");
    }

    public TransitionResult enable(Admin admin, UUID id) {
        StubMapping stub = remove(id);
        if (stub != null) {
            admin.addStubMapping(stub);
            return new TransitionResult(true, "enabled", id, stub.getName() != null ? stub.getName() : "");
        }

        SingleStubMappingResult result = admin.getStubMapping(id);
        if (result != null && result.isPresent()) {
            StubMapping activeStub = result.getItem();
            return new TransitionResult(true, "enabled", id, activeStub.getName() != null ? activeStub.getName() : "");
        }

        return new TransitionResult(false, null, id, null);
    }

    public TransitionResult toggle(Admin admin, UUID id) {
        StubMapping disabledStub = remove(id);
        if (disabledStub != null) {
            admin.addStubMapping(disabledStub);
            return new TransitionResult(
                    true, "enabled", id, disabledStub.getName() != null ? disabledStub.getName() : "");
        }

        SingleStubMappingResult activeResult = admin.getStubMapping(id);
        if (activeResult != null && activeResult.isPresent()) {
            StubMapping stub = activeResult.getItem();
            put(stub);
            admin.removeStubMapping(id);
            return new TransitionResult(true, "disabled", id, stub.getName() != null ? stub.getName() : "");
        }

        return new TransitionResult(false, null, id, null);
    }

    public StubMapping upsert(Admin admin, UUID id, StubMapping updatedStub) {
        updatedStub.setId(id);
        if (get(id) != null) {
            put(updatedStub);
            return updatedStub;
        }

        SingleStubMappingResult result = admin.getStubMapping(id);
        if (result != null && result.isPresent()) {
            admin.editStubMapping(updatedStub);
            return updatedStub;
        }

        admin.addStubMapping(updatedStub);
        return updatedStub;
    }

    public void delete(Admin admin, UUID id) {
        remove(id);
        admin.removeStubMapping(id);
    }

    public int bulkApply(Admin admin, String action, List<UUID> ids) {
        int count = 0;
        for (UUID id : ids) {
            switch (action.toLowerCase(Locale.ROOT)) {
                case "disable" -> {
                    SingleStubMappingResult res = admin.getStubMapping(id);
                    if (res != null && res.isPresent()) {
                        put(res.getItem());
                        admin.removeStubMapping(id);
                        count++;
                    }
                }
                case "enable" -> {
                    StubMapping stub = remove(id);
                    if (stub != null) {
                        admin.addStubMapping(stub);
                        count++;
                    }
                }
                case "delete" -> {
                    admin.removeStubMapping(id);
                    remove(id);
                    count++;
                }
                default -> throw new IllegalArgumentException("Unsupported action: " + action);
            }
        }
        return count;
    }

    public ProjectModeResult switchProjectMode(Admin admin, String targetProject, String requestedMode) {
        boolean allProjects = "*".equals(targetProject) || "_all".equalsIgnoreCase(targetProject);
        int enabledCount = 0;
        int disabledCount = 0;

        if ("stubs".equals(requestedMode)) {
            for (StubMapping stub : List.copyOf(admin.listAllStubMappings().getMappings())) {
                if ((allProjects || targetProject.equalsIgnoreCase(getStubProject(stub))) && isProxyStub(stub)) {
                    put(stub);
                    admin.removeStubMapping(stub.getId());
                    disabledCount++;
                }
            }
            for (StubMapping stub : List.copyOf(getAll())) {
                if ((allProjects || targetProject.equalsIgnoreCase(getStubProject(stub))) && !isProxyStub(stub)) {
                    remove(stub.getId());
                    admin.addStubMapping(stub);
                    enabledCount++;
                }
            }
        } else if ("proxy".equals(requestedMode)) {
            for (StubMapping stub : List.copyOf(admin.listAllStubMappings().getMappings())) {
                if ((allProjects || targetProject.equalsIgnoreCase(getStubProject(stub))) && !isProxyStub(stub)) {
                    put(stub);
                    admin.removeStubMapping(stub.getId());
                    disabledCount++;
                }
            }
            for (StubMapping stub : List.copyOf(getAll())) {
                if ((allProjects || targetProject.equalsIgnoreCase(getStubProject(stub))) && isProxyStub(stub)) {
                    remove(stub.getId());
                    admin.addStubMapping(stub);
                    enabledCount++;
                }
            }
        } else {
            throw new IllegalArgumentException("Unsupported mode: " + requestedMode + ". Use 'stubs' or 'proxy'.");
        }

        return new ProjectModeResult(targetProject, requestedMode, enabledCount, disabledCount);
    }

    public List<StubMapping> snapshotServeEvents(
            Admin admin,
            List<ServeEvent> serveEvents,
            Set<String> targetIds,
            String targetProject,
            boolean allProjects,
            boolean filterByProject,
            boolean onlyProxied,
            boolean activate,
            RecordedStubConverter.SnapshotOptions options) {

        List<StubMapping> createdStubs = new ArrayList<>();
        Set<String> seenSignatures = new HashSet<>();

        for (ServeEvent event : serveEvents) {
            if (event.getResponse() == null || event.getResponse().getStatus() <= 0) {
                continue;
            }

            if (targetIds != null
                    && !targetIds.isEmpty()
                    && !targetIds.contains(event.getId().toString())) {
                continue;
            }

            StubMapping matchedStub = event.getStubMapping();
            if (filterByProject && !allProjects) {
                String stubProj = getStubProject(matchedStub);
                if (!targetProject.equalsIgnoreCase(stubProj)) {
                    continue;
                }
            }

            boolean wasProxied = (matchedStub != null && isProxyStub(matchedStub))
                    || (event.getResponseDefinition() != null
                            && event.getResponseDefinition().getProxyBaseUrl() != null);

            if (onlyProxied && !wasProxied) {
                continue;
            }

            String bodyHash = event.getRequest().getBody() != null
                    ? Integer.toHexString(Arrays.hashCode(event.getRequest().getBody()))
                    : "";
            String signature = event.getRequest().getMethod().getName() + ":"
                    + event.getRequest().getUrl() + ":"
                    + event.getResponse().getStatus() + ":"
                    + bodyHash;
            if (seenSignatures.contains(signature)) {
                continue;
            }
            seenSignatures.add(signature);

            StubMapping recordedStub =
                    RecordedStubConverter.convert(event, allProjects ? "Recorded Traffic" : targetProject, options);
            if (recordedStub != null) {
                if (activate) {
                    admin.addStubMapping(recordedStub);
                } else {
                    put(recordedStub);
                }
                createdStubs.add(recordedStub);
            }
        }

        return createdStubs;
    }

    public static boolean isProxyStub(StubMapping stub) {
        if (stub == null) return false;
        if (stub.getResponse() != null
                && stub.getResponse().getProxyBaseUrl() != null
                && !stub.getResponse().getProxyBaseUrl().isBlank()) {
            return true;
        }
        if (stub.getMetadata() != null
                && "proxy".equalsIgnoreCase(stub.getMetadata().getString("mode"))) {
            return true;
        }
        return false;
    }

    public static String getStubProject(StubMapping stub) {
        if (stub == null || stub.getMetadata() == null) return "Ungrouped";
        String project = stub.getMetadata().getString("project");
        return (project != null && !project.isBlank()) ? project : "Ungrouped";
    }
}
