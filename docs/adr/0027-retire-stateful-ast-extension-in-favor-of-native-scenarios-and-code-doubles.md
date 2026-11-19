# ADR 0027: Retire Stateful AST Extension in Favor of Native Scenarios and Code Doubles

Date: 2026-11-11

**Status**: Accepted
**Supersedes**: [ADR 0019](0019-in-memory-stateful-ast-simulation-and-crossing-line.md)

## Context

[ADR 0019](0019-in-memory-stateful-ast-simulation-and-crossing-line.md) introduced `wiremock-extension-stateful` to address complex stateful mock setups (such as banking/case-management FSM transitions, invariant enforcement, and parallel test session isolation) without exposing WireMock Standalone to remote code execution (RCE) from script evaluation engines (SpEL, MVEL, Groovy, OGNL).

While the closed-world Java 21 sealed AST design avoided arbitrary code execution and successfully demonstrated header-based (`X-Correlation-Id`) session isolation, ongoing usage, the UI/CLI architecture review, and integration patterns proven in `wiremock-examples` (`CaseLifecycleStubs`) exposed fundamental architectural friction:

1. **Greenspun's Tenth Rule**: `AstExpression`, `AstMutation`, `AstEvaluator`, `AstMutator`, and `SessionStateStore` re-implemented an ad-hoc in-memory relational/document rule engine inside WireMock. Supporting compound boolean logic, nested predicates, webhook actions, persistence, or cross-entity invariants pushes WireMock toward maintaining a miniature database engine.
2. **Opaque JSON AST Debugging Friction**: Encoding domain transitions in serialized `"type": "collection_all_match"` / `"type": "update_list_item"` AST payloads removes IDE autocomplete, compile-time entity checking, debugger breakpoints, and readable stack traces relative to plain Java `ResponseTransformerV2` extensions or provider-owned test doubles.
3. **Invariant Contract Drift**: Rewriting production business-rule guards inside test-only JSON AST definitions duplicates domain invariants outside the provider service. As the upstream service evolves, duplicated mock invariants silently diverge from real production validation rules unless governed by provider-owned contracts or Testcontainers.
4. **"The Crossing Line" Violation**: WireMock excels as an over-the-wire HTTP/protocol simulator, L4/L7 fault injector, request journal, and scenario state orchestrator. Complex stateful domain models and multi-invariant entity stores cross the architectural line into test-double / fake-service territory.
5. **UI and Conceptual Split-Brain**: Exposing a parallel catalog (`/__admin/stateful/*`), a fourth `STATEFUL` journal verdict alongside `PROXIED` / `STUB` / `UNMATCHED`, and bespoke UI templates (`modules/dynamic-stubs.js`) split stub management across two incompatible mental models while duplicating WireMock's native `/__admin/scenarios` capability.

## Preserved Architecture of `wiremock-extension-stateful`

To preserve the engineering work for future reference or potential re-evaluation, the complete `wiremock-extension-stateful` architecture retired by this decision is documented below:

### 1. Closed-World Java 21 Sealed AST (`com.github.mattiasgreen.wiremock.stateful.ast`)

Polymorphic Jackson deserialization using `@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")` and explicit `@JsonSubTypes` (disallowing arbitrary class loading or reflection):

- **`AstExpression`** (sealed interface):
  - `FieldRef(String path)` (`"field_ref"`)
  - `Literal(Object value)` (`"literal"`)
  - `BinaryOp(Operator operator, AstExpression left, AstExpression right)` (`"binary_op"`, with `Operator`: `EQUALS`, `NOT_EQUALS`, `GREATER_THAN`, `GREATER_THAN_OR_EQUAL`, `LESS_THAN`, `LESS_THAN_OR_EQUAL`)
  - `CollectionSize(String path)` (`"collection_size"`)
  - `CollectionAllMatch(String collectionPath, AstExpression predicate)` (`"collection_all_match"`)
  - `CollectionAnyMatch(String collectionPath, AstExpression predicate)` (`"collection_any_match"`)
