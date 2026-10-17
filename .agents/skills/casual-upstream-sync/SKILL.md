---
name: casual-upstream-sync
description: >-
  Use this skill when modifying protocol classes under se.laz.casual.* in wiremock-extension-casual
  or preparing upstream contributions to casualcore/casual-java.
---

# Casual Protocol Upstream Synchronization Runbook

`wiremock-extension-casual` maintains strict architectural separation between **WireMock adapter glue** (`com.github.mattiasgreen.wiremock.casual.*`) and **reusable Casual Java SE protocol components** (`se.laz.casual.*`) slated for upstream contribution to [`casualcore/casual-java`](https://github.com/casualcore/casual-java).

## 1. Package Isolation Contract
Classes inside `se.laz.casual.*` must **NEVER** import `com.github.tomakehurst.wiremock.*` or `com.github.mattiasgreen.wiremock.*`:

| Local Package / Class | Target Upstream Module in `casual-java` | Responsibility |
| :--- | :--- | :--- |
| `se.laz.casual.network.protocol.decoding.CasualFrameDecoder` | `casual-network` / `casual-network-protocol` | Netty `ByteToMessageDecoder` assembling 32-byte header + payload chunks into `CasualNWMessage<?>`. |
| `se.laz.casual.client.CasualTcpClient` | `casual-client-standalone` | Pure Java SE TCP client (`domainConnect`, `discover`, `tpcall`, `prepare`, `commit`, `rollback`). |
| `se.laz.casual.server.CasualServer` & handlers | `casual-server-standalone` | In-process Netty inbound server dispatching `CasualServiceHandler`, `CasualDiscoveryHandler`, `CasualTransactionHandler`. |
| `se.laz.casual.api.buffer.CasualBufferUtils` | `casual-api` | Zero-boilerplate `ServiceBuffer` / `JsonBuffer` byte and UTF-8 string extraction helpers. |

## 2. Verification Before & After Touching `se.laz.casual.*`
1. Verify zero forbidden imports:
   Ensure no `wiremock` package references exist in `wiremock-extension-casual/src/main/java/se/laz/casual/`.
2. Run fast gateway unit tests (< 5s):
   `./gradlew :wiremock-extension-casual:test -q --console=plain`
3. Run 3-node 2PC/XA MitM integration harness (~15s):
   `./gradlew :wiremock-extension-casual:quarkusIntegrationTest -q --console=plain`
4. Update [`wiremock-extension-casual/TODO.md`](../../../wiremock-extension-casual/TODO.md) if upstream readiness status changes.
