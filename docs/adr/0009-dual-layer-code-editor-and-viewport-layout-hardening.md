# 9. Dual-Layer Code Editor, In-Memory Experience Mode, and Viewport Layout Hardening

Date: 2026-11-06

## Status

Accepted (Section 2 `Editor Experience Mode` superseded by [ADR 0010](0010-unified-payload-component-and-2x2-experience-matrix.md): `[ 🎨 Rich | 📝 Raw ]` governs Unescaped/Formatted vs. Escaped/Wire-Exact representation rather than toggling syntax highlighting off; Dual-Layer Code Editor syntax highlighting remains active across both `Rich Edit` and `Raw Edit` up to the 20 KB performance guardrail)

## Context

While ADR 0005 and ADR 0008 established the Canonical Payload Surface across the developer console, two significant UX defects emerged:

1. **Missing Syntax Highlighting in Edit Surfaces**: Because the project operates under a strict Zero-NPM / Zero-Bundler invariant (prohibiting Monaco, CodeMirror, or Ace), read-only surfaces used rich regex tokenization into `<pre class="syntax-view">`, while all edit surfaces reverted to monochrome native `<textarea>` elements without syntax coloring.
2. **50% Blank Viewport Void**: A CSS specificity collision between `#payload-viewport-viewer-container` (`display: flex !important;` with ID specificity `(1, 1, 0)`) and `.hidden` (`display: none !important;` with class specificity `(0, 1, 0)`) prevented the inactive viewer flex container from collapsing when the Maximized Viewport entered editor mode, causing a massive blank void occupying the top half of the modal.
3. **Premature Editor Maximization**: Clicking `⛶ Maximize` from the read-only Stub Catalog details pane inappropriately launched the viewport in `mode: 'editor'`, forcing users into an unformatted edit textarea instead of an expansive full-screen view of the rich, syntax-highlighted document.

## Decision

We introduce the Dual-Layer Code Editor architecture and viewport hardening:

1. **Zero-NPM Dual-Layer Code Editor**:
   - Overlay an invisible `<textarea>` (configured with `color: transparent; -webkit-text-fill-color: transparent; caret-color: var(--text-color);` and spellcheck disabled) over an identically styled, synchronized `<pre class="editor-backdrop"><code>` backdrop.
   - Lock typography and layout invariants: identical monospace font stack, line-height, padding, borders, letter-spacing, and bidirectional scroll event synchronization (`backdrop.scrollTop = textarea.scrollTop; backdrop.scrollLeft = textarea.scrollLeft`).
   - Automatically fall back to monochrome plain text when payloads exceed `MAX_HIGHLIGHT_PAYLOAD_CHARS` (20 KB) to protect browser responsiveness.

2. **In-Memory Editor Experience Mode (`rich` vs. `raw`)**:
   - Provide an instant 1-click toggle button (`[ 🎨 Rich | 📝 Raw ]`) directly in the editor toolbar, allowing developers to switch to a native raw textarea on demand.
   - Keep this state strictly in-memory per session/modal (no `localStorage` persistence) to avoid stale preference drift across disparate workflows.

3. **Read-Only Maximization Contract**:
   - Maximizing read-only payloads (Stub Catalog detail, Request Journal request/response, HTTP Tester response) unconditionally opens in **Viewer Mode**, presenting rich syntax coloring, clickable format badges, and auto-unnested embedded blocks at 100vw × 100vh.
   - An explicit `[ ✏️ Edit ]` toolbar button transitions the viewport into Editor Mode when modifications or snapshotting are desired.

4. **Viewport Layout Hardening**:
   - Add high-specificity containment rules (`.fullscreen-viewport #payload-viewport-viewer-container.hidden, .fullscreen-viewport #payload-viewport-editor-container.hidden { display: none !important; }`) and remove conflicting `!important` flex directives, guaranteeing that only the active mode claims 100% of the modal body.

5. **Dedicated Playwright Verification Seam**:
   - Introduce a dedicated browser test suite validating dual-layer editing, character typing, round-tripping, non-UTF-8 character encodings (e.g. ISO-8859-1, KOI8-R), and viewport layout geometry.

## Consequences

- 100% visual consistency between reading and editing payloads without violating the Zero-NPM invariant.
- Bulletproof editing reliability backed by an instant in-memory fallback toggle to the raw native textarea.
- Clean, predictable fullscreen maximization that preserves full-screen typography without layout collapse or blank space.
