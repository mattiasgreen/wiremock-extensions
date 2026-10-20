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

---

## 7. Theoretical Grounding & Industry De Facto Standards

The strategies and execution boundaries defined in this guide are not ad-hoc heuristics; they are directly anchored in foundational software engineering literature and de facto industry standards:

```
┌────────────────────────────────────────────────────────────────────────┐
│             FOUNDATIONAL LITERATURE & DE FACTO STANDARDS               │
├─────────────────────────┬────────────────────────┬─────────────────────┤
│ Thinker / Authority     │ Landmark Work          │ Applied Principle   │
├─────────────────────────┼────────────────────────┼─────────────────────┤
│ **Martin Fowler**       │ Mocks Aren't Stubs     │ Test Double         │
│                         │ Practical Test Pyramid │ Taxonomy & Wire     │
│                         │ Consumer-Driven        │ Boundary Isolation  │
│                         │ Contracts              │                     │
├─────────────────────────┼────────────────────────┼─────────────────────┤
│ **Kent Beck**           │ TDD By Example         │ Sub-Second Feedback │
│                         │ Test Desiderata        │ & Test Isolation    │
├─────────────────────────┼────────────────────────┼─────────────────────┤
│ **Nat Pryce &**         │ Growing Object-        │ "Don't Mock Types   │
│ **Steve Freeman**       │ Oriented Software      │ You Don't Own"      │
│                         │ (GOOS)                 │ (Producer TestKit)  │
├─────────────────────────┼────────────────────────┼─────────────────────┤
│ **Gerard Meszaros**     │ xUnit Test Patterns    │ Test Doubles &      │
│                         │                        │ Self-Shunting       │
├─────────────────────────┼────────────────────────┼─────────────────────┤
│ **Michael Feathers**    │ Working Effectively    │ The Wire Seam &     │
│                         │ with Legacy Code       │ Characterization    │
├─────────────────────────┼────────────────────────┼─────────────────────┤
│ **Gojko Adzic &**       │ Specification by       │ Active BDD Driver   │
│ **Dan North**           │ Example                │ vs Autonomous Mocks │
└─────────────────────────┴────────────────────────┴─────────────────────┘
```

