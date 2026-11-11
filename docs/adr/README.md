# Architecture Decision Records (ADRs)

Master index of architecture decisions across `wiremock-extensions`.

## Status Categories

- **Accepted**: Active, canonical architectural standard.
- **Superseded**: Previously accepted decision evolved and replaced by a subsequent ADR.
- **Rejected**: Evaluated trial or experiment that was reverted or rejected.

---

## Architecture Decision Index

| ADR | Title | Scope | Status | Replaces / Superseded By |
| :--- | :--- | :--- | :--- | :--- |
| [0001](0001-nested-payload-lens-roundtripping.md) | Stacked Nested Payload Lens for Round-Trip Escaped Payload Editing | UI | Superseded | Superseded by [0010](0010-unified-payload-component-and-2x2-experience-matrix.md), [0012](0012-comprehensive-ui-editing-deepening-and-lifecycle-hardening.md) |
| [0002](0002-universal-payload-lens-and-viewport-adaptive-sizing.md) | Universal Payload Lens, Inline Truncation Flow, and Viewport-Adaptive Sizing | UI | Superseded | Superseded by [0010](0010-unified-payload-component-and-2x2-experience-matrix.md), [0012](0012-comprehensive-ui-editing-deepening-and-lifecycle-hardening.md) |
| [0003](0003-recursive-payload-auto-unnesting-and-expanded-presentation.md) | Recursive Payload Auto-Unnesting, Expanded-by-Default Presentation | UI | Superseded | Superseded by [0010](0010-unified-payload-component-and-2x2-experience-matrix.md), [0012](0012-comprehensive-ui-editing-deepening-and-lifecycle-hardening.md) |
| [0004](0004-casual-buffer-descriptor-presentation.md) | Casual Buffer Descriptor Presentation and Fielded Payload Inspection | Casual / UI | **Accepted** | — |
| [0005](0005-canonical-payload-surface-and-fullscreen-viewport.md) | Canonical Payload Surface, Fullscreen Maximized Viewport, Contextual Save | UI | Superseded | Superseded by [0008](0008-canonical-payload-surface-and-maximized-viewport.md), [0010](0010-unified-payload-component-and-2x2-experience-matrix.md) |
| [0006](0006-browser-cdp-ui-performance-gate.md) | Browser CDP Automated Performance Gate and UI Frame Budgeting | UI / Testing | **Accepted** | — |
| [0007](0007-schema-less-fielded-buffer-mediation.md) | Schema-less Fielded Buffer Mediation & Dynamic Type Inference | Casual | **Accepted** | — |
| [0008](0008-canonical-payload-surface-and-maximized-viewport.md) | Canonical Payload Surface, Maximized Viewport, Lenient Base64 Unnesting | UI | Superseded | Superseded by [0010](0010-unified-payload-component-and-2x2-experience-matrix.md), [0012](0012-comprehensive-ui-editing-deepening-and-lifecycle-hardening.md) |
| [0009](0009-dual-layer-code-editor-and-viewport-layout-hardening.md) | Dual-Layer Code Editor and Viewport Layout Hardening | UI | **Accepted** | Foundational editor synchronization layer |
| [0010](0010-unified-payload-component-and-2x2-experience-matrix.md) | Unified Payload Component and 2x2 Experience Matrix | UI | **Accepted** | Unifies all payload viewers/editors, identical hierarchical toolbars, in-line auto-growing edit (`Edit` → `Apply/Cancel`), and top-scope Root Document propagation |
| [0011](0011-parallel-polyglot-nested-editor-and-protected-token-islands.md) | Parallel Polyglot Nested Editor and Protected Token Islands | UI | **Rejected** | Trial prototype reverted; abandoned in favor of [0010](0010-unified-payload-component-and-2x2-experience-matrix.md) and [0012](0012-comprehensive-ui-editing-deepening-and-lifecycle-hardening.md) |
| [0012](0012-comprehensive-ui-editing-deepening-and-lifecycle-hardening.md) | Comprehensive UI Editing Deepening and Lifecycle Hardening | UI | **Accepted** | Universal Root Document Ownership across all 6 UI surfaces, transient subsection staging, and generalized `mountCodeInputEditor` |
| [0013](0013-casual-xatmi-in-process-protocol-gateway.md) | Casual XATMI In-Process Protocol Gateway | Casual | **Accepted** | In-process Netty gateway, buffer mediation, 2PC coordination |
| [0014](0014-virtual-thread-l4-tcp-network-emulation.md) | Virtual-Thread L4 TCP Network Emulation | TCP NetEm | **Accepted** | Virtual-thread L4 proxy engine, Toxiproxy v2 REST API, L7 chaos |
| [0015](0015-openapi-dynamic-ingestion-and-proxy-layering.md) | OpenAPI Dynamic Ingestion and Proxy Layering | OpenAPI | **Accepted** | Spec import, schema example synthesis, priority proxy layering |
| [0016](0016-zero-npm-embedded-vanilla-es6-ui-architecture.md) | Zero-NPM Embedded Vanilla ES6 UI Architecture | UI | **Accepted** | Pure ES6 modules, air-gapped capable, multi-tier testing loops |
| [0017](0017-structured-single-line-json-traffic-logging-and-mdc.md) | Structured Single-Line JSON Traffic Logging and MDC Propagation | Logging | **Accepted** | Single-line JSON traffic logs, MDC header propagation, CRLF safety |
| [0018](0018-in-process-opentelemetry-metrics-and-w3c-tracing.md) | In-Process OpenTelemetry Metrics and W3C Tracing | OTel | **Accepted** | In-process telemetry, Golden Signals, Prometheus scraping, W3C trace spans |
| [0019](0019-in-memory-stateful-ast-simulation-and-crossing-line.md) | In-Memory Stateful AST Simulation and Parallel Session Isolation | Stateful | **Accepted** | Dynamic AST state machines, correlation session isolation, response templating |
| [0020](0020-asynchronous-consumer-mocking-and-queue-mediation.md) | Asynchronous Consumer Mocking and Queue Mediation | Async | **Accepted** | Virtual-thread Kafka Netty sink, AMQP 1.0 broker, priority bridge, synthetic journal events |
| [0021](0021-casual-queue-protocol-gateway-and-mediation.md) | Casual Queue Protocol Gateway and Mediation | Casual | **Accepted** | Netty queue frames (6100-6202), 2PC queue commit/rollback, synthetic queue journal probes |
| [0022](0022-async-and-queue-testing-pyramid-and-e2e-verification.md) | Multi-Protocol Async & Queue Testing Strategy, Pyramid, and E2E Playwright Verification | Testing | **Accepted** | Four-tier testing architecture, embedded Artemis, multi-node Casual harness, headless Playwright |
| [0023](0023-unified-multi-protocol-tester-and-dispatch-workbench.md) | Unified Multi-Protocol Tester & Dispatch Workbench | UI | **Accepted** | Morphing address bar, protocol picker, shared DualLayerCodeEditor, settlement inspector |
| [0024](0024-deep-async-serveevent-ingestion-and-zero-dom-dispatcher.md) | Deep Async ServeEvent Ingestion and Zero-DOM Protocol Dispatcher Seam | Async / UI | **Accepted** | Direct ServeEvent ingestion seam, Zstd compression transparency, zero-DOM dispatcher, Bridged domain verdict |
