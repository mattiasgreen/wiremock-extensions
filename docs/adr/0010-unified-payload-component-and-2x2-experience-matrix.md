# 10. Unified Payload Component and 2×2 Payload Experience Matrix

Date: 2026-11-07

## Status

Accepted (Supersedes Section 2 `Editor Experience Mode` of [ADR 0009](0009-dual-layer-code-editor-and-viewport-layout-hardening.md))

## Context

While [ADR 0005](0005-canonical-payload-surface-and-fullscreen-viewport.md), [ADR 0008](0008-canonical-payload-surface-and-maximized-viewport.md), and [ADR 0009](0009-dual-layer-code-editor-and-viewport-layout-hardening.md) introduced the Canonical Payload Surface and the Dual-Layer Code Editor, critical UX fragmentation and semantic inconsistencies remained across the Web Console:

1. **Three Divergent Components Across Stub Views**:
   - **View Stub (`stubs.js`)**: Hand-rendered `<pre id="stub-json-viewer">` using `renderFormattedWithNestedPreviews()`, lacking any `[ 🎨 Rich | 📝 Raw ]` or `[ 👁️ View | ✏️ Edit ]` toggles.
   - **Maximized Viewer (`payload-lens.js` -> `renderPayloadViewer`)**: Rendered a `.payload-viewer-box` with legacy buttons `[ ✨ Unescaped ]` and `[ 📦 Raw (Wire) ]`, and in `Raw (Wire)` mode called `escapeHtml()` **without syntax highlighting**.
   - **Edit Stub Modal & Maximized Editor (`lifecycle.js` / `index.html`)**: Used raw `<textarea>` elements with divergent toolbars (`Format JSON` in the modal; `✨ Prettify`, `Compact`, `Minify before re-escaping`, and `✨ Deep Nested` bars in the maximized editor).
2. **Semantic Collision on `[ 🎨 Rich | 📝 Raw ]` in ADR 0009**:
   - ADR 0009 used `[ 🎨 Rich | 📝 Raw ]` to toggle syntax highlighting on/off on a raw `<textarea>`, conflicting with the core domain meaning where **🎨 Rich** means *Unescaped, formatted, and indented* and **📝 Raw** means *Escaped and unindented (wire-exact)*—with **syntax highlighting enabled in all modes**.
3. **The Escaped String Editing Impediment**:
   - In WireMock mappings, response bodies and nested envelopes are frequently serialized as escaped strings (e.g., `"body": "{\n  \"key\": \"value\"\n}"` or XML inside JSON). Entering edit mode forced developers to edit raw escaped strings with cumbersome `\"` and `\n` literals instead of editing the unescaped, indented structure directly.
4. **Modal CSS Specificity Defect**:
   - `.modal-dialog-wide #editor-json-panel { display: flex !important; }` overrode `.hidden { display: none !important; }`, causing both Form Mode and Raw JSON mode to render simultaneously inside `#stub-editor-modal`.

## Decision

We establish the **Unified Payload Component** (`createPayloadComponent` in `payload-lens.js`) and the **2×2 Payload Experience Matrix** across all six console surfaces:

### 1. The 2×2 Payload Experience Matrix (Syntax Highlighting Always On)

Every payload surface operates along two orthogonal axes—**Mode** (`Rich` vs. `Raw`) and **Action** (`View` vs. `Edit`)—with syntax highlighting active across all four states (up to the 20 KB `MAX_HIGHLIGHT_PAYLOAD_CHARS` guardrail):

| | **👁️ View** (`action: 'view'`) | **✏️ Edit** (`action: 'edit'`) |
| :--- | :--- | :--- |
| **🎨 Rich** (`mode: 'rich'`) | **Unescaped, formatted, indented viewer** *(Default everywhere)*: Pretty-printed, syntax-highlighted root document with recursively auto-unnested child blocks (`JSON`, `XML`, `CDATA`, `Base64`, `KV`), collapsible format/size badges, and inline `[ ✏️ Edit ]` + `[ ⛶ Maximize ]` actions on each nested block. | **Unescaped, formatted, indented editor**: Dual-Layer Code Editor operating on unescaped, pretty-printed content. For composite documents with embedded strings, each nested block can be edited unescaped in-place (or focused in its own unescaped Dual-Layer Code Editor); saving validates structure and automatically re-serializes/re-escapes back into its parent `Container Encoding Envelope` ([ADR 0001](0001-nested-payload-lens-roundtripping.md)). |
| **📝 Raw** (`mode: 'raw'`) | **Wire-exact, escaped, unindented viewer**: Read-only `<pre class="syntax-view"><code>` with **syntax highlighting enabled** (`highlightPayload`), displaying the exact literal wire/stored string with all escapes intact and without unnesting or re-indenting. | **Wire-exact, escaped, unindented editor**: Dual-Layer Code Editor with **syntax highlighting enabled**, operating directly on the raw escaped string as transmitted on the wire. |

