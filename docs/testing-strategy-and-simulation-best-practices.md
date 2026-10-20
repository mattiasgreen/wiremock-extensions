# Testing Strategy & Simulation Best Practices

A comprehensive guide to execution boundaries, orthogonal system complexity dimensions, and simulation archetypes for modern microservices and distributed systems.

---

## 1. Executive Summary & Context

Modern microservice architectures face a persistent testing dilemma:
* **In-Memory Mocks (Mockito / Unit Tests)** are blisteringly fast (<1ms) but blind to network boundaries, serialization quirks, corporate headers, and wire faults.
* **Full Multi-Container Environments (Testcontainers / Docker Compose)** provide high fidelity but introduce container sprawl, slow boot times (15–45s), resource bloat (2–6 GB RAM), and flaky CI pipelines.
* **The WireMock Simulation Stack** occupies the strategic sweet spot: **real network wire fidelity with sub-millisecond, in-process determinism and zero container infrastructure**.

However, effective testing requires understanding where simulation fits within the broader software delivery lifecycle, which entity owns which testing artifact, and when a service crosses the line into containerization.

---

## 2. The Execution Boundary Spectrum (Levels 1 to 4)

Testing does not exist in a binary "mock vs. real" choice. It operates across **four distinct execution boundaries**:

```
LEVEL 1                     LEVEL 2                     LEVEL 3                     LEVEL 4
Pure Logic & Synthesis      In-JVM Component Mock       Wire Boundary (RPC / Net)   Full Environment Boundary
────────────────────────    ────────────────────────    ────────────────────────    ────────────────────────
Domain logic & transforms   In-memory client mocks      WireMock & Extensions       Testcontainers / Real Env
• No network, no sockets    • Mockito / Spock           • Real HTTP / Casual wire   • Multi-process Docker
• Producer SDK Data         • Bypasses serialization,   • Serialization, headers,   • Opaque proprietary code
  Synthesis / Builders        headers & network           W3C tracing, NetEm chaos    or legacy binary internals
• Sub-millisecond           • Sub-millisecond           • Sub-10ms, deterministic   • 10s–minutes, heavy
```

### Level 1 — Pure Functions & Test Data Synthesis
* **Scope**: Domain calculations, state transitions, validation rules, and data mapping functions.
* **Mechanics**: Plain JUnit / Spock unit tests. No network, no sockets, no mocking frameworks.
* **Producer SDK Role**: The service producer delivers **Domain Data Factories & Builders** (e.g. `CustomerProfileBuilder`, `RiskAssessmentFixture`). This guarantees that consumers synthesize authentic, schema-valid domain objects without maintaining fragile manual test data.
* **Performance**: Sub-millisecond, 100% thread-safe, and parallelizable across CPU cores.

### Level 2 — In-JVM Component Boundaries (Method-Level Mocking)
* **Scope**: Internal application orchestration and collaborator wiring within the runtime.
* **Mechanics**: Mocking frameworks (Mockito, MockK) mocking the producer's client interface (`BillingClient.class`).
* **Producer SDK Role**: The producer supplies typed interfaces (`BillingClient`) designed for simple mocking in consumer unit tests.
* **Inherent Limitations**: Completely blind to HTTP status codes, missing headers, JSON serialization/deserialization bugs (e.g. timestamp formats, missing enum values), connection timeouts, and network degradation.

### Level 3 — Wire Boundary RPC & Network Semantics (The Simulation Stack)
* **Scope**: Actual network transport, real client HTTP/RPC/Casual protocol drivers, and wire-level protocol contracts.
* **Mechanics**: **WireMock Standalone + Extension Suite** (`wiremock-extensions`).
* **Value Proposition**:
  - Tests real network serialization and deserialization.
  - Verifies corporate header propagation (`x-correlation-id`, `x-tenant-id`, W3C `traceparent`).
  - Simulates L4/L7 wire faults (TCP RST, connection latency, packet slicing, HTTP 503 retry storms).
  - Emulates complex protocol gateways (Netty Casual XATMI / 2PC transaction participants).
  - Maintains sub-second startup, zero Docker overhead, and deterministic execution.

### Level 4 — Full Environment Boundary (The Testcontainer Crossing Line)
* **Scope**: Black-box algorithmic invariants, deep database triggers, or opaque legacy runtimes.
* **Mechanics**: Testcontainers, Docker Compose, or portable native binaries.
* **When to Cross the Line**: Reserved exclusively for dependencies whose internal behavior cannot or should not be simulated because the *entire purpose of the test* is to verify the producer's internal proprietary algorithm or database locking semantics.

---

## 3. Orthogonal Complexity Dimensions

