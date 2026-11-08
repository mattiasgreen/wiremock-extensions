# AGENTS.md: Developer Guidelines for `wiremock-extension-async`

Guidelines and architectural topology for `wiremock-extension-async` (Beta).

---

## 1. Module Responsibility & Invariants

`wiremock-extension-async` provides asynchronous event-driven and queue-based mocking for WireMock Standalone (JDK 21):

1. **Beta Notice**: All public classes, models, and endpoints must reflect `@Beta` / experimental status in Javadocs and README.
2. **Zero Standalone JVM Bloat**: Never embed external broker daemons (ZooKeeper, KRaft, RabbitMQ, Artemis). Operates strictly via in-process Netty TCP codecs or virtual-thread client bridges.
3. **Virtual-Thread Native**: Concurrency must use `Thread.ofVirtual().start(...)` for message dispatching, templating, and socket mediation. Never hold locks across blocking socket operations.
4. **Token Hygiene & Inner Loop**:
   - Inner Loop Test: `./gradlew :wiremock-extension-async:test --tests "ClassName.methodName" -q --console=plain`
   - Pre-Commit Gate: `./gradlew :wiremock-extension-async:spotbugsMain :wiremock-extension-async:test -q --console=plain`
