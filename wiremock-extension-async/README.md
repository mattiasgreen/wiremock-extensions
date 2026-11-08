# WireMock Asynchronous Messaging & Queue Mocking Extension (`wiremock-extension-async`)

> [!IMPORTANT]
> **Beta Functionality**: Asynchronous messaging and queue mocking in WireMock is currently in active **Beta**. The stub schemas under `metadata.async`, in-process Netty Kafka sinks, HTTP callback trigger listeners, and virtual-thread queue bridges are fully implemented and verified, but public SPI interfaces and metadata DSL properties may undergo refinement based on enterprise feedback.

---

## 1. Overview & Core Philosophy

`wiremock-extension-async` brings WireMock's core capabilities—stub matching, templated response synthesis, fault injection, the Request Journal, and the Web Console UI—to event-driven enterprise messaging:

- **Zero Standalone JVM Bloat**: WireMock never embeds heavyweight broker daemons (ZooKeeper, KRaft, RabbitMQ, Artemis). It operates strictly as an ultra-lightweight in-memory Netty wire sink or virtual-thread client bridge.
- **Zero Production Code Changes**: Systems Under Test interact with their standard queue and topic names. WireMock achieves isolation and hybrid integration via transparent connection routing and queue bridging.
- **Unified Stub & Journal Surfaces**: Async stubs live as first-class WireMock stubs in `mappings/` alongside HTTP and Casual definitions. Inbound and reply messages mediate directly into WireMock's core `ServeEvent` journal pipeline (`CONSUME /__async/<protocol>/<channel>`).
- **Virtual-Thread Native**: Socket I/O, message dispatching, and templating execute on Java 21 virtual threads, preventing carrier-thread starvation.

---

## 2. Supported Protocols & Topologies

| Protocol | Engine / Transport | Integration Topology | Primary Use Case |
| :--- | :--- | :--- | :--- |
| **Apache Kafka** | In-Process Netty Codec (`ApiVersions 18`, `Metadata 3`, `InitProducerId 22`, `Produce 0`) | In-Process Wire Sink (Port 9092 or dynamic) | Producer fire-and-forget ingestion, record batch CRC32C validation, synthetic journaling |
| **AMQP 1.0** | Non-blocking frame mediation (`X-Async-Protocol: amqp`) | Queue Bridge / Client Link | ActiveMQ Artemis, RabbitMQ (AMQP 1.0), Azure Service Bus, IBM MQ 9.3+ LTS |
| **Casual Queue** | In-process Netty XATMI Gateway (`ENQUEUE 6100`, `DEQUEUE 6200`) | `wiremock-extension-casual` seam | 2PC/XA transactional queue buffer mocking |
| **HTTP-to-Queue Trigger** | WireMock `ServeEventListener` (`AsyncMessagePublishingListener`) | In-process Extension Point | REST POST 202/204 triggering asynchronous Kafka/AMQP/Casual callback events |

---

## 3. Stub DSL Specification (`metadata.async`)

### 3.1. Fire-and-Forget Sink (Kafka Producer Ingestion)
```json
{
  "name": "Order Created Event Sink",
  "request": {
    "method": "CONSUME",
    "urlPath": "/__async/kafka/orders.events",
    "bodyPatterns": [
      { "matchesJsonPath": "$.orderId" }
    ]
  },
  "response": {
    "status": 204,
    "headers": {
      "X-Async-Ack": "ack"
    }
  },
  "metadata": {
    "async": {
      "protocol": "kafka",
      "channel": "orders.events",
      "ackPolicy": "ack"
    }
  }
}
```

### 3.2. HTTP-to-Queue Asynchronous Callback Trigger
```json
{
  "name": "Submit Payment with Async Callback Event",
  "request": {
    "method": "POST",
    "url": "/api/v1/payments/submit"
  },
  "response": {
    "status": 202,
    "headers": {
      "Content-Type": "application/json"
    },
    "jsonBody": {
      "paymentId": "{{randomValue type='UUID'}}",
      "status": "ACCEPTED"
    },
    "transformers": ["response-template"]
  },
  "metadata": {
    "async": {
      "produce": [
        {
          "destination": "payments.settled.events",
          "protocol": "kafka",
          "key": "{{jsonPath response.body '$.paymentId'}}",
          "payload": "{\"paymentId\":\"{{jsonPath response.body '$.paymentId'}}\",\"status\":\"SETTLED\",\"amount\":99.50}",
          "delayMs": 50
        }
      ]
    }
  }
}
```

---

## 4. Admin API Endpoints

- `GET /__admin/async/status`: Returns active sinks, registered channel listeners, and consumed/produced message metrics.
- `POST /__admin/async/sinks/kafka`: Dynamically starts or reconfigures the in-process Netty Kafka wire sink.
- `POST /__admin/async/publish`: Injects a synthetic outbound message into a target destination for test harness orchestration.
