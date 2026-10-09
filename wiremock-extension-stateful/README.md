# WireMock Stateful Extension (`wiremock-extension-stateful`)

Dynamic, in-memory stateful API simulation with type-safe Java DSL, 100% RCE-safe AST serialization, and multi-tenant correlation-isolated execution for WireMock Standalone.

---

## 1. Problem & Architecture

Standard WireMock stubbing is designed for static request-response matching. When simulating stateful APIs (such as Case Management, Invoicing, or Order Workflows), teams quickly run into limitations:
* **Combinatorial State Explosion**: Modeling dynamic collections (e.g., adding an arbitrary number of tasks to a case) requires an exponential number of WireMock scenarios.
* **Complex Invariants**: Expressing cross-entity constraints (e.g., *"Cannot close case while child tasks are pending"*) is difficult and brittle using Handlebars templates.
* **Global Scenario Collisions**: WireMock scenarios share global state; concurrent tests mutating the same scenario name collide and fail unpredictably.
* **Centralized Packaging Bottleneck**: Forcing domain models into a centralized WireMock container causes release coupling and classpath collisions.

### The Solution: AST-Driven Stateful Simulation

```
┌────────────────────────────────────────────────────────┐
│ 1. Consumer / Producer Test System (Java / Gradle)     │
│    - Defines domain state via generic, type-safe DSL   │
│    - Serializes model into safe JSON AST               │
└──────────────────────────┬─────────────────────────────┘
                           │ HTTP POST /__admin/stateful/rules
                           │ Header: X-Correlation-Id: test-scenario-123
                           ▼
┌────────────────────────────────────────────────────────┐
│ 2. Generic WireMock Standalone (:8080)                 │
│    - Evaluates JSON AST in-memory (0% RCE risk)        │
│    - Tracks dynamic collections & invariants           │
│    - Fully isolated per X-Correlation-Id               │
│    - Retains Request Journal & UI Observability        │
└────────────────────────────────────────────────────────┘
```

---

## 2. Universal State Primitives DSL

The DSL is 100% domain-agnostic and models stateful RESTful APIs using universal primitives:

```java
AstModelDefinition caseModel = SimulatorModel.forEntity("cases")
    .idFromPath("/api/v1/cases/{caseId}")

    // 1. Create Case
    .onPost("/api/v1/cases")
        .initialState(Map.of("status", "OPEN", "tasks", List.of()))
        .respondWith(201, ResponseSource.entity())

    // 2. Append to List + Invariant Check
    .onPost("/api/v1/cases/{caseId}/tasks")
        .require(Expr.field("status").eq("OPEN"), 409, "Cannot add tasks to closed case")
        .mutate(Action.appendToList("tasks", Source.requestBodyWithGeneratedId("id", "tsk-")))
        .respondWith(201, ResponseSource.lastAppendedItem("tasks"))

    // 3. Query Collection
    .onGet("/api/v1/cases/{caseId}/tasks")
        .respondWith(200, ResponseSource.field("tasks"))

    // 4. Update Item in Collection
    .onPut("/api/v1/cases/{caseId}/tasks/{taskId}/complete")
        .mutate(Action.updateListItem("tasks", "id", "taskId", "status", "COMPLETED"))
        .respondWith(200, ResponseSource.updatedItem("tasks", "id", "taskId"))

    // 5. Close Case (Cross-Entity Collection Invariant)
    .onPost("/api/v1/cases/{caseId}/close")
        .require(Expr.field("status").eq("OPEN"), 400, "Case is already closed")
        .require(Expr.list("tasks").allMatch(Expr.field("status").eq("COMPLETED")), 
                 409, "Cannot close case with pending tasks")
        .mutate(Action.setField("status", "CLOSED"))
        .respondWith(200, ResponseSource.entity())
    .build();
```

---

## 3. Remote HTTP Instrumentation

Tests running in a separate JVM instrument remote WireMock over HTTP using `RemoteStateClient`:

```java
RemoteStateClient client = new RemoteStateClient("http://wiremock.internal.net:8080");

String correlationId = "test-scenario-" + UUID.randomUUID();

// Push AST to remote WireMock
client.pushModel(correlationId, caseModel);

// System Under Test executes egress requests containing:
// X-Correlation-Id: test-scenario-...

// Teardown session after test
client.clearSession(correlationId);
```

---

## 4. Security & RCE Prevention

1. **Closed-World AST**: The AST is represented by Java 21 sealed interfaces (`AstExpression`, `AstMutation`).
2. **No Arbitrary Classloading**: Jackson is configured with explicit `@JsonSubTypes` and `FAIL_ON_UNKNOWN_PROPERTIES`. Unknown or malicious class gadget chains are strictly rejected.
3. **No Reflection**: Expressions are evaluated directly against in-memory `JsonNode` documents without reflection or dynamic code execution.

---

## 5. Admin API Endpoints

| Method | Route | Description |
| :--- | :--- | :--- |
| `POST` | `/__admin/stateful/rules` | Registers an AST model definition for the specified `X-Correlation-Id`. |
| `DELETE` | `/__admin/stateful/sessions/{correlationId}` | Clears state and rules for a specific correlation ID. |
| `DELETE` | `/__admin/stateful/sessions` | Clears all active sessions and state. |
| `GET` | `/__admin/stateful/entities/{correlationId}/{entityType}/{entityId}` | Inspects the live in-memory state of an entity. |

---

## 6. Maven Coordinates

Published to Maven Central as a standalone client & server extension:

```groovy
testImplementation "io.github.mattiasgreen:wiremock-extension-stateful:${wiremockExtensionsVersion}"
```