Across the Wire Boundary (Level 3), external dependencies vary across three orthogonal dimensions:

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                          ORTHOGONAL COMPLEXITY DIMENSIONS                       │
├──────────────────────────┬──────────────────────────┬───────────────────────────┤
│ 1. State Complexity      │ 2. Interaction Flow      │ 3. Data / Graph Depth     │
├──────────────────────────┼──────────────────────────┼───────────────────────────┤
│ • No-op / Fire & Forget  │ • Sync Request-Reply     │ • Flat scalar JSON / DTO  │
│ • Static Stub            │ • Test-Driven Async      │ • Relational Nested Tree  │
│ • Dynamic CRUD           │   (Wait + Callback)      │ • Correlated Multi-Entity │
│ • Stateful FSM with      │ • Long-polling / SSE     │   Graph                   │
│   Invariant Guards       │ • 2PC / XA Distributed   │                           │
│                          │   Transaction            │                           │
└──────────────────────────┴──────────────────────────┴───────────────────────────┘
```

### Dimension A: State Complexity
1. **No-op / Fire-and-Forget**: Receives an event or notification, responds immediately with `200/202`, and discards the payload.
2. **Static & Templated Stubs**: Returns canned responses or Handlebars-templated values matching URL patterns or query parameters.
3. **Dynamic CRUD**: In-memory resource simulation supporting Create, Read, Update, and Delete across a test suite without database persistence.
4. **Finite State Machine (FSM)**: Evaluates preconditions and guards before permitting state transitions (e.g. *Cannot close case while tasks are pending*). Powered by `wiremock-extension-stateful` with multi-tenant session isolation.

### Dimension B: Interaction Flow
1. **Synchronous Request-Reply**: Standard HTTP REST, gRPC, or Casual XATMI `tpcall`.
2. **Test-Driven Async (Active Test Driver)**:
   - Consumer issues an async request (e.g., place order).
   - Consumer enters a natural, observable wait state (e.g., `ORDER_PENDING_BILLING`).
   - The **BDD Test Driver** actively stimulates the consumer (via webhook callback or test-queue message) using the Producer's TestKit.
   - *Key Rule*: The simulator does **not** need autonomous timers or background dispatchers; the test orchestrates the timeline deterministically.
3. **Multi-Party Distributed Transactions**: Coordinated 2PC / XA commit and rollback sequences simulated via `wiremock-extension-casual`.

### Dimension C: Data & Graph Depth
1. **Flat Scalars**: Simple key-value payloads, identifiers, and status codes.
2. **Relational Nested Trees**: Hierarchical structures (orders with line items, addresses, discounts).
3. **Correlated Multi-Entity Graphs**: Deeply interconnected entity topologies (customer CRM histories, risk assessment scorecards, product catalog hierarchies).

---

## 4. The 5 Core Simulation Archetypes

Real-world microservice dependencies cluster into five distinct architectural archetypes:

| Archetype | Primary Example | State Model | Flow Model | Simulation Approach |
| :--- | :--- | :--- | :--- | :--- |
| **1. Fire-and-Forget Sink** | Audit logs, telemetry, email notifications | No-op (drop) | Sync / Async fire | Single static WireMock stub returning `202 Accepted`. |
| **2. Rich Information Provider** | Customer CRM, Risk Scoring, Product Catalog, LDAP | Read-only (stateless) | Synchronous query | Sophisticated **Producer SDK Data Builders** feeding realistic WireMock response stubs. |
| **3. Test-Driven Async Workflow** | Order checkout -> Billing confirmation -> Shipping | Correlated wait state | Asynchronous callback | WireMock records outbound request; BDD test runner injects webhook/message callback via Producer TestKit. |
| **4. Stateful Enterprise Entity** | Case Management, Loan Applications, Claims | Explicit FSM | Sync CRUD + Mutations | `wiremock-extension-stateful` in-memory AST engine with invariant guards. |
| **5. Resilience & Chaos Gateway** | Core Banking Casual XATMI, Payment Gateways | Transactional (2PC) | Synchronous / 2PC XA | `wiremock-extension-tcp-netem` (L4 latency/RST) + Casual protocol gateway. |

---

### Deep Dive: Archetype 2 — The Rich Information / Complex Graph Provider

Systems that provide rich, read-only entity graphs (CRM systems, risk-scoring engines, master product catalogs) are **easy to mock from an interaction standpoint** (simple `GET /api/v1/customers/{id}` or `POST /risk/evaluate`), but **difficult to mock from a data realism standpoint**.

#### The Anti-Pattern: Hand-Rolled Giant JSON Dumps
Consumers frequently copy-paste massive (50 KB+) JSON files from production into their test directories. These files quickly rot:
- When the producer adds required fields or deprecates enums, the consumer's test suite remains unaware.
- The consumer cannot easily synthesize edge cases (e.g., customer with 50 past addresses, high-risk flag, and expired KYC).

#### The Best Practice: Producer SDK Test Data Builders
The producer provides fluent, composable data builders in their companion TestKit library:
```java
// Consumer test using Producer's Billing TestKit builder:
CustomerProfile profile = CustomerProfileBuilder.aValidCustomer()
    .withRiskTier(RiskTier.ELEVATED)
    .withOpenInvoice(inv -> inv.withAmount(1500.00).overdueByDays(45))
    .withKycStatus(KycStatus.VERIFIED)
    .build();