### 2. Universal Reusable Component Factory (`createPayloadComponent`)

- Implement `createPayloadComponent(containerEl, options)` in `payload-lens.js` (Zero-NPM, pure Vanilla ES6).
- All six console surfaces mount this single component:
  1. **View Stub** (`stubs.js` — Stub Catalog detail card).
  2. **Edit Stub Mapping Modal** (`lifecycle.js` — both Form Mode `Response Body` and `Full Stub JSON` tab).
  3. **Fullscreen Maximized Viewport** (`payload-lens.js` — `#modal-payload-lens`).
  4. **HTTP Tester** (`tester.js` — Request Body and Response Body).
  5. **Casual Tester** (`casual-tester.js` — Request Buffer and Response Buffer).
  6. **Request Journal** (`journal.js` — Request Body and Response Body, with `readOnly: true` so `[ ✏️ Edit ]` is hidden while `[ 🎨 Rich | 📝 Raw ]` is fully active).
- Retire all divergent legacy toolbars (`✨ Unescaped / 📦 Raw (Wire)`, `✨ Prettify / Compact / Minify before re-escaping`, and separate `✨ Nested Payloads` chip bars) in favor of the unified component.
- Synchronize existing underlying DOM elements (`#stub-json-viewer`, `#editor-raw-json`, `#editor-body`, `#tester-body`, `#casual-request-payload`, `#payload-lens-editor`) so form submission lifecycles and existing test contracts remain intact.

### 3. Standardized Header Toolbar

Every `createPayloadComponent` instance renders the same header control bar:
- **Left**:
  - Title / label + Format, Charset & Size descriptor badge (`JSON · 0.5 KB`, `XML · UTF-8 · 0.2 KB`, clickable to collapse/expand inline).
  - Optional nested path breadcrumb (`$.response.body ▸ $.executionTrace.gatewayEnvelope`).
- **Right**:
  - **Mode Toggle**: `[ 🎨 Rich | 📝 Raw ]`
  - **Action Toggle**: `[ 👁️ View | ✏️ Edit ]` (omitted when `readOnly: true`)
  - **Action Buttons**:
    - `[ ✨ Format ]` (visible in Edit mode to re-indent current editor buffer)
    - `[ 📋 Copy ]` (copies active representation—unescaped formatted in `Rich`, raw wire in `Raw`)
    - `[ ⛶ Maximize ]` (opens the identical component state in `#modal-payload-lens`)

### 4. In-Place Block Editing & Non-Blocking Syntax Linting

- In **Rich View**, each discovered nested payload block includes an inline `[ ✏️ Edit ]` pill alongside its format badge and `[ ⛶ Maximize ]`. Clicking `[ ✏️ Edit ]` switches that block (or the component) into **Rich Edit** with the unescaped, formatted child payload loaded into a Dual-Layer Code Editor.
- In **Rich Edit**, live validation displays a non-blocking inline lint status pill (e.g., `⚠️ Invalid JSON: line 3`) when syntax is malformed, preventing corrupted re-escaping on save while allowing the user to fix the syntax or switch to `Raw Edit` for negative testing.

### 5. Modal Display Specificity Fix

- Restrict `.modal-dialog-wide #editor-json-panel` flex layout rules in `style.css` to `.modal-dialog-wide #editor-json-panel:not(.hidden)` so Form Mode (`#editor-form-panel`) and Full Stub JSON Mode (`#editor-json-panel`) never display simultaneously.

## Consequences

- **True Single-Component Consistency**: View Stub, Edit Stub Modal, Maximized Viewport, Testers, and Journal share the exact same DOM structure, buttons, and 2×2 state model.
- **Frictionless Rich Editing**: Developers edit unescaped, indented JSON and XML directly without manual `\"` or `\n` escaping, while retaining 1-click access to wire-exact `Raw View` and `Raw Edit`.
- **Always-On Syntax Highlighting**: Both `Rich` and `Raw` modes remain syntax-highlighted in both `View` and `Edit` states.
