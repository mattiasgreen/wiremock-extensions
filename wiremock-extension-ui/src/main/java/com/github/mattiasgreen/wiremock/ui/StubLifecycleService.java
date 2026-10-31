package com.github.mattiasgreen.wiremock.ui;

import com.github.tomakehurst.wiremock.core.Admin;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import java.util.*;

/**
 * Canonical domain service managing the state lifecycle of WireMock stubs across active
 * admin mappings and parked disabled stores, as well as serve event snapshot conversions.
 *
 * <p>Encapsulates the {@link Admin} communication seam, atomic status transitions, project mode switching,
 * and deduplicated recording conversions behind a clean, high-leverage interface.
 */
public class StubLifecycleService {

    public record TransitionResult(boolean found, String status, UUID id, String name) {}

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
            boolean all = targetProject == null
                    || targetProject.isBlank()
                    || "*".equals(targetProject)
                    || "_all".equalsIgnoreCase(targetProject);
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
    private final DisabledStubStore store;

    public StubLifecycleService() {
        this(null, new DisabledStubStore());
    }

    public StubLifecycleService(Admin admin) {
        this(admin, new DisabledStubStore());
    }

    public StubLifecycleService(DisabledStubStore store) {
        this(null, store);
    }

    public StubLifecycleService(Admin admin, DisabledStubStore store) {
        this.admin = admin;
        this.store = Objects.requireNonNull(store, "store must not be null");
    }

    /**
     * Returns a new service instance bound to the given {@link Admin} seam.
     */
    public StubLifecycleService withAdmin(Admin admin) {
        return new StubLifecycleService(admin, this.store);
    }

    public DisabledStubStore getStore() {
        return store;
    }

    public Collection<StubMapping> getAllDisabled() {
        return store.getAll();
    }

    public int disabledCount() {
        return store.size();
    }

    public void clearParkedStubs() {
        store.clear();
    }

    public TransitionResult disable(UUID id) {
        return disable(requireAdmin(), id);
    }

    public TransitionResult disable(Admin targetAdmin, UUID id) {
        Objects.requireNonNull(targetAdmin, "admin must not be null");
        DisabledStubStore.TransitionResult r = store.disable(targetAdmin, id);
        return new TransitionResult(r.found(), r.status(), r.id(), r.name());
    }

    public TransitionResult enable(UUID id) {
        return enable(requireAdmin(), id);
    }

    public TransitionResult enable(Admin targetAdmin, UUID id) {
        Objects.requireNonNull(targetAdmin, "admin must not be null");
        DisabledStubStore.TransitionResult r = store.enable(targetAdmin, id);
        return new TransitionResult(r.found(), r.status(), r.id(), r.name());
    }

    public TransitionResult toggle(UUID id) {
        return toggle(requireAdmin(), id);
    }

    public TransitionResult toggle(Admin targetAdmin, UUID id) {
        Objects.requireNonNull(targetAdmin, "admin must not be null");
        DisabledStubStore.TransitionResult r = store.toggle(targetAdmin, id);
        return new TransitionResult(r.found(), r.status(), r.id(), r.name());
    }

    public StubMapping upsert(UUID id, StubMapping updatedStub) {
        return upsert(requireAdmin(), id, updatedStub);
    }

    public StubMapping upsert(Admin targetAdmin, UUID id, StubMapping updatedStub) {
        Objects.requireNonNull(targetAdmin, "admin must not be null");
        return store.upsert(targetAdmin, id, updatedStub);
    }

    public void delete(UUID id) {
        delete(requireAdmin(), id);
    }

    public void delete(Admin targetAdmin, UUID id) {
        Objects.requireNonNull(targetAdmin, "admin must not be null");
        store.delete(targetAdmin, id);
    }

    public int bulkApply(String action, List<UUID> ids) {
        return bulkApply(requireAdmin(), action, ids);
    }

    public int bulkApply(Admin targetAdmin, String action, List<UUID> ids) {
        Objects.requireNonNull(targetAdmin, "admin must not be null");
        return store.bulkApply(targetAdmin, action, ids);
    }

    public ProjectModeResult switchProjectMode(String targetProject, String requestedMode) {
        return switchProjectMode(requireAdmin(), targetProject, requestedMode);
    }

    public ProjectModeResult switchProjectMode(Admin targetAdmin, String targetProject, String requestedMode) {
        Objects.requireNonNull(targetAdmin, "admin must not be null");
        DisabledStubStore.ProjectModeResult r = store.switchProjectMode(targetAdmin, targetProject, requestedMode);
        return new ProjectModeResult(r.project(), r.activeMode(), r.stubsEnabled(), r.stubsDisabled());
    }

    public List<StubMapping> snapshotRecordings(SnapshotCommand cmd) {
        return snapshotRecordings(requireAdmin(), cmd);
    }

    public List<StubMapping> snapshotRecordings(Admin targetAdmin, SnapshotCommand cmd) {
        Objects.requireNonNull(targetAdmin, "admin must not be null");
        Objects.requireNonNull(cmd, "SnapshotCommand must not be null");
        List<ServeEvent> serveEvents = targetAdmin.getServeEvents().getServeEvents();
        return store.snapshotServeEvents(
                targetAdmin,
                serveEvents,
                cmd.requestIds(),
                cmd.targetProject(),
                cmd.allProjects(),
                cmd.filterByProject(),
                cmd.onlyProxied(),
                cmd.activate(),
                cmd.options() != null ? cmd.options() : RecordedStubConverter.SnapshotOptions.defaults());
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

        String project = (targetProject != null
                        && !targetProject.isBlank()
                        && !"*".equals(targetProject)
                        && !"_all".equalsIgnoreCase(targetProject))
                ? targetProject
                : "Recorded Traffic";
        RecordedStubConverter.SnapshotOptions opts =
                options != null ? options : RecordedStubConverter.SnapshotOptions.defaults();

        StubMapping stub = RecordedStubConverter.convert(foundEvent, project, opts);
        return Optional.ofNullable(stub);
    }

    private Admin requireAdmin() {
        if (this.admin == null) {
            throw new IllegalStateException(
                    "StubLifecycleService was not initialized with an Admin instance. Use withAdmin(admin).");
        }
        return this.admin;
    }
}