// WireMock serves this authentic synthesized graph
wireMock.stubFor(get(urlEqualTo("/api/v1/customers/CUST-99"))
    .willReturn(okJson(profile.toJson())));
```

---

### Deep Dive: Archetype 3 — The Test-Driven Async Workflow

#### The "Autonomous Simulator Trap"
When building mocks for asynchronous flows (e.g. Order Service -> Billing -> Shipping), teams often attempt to make the mock server autonomous:
1. WireMock receives `POST /charges`.
2. A background timer in WireMock waits 100ms.
3. WireMock autonomously fires a webhook back to the Order Service.

This creates severe testing problems:
- **Flaky Race Conditions**: Tests intermittently fail on slow CI runners when the 100ms timer expires before the consumer is ready.
- **Inability to Test Negative Cases**: Testing what happens when billing *times out* or *fails* requires complex configuration of the mock's autonomous clock.

#### The Best Practice: Test-Driven Stimulation
Instead, let the test drive the async timeline:

```gherkin
Scenario: Customer places order and payment succeeds
  When the customer places order "ORD-101" for $99.00
  Then the order status should be "PENDING_BILLING"
  And a billing charge request was recorded for "ORD-101"

  # The TEST drives the async progression via Producer TestKit!
  When billing confirms payment for "ORD-101" with status "APPROVED"
  Then the order status should transition to "PROCESSING"
  And a shipping request should be initiated
```

Under the hood:
```java
// BDD Step Definition:
@When("billing confirms payment for {string} with status {string}")
public void billingConfirmsPayment(String orderId, String status) {
    // SDK TestKit injects the callback event directly into the consumer's webhook or test queue!
    billingTestKit.emitWebhookCallback(orderId, BillingStatus.valueOf(status));
}
```

The simulator remains 100% passive: it records the outbound request into the Request Journal. The BDD test driver controls the timeline, eliminating race conditions.

---

## 5. Responsibility & Ownership Matrix

Clear boundaries between producers, consumers, test frameworks, and the simulation stack prevent duplication and ensure contract parity:

| Role | Primary Responsibility | Key Deliverables |
| :--- | :--- | :--- |
| **Producer Team** | Contract Parity, Schemas & Domain Tooling | • OpenAPI 3.0/3.1 & AsyncAPI specifications.<br>• Production Client SDK (`service-client.jar`).<br>• **Companion TestKit** (`service-testkit.jar`) with Data Builders & Fixtures.<br>• Ready-made **Cucumber / BDD Step Definitions**.<br>• Validated WireMock stub packs published to artifact repository. |
| **Consumer Team** | Application Logic & Scenario Expectations | • Defining BDD feature files and scenario expectations.<br>• Writing integration tests against Producer TestKit / stubs.<br>• Maintaining consumer-side contract tests against producer specs. |
| **BDD Test Driver** (JUnit / Cucumber / Playwright) | Scenario Orchestration & Timeline Progression | • Driving the test lifecycle.<br>• Triggering async callbacks and state transitions via Producer TestKit.<br>• Executing assertions across consumer state. |
| **Simulation Platform** (`wiremock-extensions`) | Protocol Fidelity & Execution Engine | • Wire-level transport (HTTP/1.1, HTTP/2, Netty Casual XATMI, Toxiproxy L4).<br>• Request Journal with compound boolean filtering (`AND`, `OR`) and datetime range windows.<br>• 1-Click filtered stub snapshotting (`📸 Snapshot Filtered Proxied`).<br>• In-memory Stateful AST engine with session isolation.<br>• L4/L7 Network Emulation (NetEm) for chaos and resilience testing.<br>• Web Console UI (`/__admin/ui`) for developer exploration and debugging. |

---

## 6. Anti-Patterns to Avoid

1. **The "Mockito Illusion"**:
   - *Anti-Pattern*: Relying 100% on Mockito method mocks for external microservice boundaries.
   - *Consequence*: Tests pass in CI, but fail in production due to header mismatch, serialization format drift, or HTTP error status handling bugs.
2. **The "Hand-Rolled JSON Dump"**:
   - *Anti-Pattern*: Consumers creating hand-crafted JSON files to mock complex entity graphs.
   - *Consequence*: Stale schemas, broken contracts, and high maintenance overhead.
3. **The "Autonomous Mock Clock"**:
   - *Anti-Pattern*: Designing mock servers with background thread pools that auto-emit callbacks on timers.
   - *Consequence*: Flaky CI runs and race conditions. Always drive async transitions from the test runner.
4. **Premature Testcontainer Sprawl**:
   - *Anti-Pattern*: Spinning up 6 Docker containers for simple request-reply or test-driven async services.
   - *Consequence*: 10x slower build pipelines, memory exhaustion on developer laptops, and high developer friction.
