# 8. Canonical Payload Surface, Maximized Viewport, and Lenient Base64 Unnesting

Date: 2026-11-03

## Status

Accepted

## Context

In our web console, viewing and editing request and response payloads encountered three major UX and architectural inconsistencies:

1. **Broken Maximized Viewport**: Clicking `⛶ Maximize` on a rich, syntax-highlighted payload viewer (with auto-unnested JSON/XML blocks) dumped the user into an unformatted, monospace `<textarea>` with escaped strings (`\"auditor\": \"auto-fsm\"`) and archaic modal buttons ("Prettify", "Compact", etc.), rather than showing a full-screen version of the same rich surface.
2. **Confusing "Lens" Moniker & Clutter**: Format badges and headers featured a hovering `🔍 Lens` pill button alongside `⛶ Maximize`, creating redundant and conflicting mental models.
3. **Tester Panels Using Legacy Textareas**: The HTTP Tester and Casual Tester request bodies used plain textareas with simple "Format JSON" links, rather than reusing the Canonical Payload Surface with syntax highlighting, Formatted/Raw toggles, and nested unnesting.
4. **Base64 Payloads Left Opaque**: When Base64 strings encoded Java `Map.toString()` / pseudo-JSON structures (`{abc={abc={id=1111111111111}}}` or `{messages=null; deviations=[]; periods=[]}`) or plain text, strict RFC 8259 JSON parsers rejected them, leaving them as opaque Base64 strings.

## Decision

We introduce a unified UI architecture and expanded unnesting engine:

1. **Rich Fullscreen Maximized Viewport**:
   - The Maximized Viewport (100vw × 100vh) hosts the exact same **Canonical Payload Surface** as inline cards: rich syntax highlighting, auto-unnested child blocks, format badges, and Formatted/Raw wire toggles.
   - Operates in **Viewer Mode** for read-only contexts (Journal, Stub Catalog) with copy and snapshot actions, and **Editor Mode** for editable contexts (Stub Editor, HTTP Tester, Casual Tester) with live editing and save lifecycle.

2. **Retirement of "Lens" Terminology**:
   - Eradicate the "Lens" moniker across UI buttons, tooltips, DOM IDs, and CSS classes.
   - Format badges (e.g. `JSON · 0.2 KB`, `XML · 0.1 KB`, `KV · 0.1 KB`) are directly clickable to toggle inline collapse/expansion.
   - Dedicated `⛶ Maximize` controls open documents and child layers in the Maximized Viewport.

3. **Unified Tester Surfaces**:
   - Refactor both HTTP Tester and Casual Tester Request Bodies to use the Canonical Payload Surface.
   - "Replay in Tester" actions from the Request Journal populate the canonical surface directly.

4. **Lenient Java Map / KV Normalization & Base64 Text Unnesting**:
   - **Canonical Charset Guessing**: In both Java and ES6 modules, decode byte buffers by validating UTF-8 continuation byte rules; if non-UTF-8, fall back to ISO-8859-1 / Windows-1252.
   - **Lenient KV Parser**: Parse Java `Map.toString()` `{key=val}` and `{key=val; ...}` pseudo-JSON into structured, syntax-highlighted trees with badge `KV · X KB` or normalized JSON.
   - **General Base64 Text Unnesting**: Any Base64 string decoding to printable text is unnested with an inline format badge (`TEXT · X KB`, `KV · X KB`, `JSON`, `XML`).

## Consequences

- **Consistent User Experience**: Every payload in the console—in the Journal, Stub Catalog, Stub Editor, HTTP Tester, or Casual Tester—uses the identical Canonical Payload Surface.
- **Predictable Maximization**: Maximizing a payload preserves all formatting, syntax coloring, and nested blocks at full screen.
- **Deeper Observability**: Legacy middleware payloads (such as Java Map string representations encoded in Base64) are immediately readable and inspectable without manual decoding.
