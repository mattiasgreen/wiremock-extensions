# WireMock Stateful Extension (`wiremock-extension-stateful`)

> [!WARNING]
> **Status: Experimental / Alpha Prototype**
> This extension is an experimental prototype exploring AST-driven dynamic state simulation in WireMock.
> Before adopting this extension in production test suites, carefully review the [Architectural Trade-Off Analysis & The Crossing Line](#1-architectural-trade-off-analysis--the-crossing-line) below. For 85%+ of stateful testing requirements, standard **WireMock Native Scenarios** with ID scoping or **Provider-Supplied Testcontainers** are the recommended industry paths.

Dynamic, in-memory stateful API simulation with type-safe Java DSL, 100% RCE-safe AST serialization, and multi-tenant correlation-isolated execution for WireMock Standalone.

---

## 1. Architectural Trade-Off Analysis & The Crossing Line

### The Value Proposition vs. The Accidental Complexity Trap
When simulating stateful dependencies (such as Case Management, Invoicing, or Order Workflows), teams often struggle with static mock stubs:
* Standard WireMock scenarios can become verbose when modeling deep CRUD lifecycles.
* Handlebars templating can echo attributes, but cannot enforce relational invariant guards across multiple requests.

`wiremock-extension-stateful` was prototyped to explore whether a **generic, RCE-safe AST engine** evaluated directly inside WireMock could bridge this gap without writing custom Java plugins for each domain entity.

However, real-world evaluation reveals significant architectural trade-offs:

1. **Greenspun's Tenth Rule Applied to Testing**:
   > *"Any sufficiently complicated C or Fortran program contains an ad hoc, informally-specified, bug-ridden, slow implementation of half of Common Lisp."*
   Attempting to simulate relational state, dynamic collections, and conditional invariants inside a mock server inevitably recreates an ad-hoc, in-memory relational database inside WireMock configuration.
2. **Client Meta-Programming & Maintenance Overhead**:
   Instead of writing straightforward tests or plain Java domain logic, developers must compose and debug abstract syntax trees (`Expr.field("status").eq("OPEN")`, `Action.updateListItem(...)`). This introduces cognitive friction and fragile test setup.
3. **Debuggability & Observability Friction**:
   When an AST invariant fails or state is corrupted, troubleshooting requires inspecting serialized JSON AST rules across HTTP headers rather than stepping through standard code or inspecting plain request/response pairs in WireMock's Request Journal.

### Decision Matrix: When to Use What

| Approach | Best Fit / Scope | Strengths | Drawbacks / Anti-Patterns | Recommendation |
| :--- | :--- | :--- | :--- | :--- |
| **1. WireMock Native Scenarios (with ID / Correlation Scoping)** | Linear / branching state transitions (e.g., `NEW` $\to$ `IN_PROGRESS` $\to$ `CLOSED`). | Standard WireMock feature, 0 extra dependencies, visible in UI DAG visualizer, simple to understand. | Combinatorial explosion if modeling arbitrary-length collections. | **Recommended for 85% of stateful flows.** Use URL ID / correlation prefixing to avoid parallel test collisions. |
| **2. Handlebars Response Templating** | Stateless dynamic responses (echoing request fields, generating UUIDs, calculating future timestamps). | Lightweight, native WireMock support, low overhead. | Cannot store cross-request state or enforce business invariants. | **Recommended for attribute echoing & dynamic IDs.** |
| **3. Provider-Supplied Testcontainer or In-Memory Fake** | Deep relational graphs, complex invariant guards, ledger balances, database constraints. | 100% domain fidelity, real business logic, fully debuggable with standard IDE tools. | Requires container engine or in-memory fake library; slightly higher boot time (~1–3s). | **The Crossing Line: Recommended when state complexity exceeds simple FSMs.** |
| **4. `wiremock-extension-stateful` (AST Simulation)** | Distributed testing topologies where Docker is prohibited and WireMock Standalone runs centrally. | In-memory evaluation, session isolation per `X-Correlation-Id`, 0% RCE risk. | AST DSL cognitive overhead, complex debugging, prototype status. | **Experimental / Alpha.** Evaluate with caution. |

### The "Crossing Line" Principle
**Do not build a database inside your mock server.** If your simulation requirements require complex collection joins, arithmetic balance checks, or multi-entity cascading invariants, you have reached **The Crossing Line**. Rather than layering complex AST rules on WireMock:
1. Request that the **producer team provide an authentic in-memory test double or lightweight Testcontainer** (see [Testing Strategy & Simulation Best Practices](../docs/testing-strategy-and-simulation-best-practices.md)).
2. Use WireMock at the wire boundary to inject network latency, transport errors (503, TCP drops), and corporate header validation, delegating deep business logic to the authentic double.

---

## 2. Problem & Architecture (Prototype Reference)

Standard WireMock stubbing is designed for static request-response matching. When simulating stateful APIs (such as Case Management, Invoicing, or Order Workflows), teams quickly run into limitations:
* **Combinatorial State Explosion**: Modeling dynamic collections (e.g., adding an arbitrary number of tasks to a case) requires an exponential number of WireMock scenarios.
* **Complex Invariants**: Expressing cross-entity constraints (e.g., *"Cannot close case while child tasks are pending"*) is difficult and brittle using Handlebars templates.
* **Global Scenario Collisions**: WireMock scenarios share global state; concurrent tests mutating the same scenario name collide and fail unpredictably.
* **Centralized Packaging Bottleneck**: Forcing domain models into a centralized WireMock container causes release coupling and classpath collisions.

### The Prototype Architecture: AST-Driven Stateful Simulation

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

## 3. Universal State Primitives DSL

The DSL is domain-agnostic and models stateful RESTful APIs using universal primitives:

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

## 4. Remote HTTP Instrumentation

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

## 5. Security & RCE Prevention

1. **Closed-World AST**: The AST is represented by Java 21 sealed interfaces (`AstExpression`, `AstMutation`).
2. **No Arbitrary Classloading**: Jackson is configured with explicit `@JsonSubTypes` and `FAIL_ON_UNKNOWN_PROPERTIES`. Unknown or malicious class gadget chains are strictly rejected.
3. **No Reflection**: Expressions are evaluated directly against in-memory `JsonNode` documents without reflection or dynamic code execution.

---

## 6. Admin API Endpoints

| Method | Route | Description |
| :--- | :--- | :--- |
| `POST` | `/__admin/stateful/rules` | Registers an AST model definition for the specified `X-Correlation-Id`. |
| `DELETE` | `/__admin/stateful/sessions/{correlationId}` | Clears state and rules for a specific correlation ID. |
| `DELETE` | `/__admin/stateful/sessions` | Clears all active sessions and state. |
| `GET` | `/__admin/stateful/entities/{correlationId}/{entityType}/{entityId}` | Inspects the live in-memory state of an entity. |

---

## 7. Maven Coordinates

Published to Maven Central as a standalone client & server extension:

```groovy
testImplementation "io.github.mattiasgreen:wiremock-extension-stateful:${wiremockExtensionsVersion}"
```
