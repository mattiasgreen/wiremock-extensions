# Research Report: IBM MQ Connectivity Options for WireMock Async Mocking

- **Issue**: [#87 - Research: IBM MQ connectivity options (AMQP 1.0 vs. Native Client vs. JMS)](https://github.com/mattiasgreen/wiremock-extensions/issues/87)
- **Parent Map**: [#85 - Map: Async Messaging & Queue Mocking Architecture](https://github.com/mattiasgreen/wiremock-extensions/issues/85)
- **Status**: Completed Research & Architectural Recommendation
- **Date**: 2026-11-08
- **Authors**: WireMock Extensions Architecture Team / Research Subagent

---

## 1. Executive Summary & Core Tradeoffs

As part of the **Async Messaging & Queue Mocking Architecture** ([#85](https://github.com/mattiasgreen/wiremock-extensions/issues/85)), WireMock must act as a lightweight peer client capable of consuming from and producing to enterprise message queues, matching payloads, generating synthetic `ServeEvent` journals, and dispatching correlated replies. 

IBM MQ (formerly WebSphere MQ / MQSeries) remains the dominant message queuing backbone across banking, insurance, healthcare, and enterprise legacy systems. Integrating WireMock with IBM MQ presents a fundamental architectural dilemma:

| Strategy | Primary Benefit | Primary Drawback |
| :--- | :--- | :--- |
| **Approach A: Pure AMQP 1.0 Client** | **Zero proprietary IBM dependencies**, 100% open-source (Apache 2.0), ~500 KB net footprint, zero legal friction for packaging with WireMock Standalone. | Requires IBM MQ **9.3+** with an explicit AMQP channel (`CHLTYPE(AMQP)`); **no support for z/OS (mainframes)** or IBM i; no server-side message selectors; no XA/2PC transactions. |
| **Approach B: Native IBM MQ Client (`com.ibm.mq`)** | **100% universal compatibility** across all MQ versions (7.x - 10.x), all platforms (including **z/OS** and IBM i), full SVRCONN channel support, server-side selectors, and XA transactions. | **Proprietary IBM IPLA license** (not open source); massive classpath bloat (**~16–18 MB** including Bouncy Castle); pins Java 21 Virtual Threads due to internal `synchronized` locks. |

### Architectural Recommendation: Two-Tier Decoupled Driver Strategy

To deliver a **zero-friction developer experience out of the box** while preserving **full enterprise depth for production integration**, WireMock should adopt a **Two-Tier Decoupled Driver Strategy**:

1. **Tier 1 (Built-In Zero-Friction Driver - Default)**:
   - WireMock Standalone ships with a pure **OASIS AMQP 1.0 driver** (`wiremock-extension-amqp` via Apache Qpid Proton-J / Netty).
   - Connects to IBM MQ 9.3+ via standard AMQP channels (`amqp://host:5672/DEV.QUEUE.1`) out of the box with **zero configuration and zero IBM JARs**.
   - Ideal for local development, CI pipelines running containerized IBM MQ (`icr.io/ibm-messaging/mq:latest`), and modern cloud deployments.
   - WireMock's in-memory pattern matching engine compensates for the lack of server-side selectors by evaluating consumed messages locally before journal recording.
2. **Tier 2 (Pluggable Native IBM MQ Driver - Optional Enterprise Add-On)**:
   - Provide a modular provider (`wiremock-extension-ibmmq`) implementing WireMock's `AsyncBrokerDriver` SPI over `jakarta.jms` (Jakarta Messaging 3.1).
   - Compiled with `provided` / `compileOnly` scope against `com.ibm.mq:com.ibm.mq.jakarta.client`.
   - Users connecting to legacy queue managers, **z/OS mainframes**, or requiring native `SVRCONN` (port 1414) supply `com.ibm.mq.jakarta.client` on the classpath (`--cp` or `/drivers/` directory).
   - If configured with an `ibmmq://` URI but the driver is absent, WireMock fails fast with an actionable, clear diagnostic message pointing the user to the exact JAR coordinates or suggesting the `amqp://` alternative.

---

## 2. IBM MQ AMQP 1.0 Support Analysis

IBM MQ provides an optional AMQP 1.0 service that allows standard AMQP clients to communicate directly with the queue manager without installing IBM MQ client libraries.

### 2.1 Version History & Feature Matrix

AMQP support in IBM MQ has evolved significantly across release versions:

| IBM MQ Version | Release Year | AMQP Feature Set | Point-to-Point Queue Support | Queue Browsing Support |
| :--- | :--- | :--- | :--- | :--- |
| **IBM MQ 8.0.0.4** | 2015 | AMQP channel introduced (`SYSTEM.AMQP.SERVICE`). Targeted exclusively at **MQ Light** applications. | **No** (Pub/Sub topics only) | **No** |
| **IBM MQ 9.0 / 9.1** | 2016 / 2018 | LTS releases maintaining MQ Light / AMQP 1.0 pub/sub capabilities. | **No** (Pub/Sub topics only) | **No** |
| **IBM MQ 9.2** | 2020 | Added initial support for **Apache Qpid JMS** clients for publish/subscribe and shared subscriptions. | **No** (Pub/Sub topics only) | **No** |
| **IBM MQ 9.3** | 2022 | **Major Milestone**: Introduced native **Point-to-Point Queue Support**, **Queue Browsing**, and temporary request-reply queues (`TMPMODEL`, `TMPQPRFX`). | **Yes** (Full queue get/put) | **Yes** (`distribution-mode = copy`) |
| **IBM MQ 9.4 (Current LTS)** | 2024 | Mature AMQP 1.0 implementation with queue, topic, and queue browsing support for Qpid JMS and generic AMQP 1.0 clients. | **Yes** | **Yes** |

> [!IMPORTANT]
> **Queue Mocking Constraint**: Connecting WireMock to IBM MQ queues via pure AMQP 1.0 requires **IBM MQ 9.3.0.0 or higher**. Earlier versions (8.x, 9.0, 9.1, 9.2) support only topic-based pub/sub over AMQP.

### 2.2 Platform Boundaries

AMQP channels are implemented as an external Java daemon service (`SYSTEM.AMQP.SERVICE`) running alongside the queue manager process.

- **Supported Platforms**:
  - Linux (x86_64, s390x, POWER, ARM64)
  - AIX
  - Windows
- **Unsupported Platforms**:
  - **z/OS (IBM Mainframes)**: AMQP channels and the AMQP service **do not exist on z/OS**.
  - **IBM i (AS/400)**: AMQP channels are **not available**.

> [!WARNING]
> In enterprise banking and insurance environments where core banking queue managers reside on **z/OS (IBM Mainframes)**, **AMQP 1.0 cannot be used**. Any interaction with z/OS queue managers requires native SVRCONN channels.

### 2.3 Server Configuration & Channel Topology

AMQP channels are **not enabled by default** when a queue manager is created. An MQ administrator or container initialization script must execute the following MQSC commands:

```mqsc
* 1. Ensure the AMQP service starts automatically with the Queue Manager
ALTER SERVICE(SYSTEM.AMQP.SERVICE) CONTROL(QMGR)
START SERVICE(SYSTEM.AMQP.SERVICE)

* 2. Define an AMQP channel on the standard AMQP port (5672)
DEFINE CHANNEL(MY.AMQP.CHANNEL) +
       CHLTYPE(AMQP) +
       PORT(5672) +
       TMPMODEL(SYSTEM.DEFAULT.MODEL.QUEUE) +
       TMPQPRFX('AMQP.*') +
       MCAUSER('appuser') +
       REPLACE

* 3. Authorize the user to the Queue Manager and target queues
SET AUTHREC PROFILE(SELF) OBJTYPE(QMGR) PRINCIPAL('appuser') AUTHADD(+CONNECT, +INQ)
SET AUTHREC PROFILE('DEV.**') OBJTYPE(QUEUE) PRINCIPAL('appuser') AUTHADD(+PUT, +GET, +INQ, +BROWSE)
```

- **Default Ports**:
  - Non-TLS: `5672`
  - TLS / SSL: `5671` (configured via `SSLCIPH`, `CERTLABL`, and `SSLCAUTH` on the AMQP channel).

### 2.4 Addressing Semantics & Link Capabilities

Unlike Apache ActiveMQ or RabbitMQ, IBM MQ **does not parse prefix strings** such as `queue://` or `topic://` out of the AMQP address field. 

Instead, IBM MQ determines whether an address refers to a **Queue** or a **Topic** through **AMQP 1.0 Link Capabilities** passed in the `ATTACH` frame:

1. **Queue Addressing (Point-to-Point)**:
   - **Link Name / Address**: The exact case-sensitive queue name (e.g., `DEV.QUEUE.1`).
   - **Attach Capability**: The AMQP client *must* declare the `queue` symbol capability in the `Source` or `Target` node configuration:
     $$\text{capabilities} = [ \text{symbol}("queue") ]$$
   - If the `queue` capability is omitted, IBM MQ will mistakenly treat `DEV.QUEUE.1` as a **Topic string** in the publish/subscribe hierarchy!
2. **Topic Addressing (Publish/Subscribe)**:
   - **Address**: The topic path (e.g., `sport/football` or `/orders/created`).
   - **Attach Capability**: Omitted, or explicitly declared as `symbol("topic")`.
   - **Private Subscriptions**: Formatted as `private:<topic-string>`.
3. **Queue Browsing**:
   - Clients browse messages non-destructively by specifying the AMQP `distribution-mode` attribute as `copy` in the `ATTACH` frame:
     $$\text{distribution-mode} = \text{symbol}("copy")$$
4. **Temporary Queues (Dynamic Request-Reply)**:
   - Client sends an `ATTACH` frame with dynamic target and capability `symbol("temporary-queue")`.
   - IBM MQ allocates a dynamic queue using the channel's `TMPMODEL` (e.g., `SYSTEM.DEFAULT.MODEL.QUEUE`) with the naming prefix specified in `TMPQPRFX` (e.g., `AMQP.<random-id>`).

### 2.5 Message Mapping: AMQP 1.0 $\leftrightarrow$ MQMD / RFH2

IBM MQ's AMQP service performs bidirectional translation between OASIS AMQP 1.0 message sections and internal IBM MQ structures:

```
+-------------------------------------------------------------+
|                     AMQP 1.0 Message                        |
+-------------------------------------------------------------+
| Header (durable, priority, ttl, first-acquirer)             |
| Delivery Annotations & Message Annotations                  |
| Properties (message-id, correlation-id, to, reply-to, etc.) |
| Application Properties (key-value string/int/bool map)      |
| Body (Data section / AmqpValue)                             |
| Footer                                                      |
+-------------------------------------------------------------+
                                │
                      Bi-directional Mapping
                                ▼
+-------------------------------------------------------------+
|                    IBM MQ Native Message                    |
+-------------------------------------------------------------+
| MQMD (Message Descriptor):                                  |
|   - MsgId            <-- AMQP Properties.message-id         |
|   - CorrelId         <-- AMQP Properties.correlation-id     |
|   - ReplyToQ/QMgr    <-- AMQP Properties.reply-to           |
|   - Expiry           <-- AMQP Header.ttl                    |
|   - Priority         <-- AMQP Header.priority               |
|   - Format           <-- MQSTR (text/JSON) or MQBYTE        |
|   - Persistence      <-- AMQP Header.durable                |
| MQRFH2 Header (Rules and Formatting Header 2):              |
|   - <usr> folder     <-- AMQP Application Properties        |
|   - <jms> folder     <-- JMS-specific headers               |
| Payload Body         <-- AMQP Data section                  |
+-------------------------------------------------------------+
```

- **Text & JSON Payloads**: AMQP string or Data sections automatically set `MQMD.Format` to `MQSTR` with the queue manager's Coded Character Set ID (CCSID, e.g., 1208 for UTF-8).
- **Application Properties**: Any user-defined AMQP properties map directly to native IBM MQ message properties, serializing into an `MQRFH2` header if consumed by legacy applications expecting RFH2.

### 2.6 Limitations of IBM MQ AMQP Channels

While AMQP 1.0 provides clean wire interoperability, IBM MQ's AMQP channel implementation imposes significant constraints compared to native MQI:

1. **No Transacted Sessions**: AMQP clients cannot declare or commit local transactions. Message consumption and production operate strictly under auto-acknowledge (`settled`) or explicit transfer settlement semantics.
2. **No XA / 2PC Distributed Transactions**: Standard AMQP 1.0 transaction coordinator containers (`amqp:coordinator`) are **not supported**. WireMock cannot enlist an AMQP exchange into a two-phase commit (2PC) transaction alongside Casual or an RDBMS.
3. **No Server-Side Message Selectors**: IBM MQ AMQP channels **do not support SQL-92 message selectors or AMQP filter sets**. All messages arriving on a queue must be delivered to the client; filtering must occur client-side in the application.
4. **No Delivery Delays**: The AMQP `delivery-delay` extension is not supported by IBM MQ AMQP channels.
5. **No Temporary Topics**: Dynamic temporary topics are unsupported.
6. **No `no-local` Subscription Filtering**: Subscribers cannot suppress messages published by their own connection.

---

## 3. IBM MQ Native Java & JMS Client Analysis

The native IBM MQ client connects to IBM MQ **Server-Connection channels (`SVRCONN`)** over TCP (default port `1414`) using IBM's proprietary MQI (Message Queue Interface) protocol.

### 3.1 Artifact Options & Coordinate Evolution

IBM publishes official client libraries to Maven Central under `com.ibm.mq`:

| Artifact Coordinate | Target Specification | Target Java Versions | Notes |
| :--- | :--- | :--- | :--- |
| **`com.ibm.mq:com.ibm.mq.allclient`** | **JMS 2.0** / JMS 1.1 (`javax.jms.*`) & base MQI (`com.ibm.mq.*`) | Java 8, 11, 17, 21 | Legacy standard. Used in applications relying on `javax.jms`. |
| **`com.ibm.mq:com.ibm.mq.jakarta.client`** | **Jakarta Messaging 3.1** (`jakarta.jms.*`) & base MQI (`com.ibm.mq.*`) | Java 17, 21 | Modern standard. Introduced in MQ 9.3+ for Jakarta EE 9/10 and Spring Boot 3.x. |

Both artifacts are published under matching release versions (e.g., `9.3.5.0`, `9.4.0.0`, `9.4.1.0`).

### 3.2 Platform & Feature Reach

The native client represents the **gold standard for IBM MQ connectivity**:

- **Universal Platform Coverage**: Connects seamlessly to queue managers running on **z/OS (IBM Mainframe)**, **IBM i (AS/400)**, Linux, Windows, AIX, and IBM Cloud.
- **Universal Version Coverage**: Backward compatible across all MQ releases (MQ 7.0 through MQ 9.4/10.0).
- **Server-Side Message Selectors**: Full support for SQL-92 selectors (`JMSCorrelationID = 'REQ-1234' AND Priority >= 4`). The queue manager filters messages before transmission, minimizing network traffic.
- **Transaction Coordination**: Supports local transactions (`session.commit()`) and full XA / 2PC transaction enlistment via `XAResource` / `MQXAConnectionFactory`.
- **Full MQMD Descriptor Control**: Exposes low-level MQMD fields (MsgSeqNum, GroupId, Feedback codes, Report options like COA/COD/Expiry, AccountingToken, ApplIdentityData).
- **Enterprise High Availability**: Full support for Client Channel Definition Tables (CCDT), multi-instance queue manager failover, automatic reconnect (`MQCNO_RECONNECT`), and security exit pipelines.

### 3.3 Licensing & Redistribution Considerations

Understanding IBM's license terms is vital when deciding whether to bundle client libraries with WireMock Standalone:

- **Governing Agreement**: IBM International Program License Agreement (IPLA) / International License Agreement for Non-Warranted Programs (ILAN).
- **Open Source Incompatibility**: **The IBM client is NOT open source** (not Apache 2.0, MIT, or BSD).
- **Redistribution Status**: IBM designates the client JARs on Maven Central as **"Redistributable Components"**. Developers are legally permitted to package unmodified client JARs within an application distributed to end users free of charge.
- **Entitlement Prerequisite**: The license explicitly states that the runtime is permitted only for the purpose of connecting to a remote IBM MQ queue manager that is **lawfully licensed and entitled** under an IBM MQ license agreement.
- **Packaging Impact on WireMock**:
  - While redistributing the JAR is legally permissible under IBM's terms, bundling a **proprietary 18 MB binary blob** into the pure Apache-2.0-licensed `wiremock-extension-bundle` creates significant corporate friction:
    - Enterprise security compliance audits frequently flag non-open-source binaries bundled inside open-source distributions.
    - Apache Software Foundation (ASF) and many enterprise vendor policies strictly prohibit bundling proprietary Category-X/B commercial binaries in open-source artifacts.
    - Standalone WireMock users who do not use IBM MQ would be forced to download an extra 18 MB payload.

### 3.4 Binary Footprint & Classpath Bloat

The native client is an exceptionally heavy dependency:

```
com.ibm.mq:com.ibm.mq.jakarta.client:9.4.1.0 (~8.5 MB)
├── org.bouncycastle:bcprov-jdk18on:1.78.1 (~8.2 MB)
├── org.bouncycastle:bcpkix-jdk18on:1.78.1 (~1.1 MB)
├── org.bouncycastle:bcutil-jdk18on:1.78.1 (~0.7 MB)
├── jakarta.jms:jakarta.jms-api:3.1.0 (~0.1 MB)
└── jakarta.json:jakarta.json-api:2.1.3 (~0.1 MB)
---------------------------------------------------------------
Total Classpath Addition: ~18.7 MB
```

- The client JAR alone is **~8.5 MB** of compiled bytecode.
- Its mandatory cryptographic dependencies (Bouncy Castle for TLS and Advanced Message Security / AMS) add another **~10 MB**.
- In contrast, WireMock's core extension bundle is optimized for minimal overhead and fast startup.

### 3.5 Java 21 Virtual Threads (Project Loom) Behavior

WireMock extensions run on **JDK 21** and heavily leverage virtual threads for high-throughput, low-overhead I/O concurrency.

Native IBM MQ clients interact poorly with Java 21 Virtual Threads:

1. **Carrier Thread Pinning via `synchronized` Blocks**:
   - The internal codebase of `com.ibm.mq` originates from a mature legacy codebase that relies heavily on Java `synchronized` methods and blocks for thread-safe session multiplexing, connection state machines, and socket write synchronization.
   - In Java 21, when a virtual thread encounters a blocking network operation (e.g., waiting for `MQGET` or socket read/write) while holding a `synchronized` monitor, **it cannot unmount from its carrier thread**.
   - Result: The underlying OS carrier thread is pinned. Under high concurrent mock traffic, the ForkJoinPool carrier thread pool (sized to CPU cores) is rapidly exhausted, leading to complete thread starvation.
   - *(Note: Java 24 JEP 491 redesigns monitor unmounting, but in Java 21 LTS, monitor pinning remains an active constraint).*
2. **Bindings Mode JNI Pinning**:
   - If configured in "bindings mode" (native shared C libraries via JNI), virtual threads are unconditionally pinned because virtual threads cannot unmount across native stack frames.
3. **Architectural Workaround for Native Client**:
   - If `com.ibm.mq` is used, blocking MQ calls **must not run directly on raw virtual threads**. WireMock must isolate native MQ consumer/producer operations onto a dedicated, bounded **platform thread pool** (`Executors.newFixedThreadPool(n)`), passing results back to virtual threads via `CompletableFuture`.

---

## 4. Comprehensive Architectural Comparison Matrix

| Evaluation Dimension | Approach A: Pure AMQP 1.0 Client | Approach B: Native IBM MQ Client (`com.ibm.mq`) |
| :--- | :--- | :--- |
| **Wire Protocol** | OASIS AMQP 1.0 (ISO/IEC 19464) | Proprietary IBM MQI |
| **Target MQ Channel** | AMQP Channel (`CHLTYPE(AMQP)`) | Server-Connection Channel (`CHLTYPE(SVRCONN)`) |
| **Default TCP Port** | `5672` (plain) / `5671` (TLS) | `1414` (plain / TLS) |
| **Min. IBM MQ Version** | **IBM MQ 9.3+** for Point-to-Point Queues | **IBM MQ 7.0+** (universal backward compatibility) |
| **Mainframe Support (z/OS)** | **No** (AMQP service does not exist on z/OS) | **Yes** (universal z/OS mainframe support) |
| **IBM i (AS/400) Support** | **No** | **Yes** |
| **Default Server Availability** | Must be explicitly enabled by MQ administrator (`SYSTEM.AMQP.SERVICE`) | Enabled by default on virtually all MQ installations |
| **Addressing Syntax** | Exact queue name + AMQP `queue` symbol capability | Queue name directly via MQI or JMS destination |
| **Queue Browsing** | Supported (AMQP `distribution-mode = copy`) | Supported natively (`MQOO_BROWSE`) |
| **Server-Side Filtering** | None (no selectors or AMQP filter support) | Full SQL-92 JMS Message Selectors |
| **Transaction / XA (2PC)** | No transacted sessions or XA support | Local transactions and XA / 2PC coordinator enlistment |
| **Message Header Control** | AMQP Properties <-> standard MQMD/RFH2 | Full programmatic control over all 32+ MQMD fields |
| **Client License** | **Apache 2.0** (Open Source) | **IBM IPLA / ILAN** (Proprietary commercial) |
| **Classpath Footprint** | **~500 KB** (engine) / ~3.5 MB with Netty (already present) | **~18.7 MB** (Client JAR + Bouncy Castle + JSON) |
| **JDK 21 Virtual Threads** | Fully unmounts cleanly (async Netty / Proton-J) | **Pins carrier threads** (requires platform thread pool) |
| **WireMock Packaging** | Built-in out of the box in `wiremock-extension-bundle` | Optional runtime add-on / dynamic classpath loading |

---

## 5. Recommended Driver Strategy for WireMock Standalone

Based on the research findings, a single driver approach cannot satisfy all requirements:
- Shipping *only* AMQP 1.0 leaves out enterprise users with **z/OS mainframes**, legacy MQ versions (<9.3), or strict corporate policies that disallow opening port 5672.
- Shipping *only* the native client imposes an unacceptable **18 MB proprietary binary burden** on all WireMock users and violates open-source packaging hygiene.

Therefore, we recommend a **Two-Tier Decoupled Driver Strategy** integrated with WireMock's dynamic broker connection pooling ([#91](https://github.com/mattiasgreen/wiremock-extensions/issues/91)).

```
                                WireMock Standalone Engine
                                            │
                             ┌──────────────┴──────────────┐
                             │  AsyncBrokerDriver SPI      │
                             └──────────────┬──────────────┘
                                            │
               ┌────────────────────────────┴────────────────────────────┐
               │                                                         │
   [Tier 1: Built-in Default]                               [Tier 2: Pluggable Enterprise]
     wiremock-extension-amqp                                   wiremock-extension-ibmmq
   (Apache Qpid Proton-J / Netty)                            (Jakarta JMS 3.1 Driver Seam)
      License: Apache 2.0                                       License: Apache 2.0 (Seam)
       Size: ~500 KB net                                      Client: Provided at Runtime
               │                                                         │
       URI: amqp://host:5672                                     URI: ibmmq://host:1414
               │                                                         │
      OASIS AMQP 1.0 Frame                                         Native MQI Protocol
               │                                                         │
               ▼                                                         ▼
    ┌──────────────────────┐                                  ┌──────────────────────┐
    │  IBM MQ 9.3+ (Linux) │                                  │ IBM MQ (z/OS, AIX,   │
    │  AMQP Channel: 5672  │                                  │ Linux, Windows: 1414)│
    │                      │                                  │ SVRCONN Channel      │
    └──────────────────────┘                                  └──────────────────────┘
```

### 5.1 Tier 1: Built-In Zero-Friction Driver (`wiremock-extension-amqp`)

- **Role**: Ships directly inside `wiremock-extension-bundle`.
- **Target Protocols**: Pure AMQP 1.0 brokers (RabbitMQ, Apache ActiveMQ Artemis, Azure Service Bus, and **IBM MQ 9.3+ with AMQP enabled**).
- **URI Scheme**: `amqp://hostname:5672/DEV.QUEUE.1` or `amqps://...` (TLS).
- **Implementation**:
  - Leverages Apache Qpid Proton-J or Netty AMQP (sharing Netty already bundled in `wiremock-extension-casual`).
  - Sets the mandatory `queue` symbol capability in the AMQP `ATTACH` frame so IBM MQ recognizes the target as a local queue rather than a topic.
  - Automatically handles `distribution-mode = copy` when stubbing read-only / queue-browsing scenarios.
- **Compensating for Missing Server Selectors**:
  - Because IBM MQ AMQP channels do not evaluate SQL-92 selectors server-side, WireMock's AMQP consumer reads messages into memory, where WireMock's core matching engine evaluates headers, correlation IDs, and JSON/XML body patterns. Non-matching messages can either be left unconsumed (via browse mode) or redelivered per stub policy.

### 5.2 Tier 2: Pluggable Native IBM MQ Driver (`wiremock-extension-ibmmq`)

- **Role**: Published as a lightweight, open-source adapter module (`wiremock-extension-ibmmq`) or included as an optional reflection-based driver provider.
- **Target Protocols**: Native IBM MQ over SVRCONN channels (`ibmmq://hostname:1414/QMGR_NAME/DEV.QUEUE.1?channel=DEV.APP.SVRCONN`).
- **Dependency Scope**:
  - Compiles against `jakarta.jms:jakarta.jms-api:3.1.0` (Apache 2.0 / EPL 2.0).
  - References `com.ibm.mq.jakarta.jms.MQConnectionFactory` via `compileOnly` / `provided` Gradle scope.
  - **Zero IBM proprietary bytes are distributed inside the extension JAR**.
- **Runtime Discovery & Loading**:
  - At startup or first connection to an `ibmmq://` URI, WireMock uses `ServiceLoader` or reflection to detect if `com.ibm.mq.jakarta.client` is available on the classpath.
  - If present: WireMock initializes the native connection factory and delegates connection pooling to the driver.
  - If absent: WireMock throws a clear, actionable diagnostic exception:
    ```
    WireMock Async Broker Error:
    Connection URI 'ibmmq://mqserver:1414/QM1/DEV.QUEUE.1' requires the IBM MQ native client.
    The IBM MQ client library is not present on the classpath.
    
    To resolve:
    1. Download com.ibm.mq.jakarta.client (v9.3+) from Maven Central and place it in WireMock's /drivers folder, OR
    2. Start WireMock with: java -cp "wiremock-standalone.jar:com.ibm.mq.jakarta.client.jar" ... OR
    3. If your queue manager is IBM MQ 9.3+ on Linux/Windows, switch your URI to 'amqp://mqserver:5672/DEV.QUEUE.1' to use the built-in zero-dependency driver.
    ```
- **Virtual Thread Concurrency Guard**:
  - To prevent carrier thread pinning caused by `com.ibm.mq`'s internal `synchronized` locks, `wiremock-extension-ibmmq` executes all blocking MQ calls inside a dedicated, bounded platform thread pool (`wiremock-ibmmq-worker-%d`), never on carrier virtual threads.

---

## 6. Concrete Stub Metadata Specification (`metadata.async`)

In accordance with the async stub specification ([#89](https://github.com/mattiasgreen/wiremock-extensions/issues/89)), async configurations are co-located within standard WireMock stub JSON files inside the `metadata.async` block.

### Example A: IBM MQ via Built-in AMQP 1.0 (Tier 1)

This stub requires **zero external JARs** and connects out of the box to any IBM MQ 9.3+ queue manager with an AMQP channel:

```json
{
  "id": "7f1e2a3b-4c5d-6e7f-8a9b-0c1d2e3f4a5b",
  "name": "IBM MQ Payment Request Mock (AMQP 1.0)",
  "request": {
    "url": "/__async/stub/payment-inbound",
    "method": "ANY"
  },
  "metadata": {
    "async": {
      "broker": "amqp://mq-host.corp.internal:5672",
      "destination": "DEV.PAYMENT.IN",
      "destinationType": "QUEUE",
      "mep": "REQUEST_REPLY",
      "inbound": {
        "headers": {
          "correlation-id": {
            "matches": "PAY-[0-9]{8}"
          }
        },
        "bodyPatterns": [
          {
            "matchesJsonPath": "$.payment.amount"
          }
        ]
      },
      "outbound": {
        "destination": "${inbound.reply-to}",
        "headers": {
          "correlation-id": "${inbound.correlation-id}",
          "content-type": "application/json"
        },
        "body": "{\"status\": \"APPROVED\", \"transactionId\": \"TX-998822\"}"
      }
    }
  }
}
```

### Example B: IBM MQ via Native SVRCONN Driver (Tier 2)

This stub targets a mainframe **z/OS queue manager** or enterprise installation requiring server-connection channels and server-side selectors:

```json
{
  "id": "8a9b0c1d-2e3f-4a5b-6c7d-8e9f0a1b2c3d",
  "name": "IBM MQ Mainframe Core Banking Stub (Native SVRCONN)",
  "request": {
    "url": "/__async/stub/mainframe-core-inquiry",
    "method": "ANY"
  },
  "metadata": {
    "async": {
      "broker": "ibmmq://mainframe-mq.corp.internal:1414/QM_ZOS_01?channel=DEV.APP.SVRCONN",
      "destination": "BANKING.INQUIRY.REQ",
      "destinationType": "QUEUE",
      "mep": "REQUEST_REPLY",
      "options": {
        "selector": "JMSCorrelationID LIKE 'INQ%' AND SourceSystem = 'PORTAL'",
        "transportType": "CLIENT"
      },
      "inbound": {
        "headers": {
          "JMSCorrelationID": {
            "matches": "INQ-[0-9]+"
          }
        }
      },
      "outbound": {
        "destination": "${inbound.JMSReplyTo}",
        "headers": {
          "JMSCorrelationID": "${inbound.JMSCorrelationID}",
          "Encoding": 546,
          "CodedCharSetId": 1208
        },
        "body": "{\"balance\": 15420.50, \"currency\": \"SEK\"}"
      }
    }
  }
}
```

---

## 7. Citations & Primary Sources

1. **IBM Documentation: AMQP Channel Overview**:
   [IBM MQ 9.3 Documentation - Developing AMQP client applications](https://www.ibm.com/docs/en/ibm-mq/9.3?topic=developing-amqp-client-applications)
2. **IBM Documentation: Point-to-Point Queue Support over AMQP**:
   [IBM MQ 9.3 Documentation - Point-to-point messaging with Apache Qpid JMS](https://www.ibm.com/docs/en/ibm-mq/9.3?topic=amqp-point-point-messaging-apache-qpid-jms)
3. **IBM Documentation: AMQP Channel Limitations**:
   [IBM MQ 9.4 Documentation - AMQP channel differences and unsupported features](https://www.ibm.com/docs/en/ibm-mq/9.4?topic=amqp-channel-differences)
4. **IBM Documentation: AMQP Link Addressing and Capabilities**:
   [IBM MQ 9.3 Documentation - Creating AMQP links to IBM MQ objects](https://www.ibm.com/docs/en/ibm-mq/9.3?topic=amqp-creating-links-objects)
5. **IBM Documentation: MQ Classes for Jakarta Messaging on Maven Central**:
   [IBM MQ 9.4 Documentation - Obtaining IBM MQ classes for Java and JMS from Maven](https://www.ibm.com/docs/en/ibm-mq/9.4?topic=jms-obtaining-mq-classes-from-maven-central)
6. **OASIS AMQP 1.0 Specification**:
   [OASIS Standard: Advanced Message Queuing Protocol (AMQP) Version 1.0](http://docs.oasis-open.org/amqp/core/v1.0/os/amqp-core-overview-v1.0-os.html)
7. **IBM Terms: Redistributable Components Licensing**:
   [IBM Program License Agreement - Components Not Used for Establishing Required Entitlements](https://www.ibm.com/support/customer/csol/terms/?id=L-ASCR-N...)
8. **OpenJDK JEP 491 & Java 21 Loom Monitor Pinning**:
   [JEP 491: Synchronize Virtual Threads without Pinning](https://openjdk.org/jeps/491)