- **`AstMutation`** (sealed interface):
  - `InitEntity(String idParam, Map<String, Object> defaultFields)` (`"init_entity"`)
  - `SetField(String path, AstValueSource value)` (`"set_field"`)
  - `AppendToList(String collectionPath, AstValueSource item)` (`"append_to_list"`)
  - `UpdateListItem(String collectionPath, String idField, String idParam, String targetField, AstValueSource value)` (`"update_list_item"`)
- **`AstValueSource`** (sealed interface):
  - `RequestBody()` (`"request_body"`)
  - `RequestBodyWithGeneratedId(String idField, String idPrefix)` (`"request_body_with_id"`)
  - `LiteralValue(Object value)` (`"literal_value"`)
  - `PathParam(String paramName)` (`"path_param"`)
- **`AstResponseSource`** (sealed interface):
  - `Entity()` (`"entity"`)
  - `LastAppendedItem(String collectionPath)` (`"last_appended_item"`)
  - `SubPath(String path)` (`"sub_path"`)
  - `UpdatedItem(String collectionPath, String idField, String idParam)` (`"updated_item"`)
  - `Empty()` (`"empty"`)
- **Model Definition Records & JSON Serialization**:
  - `AstModelDefinition(String entity, String idPathParam, String idPrefix, List<AstRuleDefinition> rules)`
  - `AstRuleDefinition(AstRoute route, List<AstInvariant> invariants, List<AstMutation> mutations, AstResponse response)`
  - `AstRoute(String method, String pathPattern)`
  - `AstInvariant(AstExpression condition, int failStatus, String failMessage)`
  - `AstResponse(int status, AstResponseSource source, Map<String, String> headers)`
  - `AstJson`: Shared Jackson `ObjectMapper` registered with `JavaTimeModule` and `@JsonSubTypes` bindings (`FAIL_ON_UNKNOWN_PROPERTIES` configured to `false`).

### 2. Fluent Java DSL & Remote Client (`...stateful.dsl`, `...stateful.client`)

- **`SimulatorModel`**: Fluent builder API (`forEntity`, `idFromPath`, `idPathParam`, `idPrefix`, `onPost`, `onGet`, `onPut`, `onDelete`, and `RuleBuilder` methods `require`, `mutate`, `initialState`, `respondWith`, `build`) backed by composable DSL factories `Expr`, `Action`, `Source`, and `ResponseSource`.
- **`RemoteStateClient`**: Typed HTTP client (`pushModel`, `clearSession`, `getEntity`) for uploading `SimulatorModel` definitions and managing correlation sessions on a standalone WireMock host.

### 3. Execution Engine & Correlation Isolation (`...stateful.engine`)

- **`AstEvaluator`**: Recursive predicate evaluator traversing Jackson `ObjectNode` / `ArrayNode` state trees without reflection or script execution.
- **`AstMutator`**: Deterministic JSON tree mutator with `AtomicLong` sequential ID generation for `RequestBodyWithGeneratedId`.
- **`RouteMatcher`**: Path template matcher converting `{param}` tokens into `([^/]+)` regex capture groups.
- **`SessionStateStore`**: Thread-safe `ConcurrentHashMap<String, Map<String, Map<String, ObjectNode>>>` partitioned strictly by `correlationId -> entityType -> entityId`.
- **`StateEngine`**: Evaluates incoming requests against compiled session/global `CompiledRule` entries in order: route match, `AstInvariant` validation (short-circuiting on failure), atomic `AstMutation` execution, and `AstResponseSource` synthesis.

### 4. WireMock SPI Wiring (`...stateful.extension`)

