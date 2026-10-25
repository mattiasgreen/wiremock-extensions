---
name: stubs-to-testkit
description: >-
  Use this skill when analyzing a set of recorded WireMock stubs and an OpenAPI 3.0/3.1 specification
  to bootstrap a fluent Java TestKit, Object Mother factories, and dual-mode (In-JVM Mockito + WireMock HTTP)
  simulation tests.
---

# Stubs to Dual-Mode Java TestKit Bootstrap Protocol

Autonomous coding agents follow this 5-step protocol to elevate raw recorded WireMock stubs into a type-safe, time-invariant, dual-mode Java TestKit:

```text
[1. Deterministic Trace Analysis] ──► [2. Domain Model & Invariants] ──► [3. Object Mother Factories]
                                                                                   │
[5. Dual-Mode BDD Verification] ◄── [4. Dual-Mode Simulation DSL] ◄────────────────┘
```

---

## Step 1: Deterministic Trace & OpenAPI Analysis
Never hand-craft stubs or attempt to guess operation contracts without running the deterministic analyzer:

```java
RecordedStubAnalyzer analyzer = new RecordedStubAnalyzer();
RecordedStubAnalysisReport report = analyzer.analyze(openApiSpecYaml, recordedStubMappings);
```

The analyzer deterministically yields:
1. **OpenAPI Operation Mapping**: `(httpMethod, pathTemplate, operationId, responseSchemaName)`.
2. **Request-to-Response Parameter Echoes** (`List<ParameterEcho>`): Detects where path or query parameters (e.g. `customerId`, `asOf`) are reflected back in the response body.
3. **Temporal Field Detection** (`List<TemporalFieldInfo>`): Detects ISO-8601 dates and timestamps (`onboardedDate`, `asOfDate`).
4. **Archetype Trait Diffing**: Automatically compares multiple recordings for the same operation, identifying:
   * `sharedBaselineFields`: Identical across all recordings (e.g. `processedBy`).
   * `distinguishingTraitFields`: Vary across recordings (e.g. `riskTier: [LOW, HIGH]`, `kycStatus: [VERIFIED, PENDING]`, `segment: [PREMIER, STANDARD]`).

---

## Step 2: Synthesize Typed Domain Model & Provider Invariants
Create the immutable Java model (or record) representing the entity with Jackson annotations and a fluent builder:
1. Annotate with `@JsonDeserialize(builder = MyModel.Builder.class)` and `@JsonIgnoreProperties(ignoreUnknown = true)`.
2. Annotate temporal fields with `@JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")`.
3. Provide `.builder()` and `.toBuilder()` methods.
4. **Enforce Provider Invariants in `.build()`**:
   * Null checks on all required fields.
   * Business invariant rules (e.g. *If `segment == PREMIER`, `kycStatus` must be `VERIFIED`*).

---

## Step 3: Synthesize Semantic Object Mother Factories (`*Mother.java`)
Do **not** dump dozens of raw JSON files into test resources. Instead, create an **Object Mother** in pure Java:
1. Seed baseline defaults using values from `sharedBaselineFields`.
2. Create named factory methods matching the distinguishing traits (e.g. `premierVipCustomer()`, `highRiskNewCustomer()`).
3. Return a pre-populated `Builder` so individual test cases can override *only* what matters for their scenario.
4. **Use Relative Dates**: For sliding-window business rules, default to relative offsets (`LocalDate.now().minusYears(4)`) rather than frozen historical calendar dates.

---

## Step 4: Synthesize Dual-Mode Simulation DSL (`*SimulationTestKit.java`)
Create a simulation DSL that enables consumers to use the exact same built objects across two execution boundaries:
1. **Level 2 (In-JVM Mockito)**:
   ```java
   public static CrmSimulationTestKit forMockito(CrmClient mockitoClient)
   ```
   Directly configures `Mockito.when(mock.myMethod(eq(id), any())).thenReturn(profile);`. Sub-millisecond execution, zero network sockets.
2. **Level 3 (WireMock Wire Boundary)**:
   ```java
   public static CrmSimulationTestKit forWireMock(WireMockServer server)
   ```
   Registers `WireMock.get(urlPathTemplate("/.../{id}")).withPathParam("id", equalTo(id)).willReturn(okJson(objectMapper.writeValueAsString(profile)))`. Real HTTP network transport, real Jackson serialization, and real headers.

---

## Step 5: Dual-Mode BDD Verification Test
Verify the bootstrapped TestKit by running a JUnit 5 `@ParameterizedTest` across `@EnumSource(ExecutionMode.class)`:
1. **Scenario 1 (Happy Path / VIP Tenure)**: Dynamic customer ID + relative date tenure assertion.
2. **Scenario 2 (Negative / High Risk)**: High risk rejection.
3. **Scenario 3 (Provider Invariant Enforcement)**: Verify that attempting to build an invalid model fails fast in Java before any network or mock call is executed.

---

## Verification Commands
```bash
# 1. Inner loop: verify analyzer & generated testkit
./gradlew :wiremock-extension-openapi:test --tests "RecordedStubAnalyzerTest" -q --console=plain
./gradlew :wiremock-examples:test --tests "RecordedStubsToTestKitEndToEndTest" -q --console=plain

# 2. Defect & quality gate before commit
./gradlew :wiremock-extension-openapi:spotbugsMain :wiremock-examples:spotbugsMain -q --console=plain
./gradlew spotlessApply check -q --console=plain
```
