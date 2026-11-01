# Universal Payload Lens, Inline Truncation Flow, and Viewport-Adaptive Sizing

Nested payload inspection and round-trip editing must not be restricted to isolated viewer surfaces or break document geometry with disruptive block widgets. Furthermore, developer consoles must adapt fluidly when browser zoom levels change (e.g. 80% or 60%), granting more vertical editor space rather than wasting viewport height with fixed static caps and excessive padding.

## Context & Problem Statement

1. **Surface Fragmentation**: The nested payload lens and embedded payload detection were initially wired into the Request Journal and tester response inspectors, but missing from Stub List viewers (`#stub-json-viewer`), the Stub Editor modal (`#modal-stub-editor`), and request builder inputs (`#tester-body`, `#casual-body`).
2. **Disruptive Block Geometry**: Large escaped payload strings were initially truncated by wrapping them in block-level `.truncated-payload-preview` containers spanning 100% width at column 0. This fractured JSON/XML indentation lines and looked alien inside syntax-highlighted code.
3. **Static Viewport Caps & Whitespace Waste**: Read-only code viewers (`.code-block-mini`) were constrained by hardcoded `max-height: 220px;`, preventing zoomed-out displays (80% or 60%) from utilizing available vertical screen space.
4. **Header Bias**: Dedicated UI prominence for `X-Correlation-Id` (blue table chips, header detail buttons, search placeholder hints) biased the console toward a single enterprise convention rather than supporting arbitrary user-defined session correlation headers.

## Considered Options

- **Modal-Only Editing without In-Flow Previews**: Keep syntax views completely raw and only show an external button to launch a lens. Rejected because developers reading through traffic cannot see at a glance what format or size an escaped blob contains.
- **Fixed-Pixel Layout Expansion**: Increase `.code-block-mini` max-height to a larger fixed pixel value (e.g. `400px`). Rejected because fixed pixel heights fail when screen resolutions or browser zoom factors scale up or down.
- **Inline Syntax Flow with Floating Actions, Viewport Flex Scaling, and Generic Headers (Chosen)**:
  - Render `.truncated-payload-preview` strictly inline as a syntax-colored string token adhering to the property's indentation depth, surfacing subtle floating actions (`[🔍 Open in Lens]`, `[▾ Expand]`) on hover/focus.
  - Bind all viewer and editor surfaces (Stub Viewer, Stub Editor modal, HTTP Tester request/response, Casual Tester request/response, Journal) to unified nested payload detection and lens editing.
  - Transition code blocks and editor cards to viewport-relative flex rules (`min-height: 240px; max-height: calc(75vh - 40px);` or flex growth), while trimming container padding so zooming out scales editor capacity.
  - Eliminate hardcoded `X-Correlation-Id` callouts in favor of generic header filtering and browser-local persistent headers.
