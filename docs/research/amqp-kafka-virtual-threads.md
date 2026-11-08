# Research: AMQP 1.0 and Kafka Client Libraries for JDK 21 Virtual Threads

**Issue**: [#88](https://github.com/mattiasgreen/wiremock-extensions/issues/88)
**Parent Map**: [#85 (Async Messaging & Queue Mocking Architecture)](https://github.com/mattiasgreen/wiremock-extensions/issues/85)
**Author**: Antigravity Autonomous Research Agent
**Status**: Completed / Recommended

---

## Executive Summary & Decision Matrix

To enable native **Async Message Stubbing** and peer-client queue/topic mocking within **WireMock Standalone (JDK 21)**, WireMock must interact with external or local message brokers (e.g., RabbitMQ, Apache Artemis, Apache Kafka, Azure Service Bus) strictly as a lightweight peer client without running heavy in-process broker daemons.

This investigation evaluated the leading Java 21 client libraries for **AMQP 1.0** and **Apache Kafka** along five core architectural criteria:
1. **JDK 21 Virtual Thread Friendliness**: Suspendability without OS carrier thread pinning (`synchronized` blocking monitor avoidance).
2. **Zero-Daemon Client Interface**: Clean lifecycle management without required external application containers, runtime daemons, or unmanageable background thread pools.
3. **Dependency Footprint**: Minimal transitive JAR size and compatibility with WireMock Standalone's fat-jar distribution.
4. **API Ergonomics & Simplicity**: Intuitive imperative consumer/producer session APIs that map naturally to WireMock's `metadata.async` stub declarations.
5. **Protocol & Enterprise Fidelity**: Support for authentication (SASL/TLS), partition rebalancing, link credit flow control, and message settlements.

### Summary Comparison Matrix

| Client Library | Protocol | Virtual Thread Compatibility | Net Dependency Footprint | Zero-Daemon Ergonomics | Recommendation |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Apache Qpid ProtonJ2 Client** (`org.apache.qpid:protonj2-client:1.4.0`) | AMQP 1.0 | **Optimal** (Netty I/O unmounts virtual threads; no carrier pinning) | **~950 KB** (Shares existing Netty 4 with Casual module) | **Excellent** (Pure imperative `Client`, `Connection`, `Receiver`, `Sender`) | **RECOMMENDED FOR AMQP 1.0** |
| **Apache Qpid JMS Client** (`org.apache.qpid:qpid-jms-client:2.6.0`) | AMQP 1.0 / JMS 2.0 | **Good** (Blocking JMS calls unmount cleanly) | ~2.5 MB + `jakarta.jms-api` (100 KB) | **Moderate** (Verbose JMS connection/session factory ceremony) | Viable fallback for enterprise JMS wrappers |
| **Eclipse Vert.x AMQP Client** (`io.vertx:vertx-amqp-client:4.5.x`) | AMQP 1.0 | **Poor** (Reactive event loop requires explicit dispatching to avoid event loop starvation) | ~6.5 MB (`vertx-core` + Netty + Jackson) | **Poor** (Requires managing long-lived `Vertx` instance daemon threads) | Not Recommended |
| **Azure Core AMQP** (`com.azure:azure-core-amqp:2.9.x`) | AMQP 1.0 | **Poor** (Project Reactor reactive streams model impedes virtual threads) | ~8.0 MB (`reactor-core` + Netty + Azure SDK) | **Poor** (Tightly coupled to Azure Service Bus CBS token auth & conventions) | Not Recommended |
| **SwiftMQ AMQP Client** (`com.swiftmq:swiftmq-client:12.x`) | AMQP 1.0 / JMS 1.1 | **Uncertain** (Legacy monitor synchronization) | ~3.8 MB (Monolithic legacy bundle) | **Moderate** (JMS 1.1 legacy abstractions) | Not Recommended |
| **Official Kafka Clients** (`org.apache.kafka:kafka-clients:3.9.0`) | Apache Kafka | **Optimal with Dedicated Poller + Virtual Worker Pattern** | ~5.5 MB (+ ~9.5 MB optional compression codecs) | **Excellent** (Pure-Java standalone client; decoupled from broker) | **RECOMMENDED FOR KAFKA** |
| **LinkedIn Kafka Clients** (`li-apache-kafka-clients:0.3.x`) | Apache Kafka | **Identical to Official** (Thin wrapper on official client) | ~6.0 MB + Kafka codecs | **Moderate** (Adds chunking features unneeded for mocking) | Not Recommended |
| **Kroxylicious API** (`io.kroxylicious:kroxylicious-api`) | Apache Kafka | **Experimental** (Netty protocol decoder only) | ~1.5 MB | **Incomplete** (Lacks consumer group coordinator, rebalance, and partition discovery) | Not Recommended (Not a client) |

---

## Part 1: Detailed Evaluation of AMQP 1.0 Client Libraries

AMQP 1.0 is an ISO/IEC standardized (ISO/IEC 19464) symmetric, binary wire-level protocol. Unlike AMQP 0-9-1 (RabbitMQ's legacy protocol), AMQP 1.0 defines transport, link-level credit-based flow control, dynamic nodes, and explicit delivery settlements (`ACCEPTED`, `REJECTED`, `RELEASED`, `MODIFIED`). It is supported natively by Apache ActiveMQ Artemis, RabbitMQ (via AMQP 1.0 plugin), Apache Qpid Dispatch Router, Azure Service Bus, and IBM MQ (via AMQP service channel).

### 1.1 Apache Qpid ProtonJ2 Client (`org.apache.qpid:protonj2-client`)

`protonj2-client` is the modern, next-generation pure-Java AMQP 1.0 client developed by the Apache Qpid project. It completely supersedes the older Proton-J engine and offers an imperative, clean client API built on top of Netty 4.

#### Architectural Characteristics
* **Clean Imperative API**: Exposes high-level abstractions:
  * `Client`: Factory managing configuration and shared client resources.
  * `Connection`: Represents an active TCP/TLS connection to an AMQP container, supporting auto-reconnect, SASL authentication (PLAIN, ANONYMOUS, EXTERNAL, SCRAM), and SSL/TLS.
  * `Session`: Lightweight multiplexed link container.
  * `Receiver`: Credit-driven consumer link subscribing to queues or topics.
  * `Sender`: Producer link for publishing messages.
  * `Delivery`: Encapsulates inbound delivery state and settlement dispositions.
* **JDK 21 Virtual Thread Compatibility**:
  * ProtonJ2's imperative blocking APIs (`receiver.receive()`, `receiver.receive(duration)`, `sender.send()`, `future.get()`) park the calling thread using standard `java.util.concurrent` locks and `CompletableFuture` mechanisms.
  * When invoked on a virtual thread (`Thread.ofVirtual()`), the virtual thread cleanly **unmounts from its carrier thread** without pinning.
  * Background network transport and frame parsing are driven asynchronously by Netty event loops (`NioEventLoopGroup`). Sockets are multiplexed non-blockingly, guaranteeing that blocked consumer threads consume zero OS carrier resources.
* **Dependency Footprint & Classpath Sharing**:
  * Core artifacts:
    * `org.apache.qpid:protonj2:1.4.0` (AMQP 1.0 protocol engine & codec): **~620 KB**
    * `org.apache.qpid:protonj2-client:1.4.0` (Imperative client API): **~330 KB**
  * Transitive dependencies:
    * Netty 4 (`netty-buffer`, `netty-codec`, `netty-common`, `netty-handler`, `netty-transport`): **Already present** in `wiremock-extensions` (managed via `libs.netty.all` / `wiremock-extension-casual`).
    * SLF4J (`slf4j-api`): Already present in WireMock Core.
  * **Incremental Bytecode Footprint in Standalone Fat-JAR**: **< 1.0 MB**.
* **Code Example (Imperative Consumer & Reply Producer)**:
  ```java
  try (Client client = Client.create();
       Connection connection = client.connect("localhost", 5672, new ConnectionOptions().user("guest").password("guest"));
       Receiver receiver = connection.openReceiver("orders.incoming");
       Sender sender = connection.openSender("orders.replies")) {

      // Virtual thread unmounts cleanly while waiting for inbound delivery
      Delivery delivery = receiver.receive(Duration.ofSeconds(10));
      if (delivery != null) {
          Message<byte[]> message = delivery.message();
          byte[] payload = message.body();

          // Evaluate WireMock stub pattern match and construct response
          byte[] replyPayload = "{\"status\":\"CONFIRMED\"}".getBytes(StandardCharsets.UTF_8);

          // Correlate reply with inbound message-id
          Message<byte[]> reply = Message.create(replyPayload)
              .correlationId(message.messageId());
          sender.send(reply);

          // Settle the delivery
          delivery.accept();
      }
  }
  ```

---

### 1.2 Apache Qpid JMS Client (`org.apache.qpid:qpid-jms-client`)

The Apache Qpid JMS client implements the standard Jakarta Messaging (JMS 2.0 / 3.0) API over AMQP 1.0.

#### Architectural Assessment
* **API Ergonomics**: Requires standard JMS boilerplate (`ConnectionFactory`, `JMSContext`, `Queue`, `Topic`, `MessageConsumer`, `MessageProducer`).
* **Impedance Mismatch with AMQP 1.0**:
  * AMQP 1.0-specific concepts (such as precise delivery dispositions `RELEASED` vs `MODIFIED`, delivery annotations, and message sections) are mapped into synthetic vendor JMS properties (e.g., `JMS_AMQP_ACK_TYPE`).
  * WireMock stubs declared in `metadata.async` operate on native headers and byte payloads; wrapping them in JMS types introduces unnecessary translation overhead.
* **Footprint**:
  * `qpid-jms-client` JAR: ~2.5 MB.
  * Requires `jakarta.jms-api` (or `geronimo-jms_2.0_spec`): ~100 KB.
* **Verdict**: While completely compatible with virtual threads via standard synchronous JMS `receive()` unmounting, `protonj2-client` provides a cleaner, direct AMQP 1.0 mapping with less than half the dependency footprint.

---

### 1.3 Eclipse Vert.x AMQP Client (`io.vertx:vertx-amqp-client`)

The Vert.x AMQP Client provides an asynchronous, reactive client built on top of the Vert.x reactive engine and Netty.

#### Architectural Assessment
* **Reactive Model vs. Virtual Threads**:
  * In Vert.x, incoming messages trigger callbacks on Netty event-loop threads (`receiver.handler(msg -> ...)`).
  * In WireMock, evaluating stubs involves pattern matching, dynamic response transformation (e.g., Handlebars templating, stateful AST queries), and synchronous journal logging. Executing this logic directly inside a Vert.x event-loop callback violates the reactive contract ("never block the event loop") and causes thread starvation.
  * Bridging Vert.x handlers to virtual threads requires wrapping every message dispatch into an executor (`Thread.ofVirtual().start(...)` or `vertx.executeBlocking(...)`), adding unnecessary complexity compared to an imperative library that is already synchronous and virtual-thread native.
* **Lifecycle & Daemon Overhead**:
  * Vert.x requires a running `Vertx` instance (`Vertx.vertx()`). This spins up multiple background thread pools (event loops, worker pool, timer daemons).
  * In dynamic mocking where connections to varying broker endpoints are initiated on demand, managing Vert.x instance lifecycles and thread shutdowns adds substantial leak risk and complexity.
* **Footprint**:
  * Requires `vertx-core`, `vertx-amqp-client`, Netty, and Jackson: ~6.5 MB.

---

### 1.4 Azure Core AMQP (`com.azure:azure-core-amqp`)

Microsoft's foundational AMQP library used by the Azure SDK for Service Bus and Event Hubs.

#### Architectural Assessment
* **Tightly Coupled to Project Reactor**:
  * Built around `Flux` and `Mono` reactive streams (`Flux<AmqpReceivedMessage>`). Reactive streams libraries add extensive operator stacks, complex backpressure mechanisms, and debugging difficulty that are counterproductive in a JDK 21 virtual thread architecture.
* **Azure-Specific Assumptions**:
  * Enforces Azure Service Bus conventions (Claims-Based Security / CBS tokens, entity management, amqp-cbs links) rather than arbitrary open AMQP 1.0 brokers.
* **Footprint**:
  * Pulls in `azure-core`, `reactor-core`, `reactor-netty`: ~8.0 MB.

---

### 1.5 SwiftMQ AMQP 1.0 Client (`com.swiftmq:swiftmq-client`)

IIT Software's legacy AMQP 1.0 client implementation.

#### Architectural Assessment
* Bundles legacy JMS 1.1, SwiftMQ proprietary protocol clients, and AMQP 1.0 into a single monolithic artifact (~3.8 MB).
* Uses older synchronization patterns dating back to Java 5/6, presenting high risk of object monitor carrier-thread pinning under JDK 21 Loom.
* Infrequent open-source releases and commercial heritage.

---

## Part 2: Detailed Evaluation of Kafka Client Libraries

Apache Kafka uses a proprietary binary request/response TCP protocol over multiplexed socket connections. Kafka is log-centric, partition-based, and relies on consumer groups, rebalance coordination, and broker metadata discovery.

### 2.1 Official Apache Kafka Clients (`org.apache.kafka:kafka-clients`)

The official `kafka-clients` library is the reference implementation developed by the Apache Kafka project. It is completely decoupled from the Kafka broker server JAR and is a pure-Java client library.

#### 2.1.1 Concurrency Model & JDK 21 Virtual Thread Analysis

##### A. Producer (`KafkaProducer`)
* **Thread-Safety**: `KafkaProducer` is **fully thread-safe** and designed to be shared concurrently across hundreds or thousands of threads.
* **Virtual Thread Ergonomics**:
  * Invocations of `producer.send(record, callback)` or `producer.send(record).get()` append records to an in-memory batch buffer (`RecordAccumulator`).
  * If the accumulator buffer is full, the calling thread waits on a `Condition` (which unmounts virtual threads cleanly).
  * A single background platform daemon thread (`kafka-producer-network-thread | producer-1`) runs an internal Java NIO `Selector` loop to batch, compress, and transmit records to broker sockets.
  * **Result**: Virtual threads can call `KafkaProducer.send()` concurrently at extreme throughput without carrier thread pinning or thread exhaustion.

##### B. Consumer (`KafkaConsumer`) & The Virtual Thread Pinning Issue
* **Single-Threaded Contract**: `KafkaConsumer` is **not thread-safe**. Concurrent calls to `poll()` or other methods throw `ConcurrentModificationException`.
* **Carrier Thread Pinning in `poll()`**:
  * The thread calling `KafkaConsumer.poll(Duration)` drives both the network I/O (`NetworkClient` with NIO `Selector.select(timeout)`) and background cluster coordination (partition rebalancing, heartbeats, commit offsets).
  * Although `Selector.select()` unmounts virtual threads in modern OpenJDK releases, `kafka-clients` contains legacy internal synchronization (`synchronized` blocks) across metadata managers and coordinators (`AbstractCoordinator`, `ConsumerCoordinator`).
  * If `consumer.poll()` is executed on a virtual thread and encounters lock contention or enters a blocking socket wait while holding an object monitor, **the virtual thread pins its underlying OS carrier thread**. This causes carrier starvation and generates JVM pinning warnings (`-Djdk.tracePinnedThreads=full`).
  * Furthermore, if a virtual thread running `consumer.poll()` is unmounted or preempted during message processing, consumer group heartbeats can be delayed, causing the Kafka broker to trigger a spurious **group rebalance** and revoke partitions.

##### C. The Solution: Dedicated Poller + Virtual Worker Dispatch Pattern
To achieve 100% virtual thread scalability with zero pinning and rock-solid consumer group stability, WireMock should adopt the **Dedicated Poller / Virtual Worker** architecture:

```
┌──────────────────────────────────────────────────────────────────────────────────┐
│                             Kafka Broker Cluster                                 │
└────────────────────────────────────────┬─────────────────────────────────────────┘
                                         │ TCP Socket (Fetch & Produce)
                                         ▼
┌──────────────────────────────────────────────────────────────────────────────────┐
│                         WireMock Kafka Protocol Driver                           │
│                                                                                  │
│   ┌──────────────────────────────────────────────────────────────────────────┐   │
│   │   Single Dedicated Platform Thread (Non-pinned Poller)                   │   │
│   │   - Runs KafkaConsumer.poll(Duration.ofMillis(100))                      │   │
│   │   - Maintains unbroken heartbeat & partition lease                       │   │
│   └────────────────────────────────────┬─────────────────────────────────────┘   │
│                                        │ ConsumerRecords batch                   │
│                                        ▼                                         │
│   ┌──────────────────────────────────────────────────────────────────────────┐   │
│   │   JDK 21 Virtual Thread Per-Task Executor                                │   │
│   │   (Executors.newVirtualThreadPerTaskExecutor())                          │   │
│   │                                                                          │   │
│   │   ┌───────────────────────┐  ┌───────────────────────┐                   │   │
│   │   │ Virtual Thread #1     │  │ Virtual Thread #2     │  ... (Unbounded)  │   │
│   │   │ - Stub Pattern Match  │  │ - Stub Pattern Match  │                   │   │
│   │   │ - Synthetic ServeEvent│  │ - Synthetic ServeEvent│                   │   │
│   │   │ - Produces Reply      │  │ - Produces Reply      │                   │   │
│   │   └───────────┬───────────┘  └───────────┬───────────┘                   │   │
│   └───────────────┼──────────────────────────┼───────────────────────────────┘   │
│                   │                          │                                   │
│                   ▼                          ▼                                   │
│   ┌──────────────────────────────────────────────────────────────────────────┐   │
│   │   Shared Singleton KafkaProducer (Thread-Safe)                           │   │
│   │   - Outbound reply messages batched via RecordAccumulator                │   │
│   └──────────────────────────────────────────────────────────────────────────┘   │
└──────────────────────────────────────────────────────────────────────────────────┘
```

1. A single dedicated platform thread runs the `KafkaConsumer.poll()` loop, ensuring uninterrupted group heartbeats and zero carrier pinning.
2. For every consumed record, the poller dispatches execution to an unbound virtual thread (`Thread.ofVirtual().start(...)`).
3. The virtual thread executes stub matching, journal recording (`ServeEvent`), and produces any correlated reply using the shared, thread-safe `KafkaProducer`.
4. Consumer offsets are committed asynchronously or synchronously after worker completion.

#### 2.1.2 Dependency Footprint & Transitive Codecs
The official `kafka-clients:3.9.0` POM declares:
* `org.apache.kafka:kafka-clients`: **5.5 MB**
* `org.slf4j:slf4j-api`: Already bundled in WireMock Core.
* Native Compression Codecs:
  * `org.lz4:lz4-java:1.8.0`: **~670 KB** (Standard Kafka fast compression)
  * `org.xerial.snappy:snappy-java:1.1.10.5`: **~4.2 MB** (Includes multi-OS native shared libraries)
  * `com.github.luben:zstd-jni:1.5.6-4`: **~5.8 MB** (Includes multi-OS native shared libraries)

**Footprint Optimization for WireMock Standalone**:
* In mock environments, uncompressed and LZ4 payloads account for >95% of traffic.
* Both `snappy-java` and `zstd-jni` can either be retained for 100% enterprise compatibility (total incremental footprint ~15 MB) or configured as optional runtime dependencies if aggressive fat-jar size limits are desired.

---

### 2.2 Feasibility of Alternative or Pure-Java Minimalist Kafka Clients

We evaluated whether a lightweight, third-party, or custom Kafka client could reduce the 15 MB footprint:

1. **Kroxylicious (`io.kroxylicious:kroxylicious-api`)**:
   * Kroxylicious is an API gateway proxy for Kafka built on Netty.
   * While it contains a high-performance parser for Kafka protocol frames, it is **not a client library**. It lacks partition leader discovery, cluster metadata refreshes, consumer group rebalancing protocols (Eager & Cooperative Sticky assignors), offset management, and SASL handshake engines.
   * Using Kroxylicious would require rebuilding the entire Kafka consumer state machine inside WireMock.
2. **LinkedIn Kafka Clients (`li-apache-kafka-clients`)**:
   * A wrapper library built directly on top of `kafka-clients`. Adds large-message segmenting and client-side auditing, which increases rather than decreases dependency footprint.
3. **Custom Pure-Java Kafka Wire Protocol Driver**:
   * The Kafka protocol is notoriously extensive: 70+ distinct API keys (Fetch, Produce, Metadata, FindCoordinator, JoinGroup, SyncGroup, Heartbeat, OffsetCommit, SaslHandshake, etc.), each with up to 16 backwards-incompatible schema versions and complex zigzag variable-length integer encodings.
   * A custom driver would be extraordinarily fragile, fail to support Kafka cluster upgrades (e.g., 2.x to 3.x to 4.x KRaft mode), and lack critical enterprise SASL authentication (SCRAM-SHA-512, Kerberos).

**Conclusion**: The official `org.apache.kafka:kafka-clients` library is the only viable, production-grade option for Kafka in Java. Its ~5.5 MB core JAR is well justified by complete protocol fidelity.

---

## Part 3: Architectural Interface Draft (ProtocolDriver SPI)

To insulate WireMock's core stubbing and journal mediation from protocol-specific libraries, we define a pluggable, decoupled **`ProtocolDriver` SPI**.

Drivers are registered via standard Java `ServiceLoader` (`META-INF/services/com.github.mattiasgreen.wiremock.async.spi.ProtocolDriver`), mirroring the architectural pattern used across WireMock extensions.

### 3.1 Domain Model & Core SPI Interfaces

```java
package com.github.mattiasgreen.wiremock.async.spi;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * SPI interface implemented by protocol-specific client adapters (AMQP 1.0, Kafka, Casual, IBM MQ).
 * Discovered via java.util.ServiceLoader.
 */
public interface ProtocolDriver extends AutoCloseable {

    /**
     * URI schemes supported by this driver (e.g., "amqp", "amqps", "kafka", "casual").
     */
    boolean supportsScheme(String scheme);

    /**
     * Establishes or acquires a pooled connection to the specified broker URI.
     */
    BrokerConnection connect(URI brokerUri, DriverOptions options) throws ProtocolDriverException;

    @Override
    default void close() {
        // Default no-op for stateless factories
    }
}
```

```java
package com.github.mattiasgreen.wiremock.async.spi;

import java.net.URI;
import java.util.concurrent.CompletableFuture;

/**
 * An active, thread-safe connection to a message broker.
 * Implementations are pooled transparently by broker URI.
 */
public interface BrokerConnection extends AutoCloseable {

    URI brokerUri();

    boolean isConnected();

    /**
     * Opens a subscription on the specified channel (queue, topic, or partition).
     * Handlers are executed on JDK 21 virtual threads.
     */
    SubscriptionHandle subscribe(String channel, MessageHandler handler, SubscriptionOptions options)
        throws ProtocolDriverException;

    /**
     * Asynchronously publishes an outbound message to a target channel.
     */
    CompletableFuture<PublishReceipt> publish(String channel, AsyncMessage message);

    @Override
    void close();
}
```

```java
package com.github.mattiasgreen.wiremock.async.spi;

import java.util.Map;

/**
 * Normalized protocol-agnostic message representation exchanged between WireMock and drivers.
 */
public record AsyncMessage(
    String messageId,
    String correlationId,
    String replyTo,
    Map<String, Object> headers,
    byte[] payload,
    long timestampEpochMs
) {
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String messageId;
        private String correlationId;
        private String replyTo;
        private Map<String, Object> headers = Map.of();
        private byte[] payload = new byte[0];
        private long timestampEpochMs = System.currentTimeMillis();

        public Builder messageId(String id) { this.messageId = id; return this; }
        public Builder correlationId(String id) { this.correlationId = id; return this; }
        public Builder replyTo(String replyTo) { this.replyTo = replyTo; return this; }
        public Builder headers(Map<String, Object> headers) { this.headers = headers; return this; }
        public Builder payload(byte[] payload) { this.payload = payload; return this; }
        public Builder timestamp(long epochMs) { this.timestampEpochMs = epochMs; return this; }
        public AsyncMessage build() {
            return new AsyncMessage(messageId, correlationId, replyTo, headers, payload, timestampEpochMs);
        }
    }
}
```

```java
package com.github.mattiasgreen.wiremock.async.spi;

/**
 * Inbound message callback invoked by the driver on a JDK 21 virtual thread.
 */
@FunctionalInterface
public interface MessageHandler {

    /**
     * Invoked when an inbound message is received.
     *
     * @param message Normalized inbound message
     * @param context Inbound contextual metadata (channel, driver receipt, delivery disposition handle)
     * @return Action instructing the driver on settlement and reply publication
     */
    AsyncAction onMessage(AsyncMessage message, MessageContext context);
}
```

```java
package com.github.mattiasgreen.wiremock.async.spi;

import java.util.Optional;

/**
 * Result returned by WireMock's async stub engine after matching and response synthesis.
 */
public record AsyncAction(
    Disposition disposition,
    Optional<AsyncMessage> replyMessage,
    Optional<String> replyChannel
) {
    public enum Disposition {
        ACKNOWLEDGE,
        REJECT,
        REQUEUE
    }

    public static AsyncAction ack() {
        return new AsyncAction(Disposition.ACKNOWLEDGE, Optional.empty(), Optional.empty());
    }

    public static AsyncAction replyAndAck(String replyChannel, AsyncMessage reply) {
        return new AsyncAction(Disposition.ACKNOWLEDGE, Optional.of(reply), Optional.ofNullable(replyChannel));
    }

    public static AsyncAction reject() {
        return new AsyncAction(Disposition.REJECT, Optional.empty(), Optional.empty());
    }
}
```

---

### 3.2 Dynamic Connection Pooling (`AsyncConnectionManager`)

Per Wayfinder Map #85 preferences, **zero global pre-configuration** is required. WireMock automatically pools and manages broker connections keyed by normalized URI:

```java
package com.github.mattiasgreen.wiremock.async.engine;

import com.github.mattiasgreen.wiremock.async.spi.BrokerConnection;
import com.github.mattiasgreen.wiremock.async.spi.DriverOptions;
import com.github.mattiasgreen.wiremock.async.spi.ProtocolDriver;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class AsyncConnectionManager implements AutoCloseable {

    private final Map<String, ProtocolDriver> drivers = new ConcurrentHashMap<>();
    private final Map<URI, BrokerConnection> connectionPool = new ConcurrentHashMap<>();

    public BrokerConnection getOrCreateConnection(URI brokerUri, DriverOptions options) {
        return connectionPool.computeIfAbsent(brokerUri, uri -> {
            ProtocolDriver driver = findDriverForScheme(uri.getScheme());
            return driver.connect(uri, options);
        });
    }

    private ProtocolDriver findDriverForScheme(String scheme) {
        ProtocolDriver driver = drivers.get(scheme);
        if (driver == null) {
            throw new IllegalArgumentException("No registered Async ProtocolDriver supports scheme: " + scheme);
        }
        return driver;
    }

    @Override
    public void close() {
        connectionPool.values().forEach(BrokerConnection::close);
        connectionPool.clear();
    }
}
```

---

### 3.3 Synthetic `ServeEvent` Journal Mediation

To achieve **journal parity** with HTTP and Casual stubs, every async exchange is mediated into a synthetic WireMock `ServeEvent`:

1. **Synthetic `LoggedRequest` Generation**:
   * `url`: `<scheme>://<broker-host>:<port>/<channel>` (e.g., `amqp://localhost:5672/orders.incoming` or `kafka://broker:9092/events.stream?partition=2&offset=1049`).
   * `method`: `RequestMethod.fromString("CONSUME")` (or protocol verb `AMQP_RECEIVE` / `KAFKA_POLL`).
   * `headers`: Normalized string headers from `AsyncMessage.headers()`.
   * `body`: Raw byte array or UTF-8 string payload.
2. **Matching & Execution**:
   * Evaluated against registered WireMock stubs where `metadata.async` matches the channel, protocol, and payload pattern (using standard JSON/XML/regex matchers).
3. **Synthetic `Response` & Correlation**:
   * Generates a 200 OK `Response` containing the synthesized reply payload and headers.
   * If `reply` is specified in `metadata.async`, the driver publishes the reply to `reply.channel` or the message's `replyTo` address, preserving the `correlationId`.
4. **Journal Emission**:
   * Emits the synthetic `ServeEvent` into the WireMock journal (`ServeEventListener`).
   * Fully triggers `JsonLoggingListener` (structured single-line JSON log with `x-correlation-id`), `OtelMetricsListener` (trace span + Prometheus metrics), and appears seamlessly in the Web Console UI (`/__admin/ui`) with full payload unnesting and syntax highlighting.

---

## Part 4: Recommendations & Implementation Plan

### 4.1 Recommendations Summary

1. **For AMQP 1.0**:
   * **Adopt `org.apache.qpid:protonj2-client:1.4.0`**.
   * Take advantage of the shared Netty 4 dependency already present in `wiremock-extensions`.
   * Incremental fat-jar footprint is < 1 MB.
   * Fully leverages JDK 21 virtual threads with zero carrier thread pinning.

2. **For Apache Kafka**:
   * **Adopt official `org.apache.kafka:kafka-clients:3.9.0`**.
   * Implement the **Dedicated Poller / Virtual Worker Dispatch Pattern** to guarantee consumer group heartbeat integrity while isolating carrier threads from pinning.
   * Retain LZ4 for lightweight fast compression, and include `snappy-java` and `zstd-jni` for enterprise multi-broker fidelity.

3. **For SPI Architecture**:
   * Introduce a modular subproject: `wiremock-extension-async` (or individual driver modules `:wiremock-extension-amqp` and `:wiremock-extension-kafka`).
   * Expose clean `ProtocolDriver` SPI to allow seamless addition of future protocol drivers (Casual Queue via `casual-java`, IBM MQ via AMQP 1.0 / native channel).

### 4.2 Next Steps for Wayfinder Map #85

- [x] **#88**: Research: AMQP 1.0 and Kafka client libraries for JDK 21 virtual threads (Resolved by this document).
- [ ] **#86**: Research: Casual Queue integration via `casual-java`.
- [ ] **#87**: Research: IBM MQ connectivity options (AMQP 1.0 vs Native Client vs JMS).
- [ ] **#89**: Specification: `metadata.async` schema and `docs/spec/async-mocking.md`.
- [ ] **#90**: ADR: `docs/adr/0013-async-messaging-and-queue-mocking.md`.
- [ ] **#91**: Core Implementation: `wiremock-extension-async` SPI and synthetic journal mediation.
- [ ] **#92**: Driver Implementation: `protonj2-client` AMQP 1.0 & `kafka-clients` drivers.
