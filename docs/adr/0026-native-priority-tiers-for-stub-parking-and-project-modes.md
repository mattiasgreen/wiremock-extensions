# Native Priority Tiers for Stub Parking and Project Modes

## Context
Previously, disabling or parking a stub removed the mapping from WireMock's active `Admin` store and held it inside an in-memory `DisabledStubStore`, exposed via seven custom shadow CRUD endpoints (`/__admin/stubs/{id}/disable`, `/__admin/stubs/{id}/enable`, `/__admin/stubs/{id}/toggle`, `/__admin/stubs/disabled`, `/__admin/stubs/disabled/{id}`, and `/__admin/stubs/bulk`). This created split-brain state: restarting WireMock discarded parked stubs, external JSON exports omitted disabled mappings unless merged through UI-only logic, vanilla WireMock instances appeared to lack basic stub toggle controls, and CLI/Casual adapters had to duplicate queries across active and shadow stores.

## Decision

1. **Four Canonical WireMock Priority Tiers (`priority` integer ordering)**:
   Represent stub active/parked lifecycle state directly on standard WireMock `StubMapping` resources without any out-of-band shadow store:
   - **`5` (`PRIORITY_ACTIVE_STUB`)**: Default priority for active static/recorded stubs. Wins over catch-all proxy rules and offline guards.
   - **`10` (`PRIORITY_ACTIVE_PROXY`)**: Active project catch-all proxy rules (`metadata.mode = "proxy"` or `response.proxyBaseUrl` set).
   - **`50` (`PRIORITY_OFFLINE_GUARD`)**: Optional catch-all offline guard (`ANY .* -> HTTP 501 Not Implemented`, `metadata.role = "offline-guard"`), ensuring parked stubs cannot match accidentally when no proxy rule covers a route. Never flipped during `switchProjectMode`.
   - **`>= 100` (`100` / `PRIORITY_PARKED`)**: Parked/disabled stubs. Remain persisted and exportable in WireMock's native `/__admin/mappings` store, lose to active stubs (`5`), active proxy rules (`10`), and offline guards (`50`), and retain their original priority in `metadata.previousPriority` for lossless restoration.

2. **Eliminate `DisabledStubStore` and Shadow Stub CRUD Endpoints**:
   - Remove `DisabledStubStore` and all `/__admin/stubs/*` shadow CRUD routes.
   - All single-stub toggles, edits, deletions, and bulk operations dispatch directly to standard `/__admin/mappings/{id}` (`PUT` / `DELETE`), enabling full toggle and filter support even against vanilla WireMock targets.
   - `StubLifecycleAdminEndpoint` exposes `GET /__admin/lifecycle/status` (`{"status":"UP"}`) solely to gate server-side recording snapshotting (`/recordings/snapshot`, `/stubs/snapshot-filtered`, `/stubs/snapshot-template`) and stateless `POST /__admin/projects/{project}/mode` flipping via `StubLifecycleService`.

3. **Casual & CLI Alignment**:
   - `WireMockStoreAdapter.getActiveStubMappings()` excludes `priority >= 100` natively from Casual TCP gateway routing without querying any HTTP UI extension endpoints.
   - `wiremock-cli` classifies `priority >= 100` mappings (alongside legacy `_disabled` metadata) locally without probing `/__admin/stubs/disabled`.

## Status
**Accepted**

## Consequences
- **Positive**: Single source of truth in WireMock's native mapping store; zero restart state loss; full JSON export/import portability; native UI toggle/bulk support on vanilla WireMock; removal of ~600 lines of shadow-store synchronization glue.
- **Trade-offs**: In the absence of a base proxy rule (`priority 10`) or `offline-guard` catch-all (`priority 50`), WireMock will still match a `priority >= 100` stub if no higher-priority stub matches the URL. Operators requiring hard isolation add an `offline-guard` stub (provided as a UI editor template) or explicit impossible request matcher.
