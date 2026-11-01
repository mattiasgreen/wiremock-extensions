# 4. Casual Buffer Descriptor Presentation Across Console Surfaces

Date: 2026-11-01

## Status

Accepted

## Context

In the Casual XATMI binary RPC protocol, messages are framed and serialized using distinct transport buffer types (`FIELDED` / `FML`, `JSON`, `CSTRING`, `FLAT`, `X_OCTET`). However, the encapsulated payload carried within that buffer may have its own distinct syntax (for example, a `FIELDED` buffer holding an embedded `JSON` document string, or an `X_OCTET` binary buffer carrying `XML` text).

Previously, viewer and editor pill badges only displayed the detected syntactic document format (`JSON`, `XML`, `TEXT`, etc.) and payload byte size (e.g. `[JSON · 0.4 KB]`). This caused a loss of transport context for engineers inspecting Casual traffic:
1. It was not immediately obvious what buffer type was being negotiated on the Casual gateway.
2. In the Casual Tester, switching the buffer type `<select>` did not visually reflect on the payload editor.
3. In the Request Journal and Stubs viewer, Casual calls were presented purely as HTTP-like JSON or XML bodies without clear Casual buffer contract visibility.

## Decision

We introduce the **Casual Buffer Descriptor** presentation model across all Casual console surfaces:

1. **Badge Composition & Deduplication**:
   - When the Casual buffer type differs from the detected payload syntax format (e.g. `FIELDED` buffer containing a `JSON` document, or `X_OCTET` containing `XML`):
     Render `[<BUFFER_TYPE> · <FORMAT> · <SIZE>]` (e.g. `[FIELDED · JSON · 0.4 KB]`, `[X_OCTET · XML · 1.2 KB]`).
   - When the buffer type and syntax format are identical (e.g. a `JSON` buffer containing `JSON` payload):
     Deduplicate to avoid stutter: render `[CASUAL JSON · <SIZE>]` (e.g. `[CASUAL JSON · 0.4 KB]`).
2. **Unified Surface Coverage**:
   - **Casual Tester Request Editor**: Live badge above `#casual-request-payload` updating on user typing and `#casual-buffer-type` changes.
   - **Casual Tester Response Inspector**: Top-level badge above `#casual-response-body` displaying returned buffer type and payload syntax.
   - **Request Journal Detail**: Top-level badge on both Request Body and Response Body headers for any request classified with `isCasual` (using `x-casual-buffer-type`).
   - **Stubs Master-Detail Viewer**: Top-level badge (`#stub-top-pill`) for any stub matching `/__casual/services/*` or metadata `protocol: 'casual'`.
3. **Interactive Capabilities**:
   - Clicking the badge continues to toggle between expanded and collapsed presentation.
   - Hovering reveals the `[🔍 Lens]` button to open the full payload in the Nested Payload Lens.

## Consequences

- Casual engineers gain immediate transparency into both transport wire contracts and document syntax in one glance.
- Zero extra screen clutter: action buttons remain hover-only, and identical buffer/syntax labels are cleanly deduplicated.
