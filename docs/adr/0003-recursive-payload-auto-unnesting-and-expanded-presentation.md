# Recursive Payload Auto-Unnesting, Expanded-by-Default Presentation, and Truncation Threshold Relief

## Context & Problem Statement

1. **Single-Pass Unnesting Limitation**: In complex multi-format payloads (such as JSON containing escaped JSON containing XML containing CDATA), the unnesting renderer executed only a single-pass replacement at depth 1. When expanding Level 1 (e.g. `$.response.body`), the content was rendered with a standard syntax highlighter that failed to discover or recursively format child nodes (`node.children`), leaving embedded payloads like `gatewayEnvelope` and its XML `messagePayload` as raw escaped strings with `\"`.
2. **Eager Truncation**: The viewer prematurely truncated any escaped payload longer than 80 characters or compact single-line strings behind an ellipsis preview token. This forced developers to manually click `[▾ Expand]` on almost every normal-sized response simply to read the unescaped content.

## Considered Options

- **Manual On-Demand Recursive Unfolding**: Require developers to click separate "Expand" pills at every level of the nesting tree. Rejected because reading multi-layered payloads becomes tedious and disjointed.
- **Root-Only Auto-Unnesting with Child Collapsing**: Only auto-expand Level 1 and collapse all subsequent child levels. Rejected because child structures (like XML messages embedded in gateway JSON) remain hidden behind strings.
- **Recursive Auto-Unnesting with Expanded-by-Default Presentation (Chosen)**:
  - Recursively traverse child nodes in `discoverEmbeddedPayloads` down to arbitrary depth, auto-formatting and indenting each nested layer directly within the syntax tree.
  - In "✨ Unescaped" mode, present nested structures expanded and auto-indented by default with a clean left-border gutter and format badge, while offering an optional `[▾ Collapse]` fold toggle.
  - Remove the aggressive 80-character truncation threshold so complete payloads are immediately readable on load.
  - Retain the `[🔍 Open in Lens]` floating/gutter button on each layer for focused isolated inspection, validation, and cascading round-trip edits.
  - Defer 1MB+ blob parsing safeguards to a future iteration.

## Consequences & Future Considerations

- **Immediate Clarity**: Developers immediately see full, cleanly formatted representations of nested microservice envelopes (SWIFT, ISO20022, SOAP, Base64 JSON) without repetitive manual clicks.
- **Dual Representation Integrity**: The "📦 Raw (Wire)" toggle continues to provide the exact byte-for-byte wire representation with literal escapes intact.
- **Deferred Large Blob Guardrail**: Very large payloads (e.g., 1MB+ base64 or raw JSON/XML blobs) currently parse in-browser without a size ceiling. A future enhancement will introduce a configurable performance safeguard (e.g. at 1 MB) to defer auto-parsing or offer a lightweight streaming view.