### 7.1. Martin Fowler: Test Double Taxonomy, Test Pyramids, and Contracts
* **[Mocks Aren't Stubs (2007)](https://martinfowler.com/articles/mocksArentStubs.html)**:
  - Fowler, following Gerard Meszaros, categorizes test doubles into **Dummies, Fakes, Stubs, Spies, and Mocks**.
  - Our execution spectrum maps directly to this taxonomy:
    - **Level 1** uses *Fakes & Synthesizers* (in-memory domain models).
    - **Level 2** uses *Mocks & Spies* (Mockito verifying method invocations).
    - **Level 3** uses *Wire Stubs & Wire Fakes* (WireMock returning canned or stateful responses over TCP/HTTP).
* **[The Practical Test Pyramid (2018)](https://martinfowler.com/articles/practical-test-pyramid.html)**:
  - Together with Ham Vocke, Fowler demonstrates how service and component tests over wire boundaries prevent the costly "ice-cream cone" anti-pattern (over-reliance on slow, brittle end-to-end UI tests). WireMock provides the sub-second component wire boundary advocated in the pyramid.
* **[Consumer-Driven Contracts: A Service Evolution Pattern (2006)](https://martinfowler.com/articles/consumerDrivenContracts.html)**:
  - Ian Robinson and Martin Fowler established that external dependencies must be verified against real producer contracts. Our **Brokerless Contract Parity** model realizes this vision: the producer runs the same BDD test against both their live service and their simulation SDK in CI.
* **[Tolerant Reader Pattern (2011)](https://martinfowler.com/bliki/TolerantReader.html)**:
  - WireMock enables testing consumer resilience against schema evolution (e.g. ignoring unknown fields, surviving reordered JSON keys) which in-JVM method mocking completely obscures.

### 7.2. Kent Beck: Fast Feedback Loops and Test Desiderata
* **[Test-Driven Development: By Example (2002)](https://www.oreilly.com/library/view/test-driven-development/0321146530/)**:
  - Beck's foundational premise is the **rapid feedback loop**: developers write a failing test, write code to pass, and refactor in seconds.
  - If integration testing requires spinning up multi-container Docker environments (Level 4), boot times (15–30s) shatter this rhythm, leading developers to abandon TDD. WireMock (Level 3) restores the sub-second cycle (<20ms boot, in-process execution).
* **[Canon TDD & Test Desiderata (Kent Beck)](https://tidyfirst.substack.com/)**:
  - Beck outlines the core properties of healthy tests: **Fast, Isolated, Deterministic, and Inspiring Confidence**.
  - Driving async workflows from the BDD test runner rather than relying on autonomous simulator clocks preserves Beck's *Determinism* and *Isolation* criteria.

### 7.3. Nat Pryce & Steve Freeman: "Don't Mock What You Don't Own"
* **[Growing Object-Oriented Software, Guided by Tests (GOOS, 2009)](http://www.growing-object-oriented-software.com/)**:
  - Freeman and Pryce formulated the seminal rule: **"Don't mock types you don't own."**
  - When consumers write Mockito mocks for third-party or upstream APIs, they mock their *assumptions* of how the external API behaves—not its reality. When the upstream API changes, consumer unit tests remain green while production fails.
  - **The Architectural Consequence**: This principle directly justifies why the **Producer must deliver the TestKit and Stub Pack**. If the producer provides the stubbing companion, the consumer tests against types and behaviors maintained and verified by the system's actual owner.

### 7.4. Gerard Meszaros: xUnit Test Patterns
* **[xUnit Test Patterns: Refactoring Test Code (2007)](http://xunitpatterns.com/)**:
  - **Configurable Test Double**: WireMock acts as an out-of-process Configurable Test Double capable of simulating various response scenarios.
  - **Self-Shunting & Callback Sensing**: When testing async workflows (Archetype 3), the BDD driver acts as the stimulus agent, matching Meszaros's self-shunting pattern where the test actively orchestrates the collaborators.
  - **Fresh Fixtures vs. Shared Fixtures**: Meszaros warns against shared mutable state causing test leakage. WireMock Extensions addresses this via **Session Isolation (`X-Correlation-Id`)**, partitioning stateful AST models so parallel tests run against fresh, isolated state within the same process.

### 7.5. Michael Feathers: The Wire Seam & Characterization Tests
* **[Working Effectively with Legacy Code (2004)](https://www.oreilly.com/library/view/working-effectively-with/0131177052/)**:
  - Feathers defines a **Seam** as *"a place where you can alter behavior in a program without editing in that place."*
  - WireMock operates as an **External Network Seam (Wire Seam)**: by changing the upstream URL or DNS/proxy routing, the System Under Test can be redirected from live backends to recorded simulation stubs without modifying a single line of production code.
  - **Characterization Testing**: When integrating against legacy systems that lack documentation, Feathers advocates capturing existing behavior before refactoring. WireMock's **Live Proxy $\to$ 1-Click Snapshot** workflow is the exact implementation of automated characterization testing over the wire.

### 7.6. Gojko Adzic & Dan North: Specification by Example and BDD
* **[Specification by Example (2011)](https://www.manning.com/books/specification-by-example)** & **Dan North (BDD)**:
  - Tests should express business intent and orchestrate scenarios from a domain perspective.
  - In our **Test-Driven Async Archetype**, Cucumber feature files describe the customer journey explicitly. The test runner drives the timeline, triggering the producer's callback when the scenario dictates, rather than delegating timing to an autonomous mock clock.

---

## 8. Further Reading & Canonical References

1. **Fowler, Martin.** *"Mocks Aren't Stubs."* (2007). [martinfowler.com/articles/mocksArentStubs.html](https://martinfowler.com/articles/mocksArentStubs.html)
2. **Vocke, Ham, & Fowler, Martin.** *"The Practical Test Pyramid."* (2018). [martinfowler.com/articles/practical-test-pyramid.html](https://martinfowler.com/articles/practical-test-pyramid.html)
3. **Robinson, Ian, & Fowler, Martin.** *"Consumer-Driven Contracts: A Service Evolution Pattern."* (2006). [martinfowler.com/articles/consumerDrivenContracts.html](https://martinfowler.com/articles/consumerDrivenContracts.html)
4. **Fowler, Martin.** *"Tolerant Reader."* (2011). [martinfowler.com/bliki/TolerantReader.html](https://martinfowler.com/bliki/TolerantReader.html)
5. **Beck, Kent.** *Test-Driven Development: By Example.* Addison-Wesley Professional, 2002.
6. **Beck, Kent.** *Tidy First? A Personal Exercise in Empirical Software Design.* O'Reilly Media, 2023.
7. **Freeman, Steve, & Pryce, Nat.** *Growing Object-Oriented Software, Guided by Tests.* Addison-Wesley, 2009.
8. **Meszaros, Gerard.** *xUnit Test Patterns: Refactoring Test Code.* Addison-Wesley, 2007. [xunitpatterns.com](http://xunitpatterns.com)
9. **Feathers, Michael.** *Working Effectively with Legacy Code.* Prentice Hall, 2004.
10. **Adzic, Gojko.** *Specification by Example: How Successful Teams Deliver the Right Software.* Manning Publications, 2011.
11. **Rosenthal, Casey, et al.** *Principles of Chaos Engineering.* (2019). [principlesofchaos.org](https://principlesofchaos.org)

