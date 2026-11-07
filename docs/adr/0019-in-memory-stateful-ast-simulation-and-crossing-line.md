# 19. In-Memory Stateful AST Simulation and Parallel Session Isolation

Date: 2026-11-07

## Status

Accepted

## Context

Static HTTP mocks return pre-canned responses for predefined requests. Real-world business workflows—such as case management, order checkouts, multi-step approvals, and inventory reservation—require dynamic state machines where earlier requests (e.g. `POST /orders`, `PUT /cases/123/approve`) mutate backend state and alter subsequent responses (e.g. `GET /orders/123`, `GET /cases/123`).

WireMock provides basic native Scenarios (`whenScenarioStateIs`, `willSetStateTo`). However, native scenarios suffer from severe limitations:
1. **Global Unpartitioned State**: Scenarios have a single global state across all requests. In concurrent test environments (CI running parallel suites), simultaneous tests collide and corrupt each other's scenario state.
2. **Coarse Transitions Only**: Scenarios cannot evaluate condition expressions on request payloads (e.g. `order.total > 1000`), update numeric fields, or maintain entity state dictionaries.
3. **Complex Setup**: Modeling realistic state machines requires dozens of boilerplate stub mappings chained together.

## Decision

We introduce `wiremock-extension-stateful`, a dynamic in-memory state engine powered by declarative Abstract Syntax Tree (AST) evaluation and parallel session isolation:

### 1. Declarative AST DSL (`com.github.mattiasgreen.wiremock.stateful.dsl`)
- Models dynamic behaviors through immutable AST structures:
  - `Entity`: In-memory resource dictionary (e.g., `Order`, `Case`, `Account`).
  - `Condition`: Predicate expressions (`equals`, `greaterThan`, `matches`, `inState`).
  - `Action`: State mutations (`set`, `increment`, `transitionState`, `appendToList`).
  - `DynamicStubDefinition`: Declarative rules combining request patterns, condition evaluations, state mutations, and dynamic templated responses.

### 2. Multi-Tenant Parallel Session Isolation (`StateEngine`)
- Scopes state machine storage dynamically using correlation headers:
  - `x-correlation-id`
  - `x-tenant-id`
  - `x-session-id`
- Parallel test runners operate against identical entity IDs (e.g., `case-101`) concurrently without state cross-contamination.

### 3. Integrated Admin API & UI State Visualizer
- Exposes REST endpoints under `/__admin/stateful/stubs` and `/__admin/stateful/state` via `StatefulAdminEndpoint`.
- Integrated directly into `wiremock-extension-ui` (`dynamic-stubs.js`), providing live entity inspection, state reset, and AST rule authoring with Dual-Layer syntax highlighting.

### 4. Seamless WireMock Response Transformer (`ResponseTransformerV2`)
- Hooks into WireMock response transformation pipeline.
- Resolves entity state into response templates on-the-fly (`{{state.entity.balance}}`), producing realistic dynamic responses without restart.

## Consequences

### Positive
- **True Stateful Simulation**: Models realistic multi-step state machines with data mutations, conditional branching, and invariant checks.
- **Parallel Test Safety**: Zero collision across parallel CI runs thanks to correlation-keyed session stores.
- **Visual Observability**: Developers inspect current state machines and entity instances in real-time in the Web UI.

### Negative / Trade-offs
- In-memory state is transient and resets on process shutdown (by design for mock isolation).
- Deep dynamic AST rules require adherence to DSL schemas; malformed ASTs are rejected during validation.