- **`StatefulExtensionFactory`**: SPI `ExtensionFactory` registering `StatefulAdminEndpoint` and `StatefulResponseTransformer` wired to a shared `StateEngine`.
- **`StatefulResponseTransformer`**: Global `ResponseDefinitionTransformerV2` (`applyGlobally() = true`), partitioning execution by `x-correlation-id` (default `"DEFAULT"`) and tagging responses with `x-wiremock-stateful`, `x-wiremock-stateful-entity`, `x-wiremock-stateful-route`, `x-wiremock-stateful-invariants`, and `x-correlation-id`.
- **`StatefulAdminEndpoint`**: `AdminApiExtension` providing:
  - `POST /__admin/stateful/rules`
  - `GET /__admin/stateful/models`
  - `DELETE /__admin/stateful/sessions/{correlationId}`
  - `DELETE /__admin/stateful/sessions`
  - `GET /__admin/stateful/entities/{correlationId}/{entityType}/{entityId}`

## Decision

We **retire `wiremock-extension-stateful`** (removing its module, AST engine, `/__admin/stateful/*` endpoints, `RemoteStateClient`, and the separate `STATEFUL` UI verdict / `modules/dynamic-stubs.js` generator) and standardize stateful simulation around **WireMock Native Scenarios + Handlebars Templating** for HTTP/protocol orchestration, paired with **plain Java `ResponseTransformerV2` extensions or provider-owned Testcontainers / code doubles** when complex domain state is unavoidable.

### Recommended Patterns Going Forward

1. **Parallel Multi-Tenant FSM Choreography via Native Scenarios**:
   Use parameterized scenario names such as `"CaseLifecycle-" + caseId` (as proven in `wiremock-examples` `CaseLifecycleStubs.setupIsolatedCaseScenario`) with native `inScenario(...)`, `whenScenarioStateIs(...)`, and `willSetStateTo(...)` bindings. These run cleanly in parallel tests without shared global collision and remain 100% inspectable in WireMock's native `/__admin/scenarios` API and the Web UI Scenario DAG visualizer.
2. **Dynamic Request/Response Echo & Lightweight Templating**:
   Combine native WireMock stubs (`priority` tiers per [ADR 0026](0026-native-priority-tiers-for-stub-parking-and-project-modes.md)) with WireMock's built-in `response-template` Handlebars transformer for echoing request paths, headers, and JSON fields without custom AST DSLs.
3. **Rich Domain Invariants via Code Doubles or Testcontainers**:
   When a dependency genuinely requires multi-field relational validation or cross-entity transaction guarantees, use the provider team's Testcontainer/in-memory fake service or implement a small, IDE-debuggable Java `ResponseTransformerV2` packaged with the test harness rather than a generic JSON rule interpreter.

## Consequences

- **Unified Stub & Verdict Model**: All traffic resolves into three clear verdicts (`PROXIED`, `STUB`, `UNMATCHED`) across standard `/__admin/mappings` and `/__admin/requests` without shadow entity catalogs or dual UI editors.
- **Zero Custom DSL Maintenance**: Eliminates ongoing maintenance of AST expression/mutation evaluators, UI template generators (`dynamic-stubs.js`), and bespoke session-inspection endpoints.
- **Native Scenario Observability**: Every multi-step state transition is visible in `/__admin/scenarios` and the Web UI Scenarios DAG graph out of the box.
- **Intentional Boundary Trade-off**: Users running WireMock Standalone as a pure remote container who previously used `RemoteStateClient` to push multi-step relational state machines dynamically over HTTP must instead express state transitions via native WireMock Scenario mappings (`/__admin/mappings`), deploy a JAR-packaged Java `ResponseTransformerV2`, or spin up a real/fake service container.

## Future Reconsideration Guidance

If declarative, over-the-wire stateful simulation is ever reconsidered in the future:

- **Extend native WireMock Scenarios/State primitives** directly on standard `StubMapping` definitions (`/__admin/mappings` and `/__admin/scenarios`) and lifecycle priority tiers ([ADR 0026](0026-native-priority-tiers-for-stub-parking-and-project-modes.md)) rather than creating a parallel stub catalog, separate admin route namespace (`/__admin/stateful/*`), or standalone rule database engine.
- Preserve strict request/tenant partitioning (e.g., scenario partitioning keys or correlation-scoped scenario state) and closed-world execution safety so multi-tenant test isolation and RCE guarantees remain intact without fragmenting stub ownership.
