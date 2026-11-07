# 10. Unified Payload Component and 2×2 Payload Experience Matrix

Date: 2026-11-07

## Status

Accepted

## Context

While ADR 0005, ADR 0008, and ADR 0009 introduced the Canonical Payload Surface and the Dual-Layer Code Editor, critical UX fragmentation and inconsistencies remained across the Web Console:

1. **Surface Divergence**: Viewing a stub card in the catalog, editing a stub in the modal dialog, and inspecting a maximized payload utilized disparate DOM structures, toolbars, and rendering lifecycles. Users were confronted with differing visual presentations and interactions depending on whether they opened a payload in-place or via a modal.
2. **The Escaped String Editing Impediment**: In WireMock mappings, response bodies are frequently serialized as escaped JSON strings (e.g., `"body": "{\n  \"key\": \"value\"\n}"`) or nested XML/CDATA. While read-only viewports successfully unescaped and formatted these trees, entering edit mode forced developers to edit raw escaped strings with cumbersome `\"` and `\n` literals, leading to frequent syntax errors.
3. **Missing 2×2 State Symmetry**: Users lacked an orthogonal, predictable mental model for payload interaction. Developers need both formatted, unescaped views/edits (for human comprehension) and wire-exact escaped views/edits (to see precisely what travels over the wire).
4. **Modal Layout Defect**: A CSS specificity regression caused `.modal-dialog-wide #editor-json-panel { display: flex !important; }` to override `.hidden { display: none !important; }`, resulting in both Form Mode and Raw JSON mode rendering simultaneously within the Edit Stub Mapping modal.

## Decision

We establish the **Unified Payload Component** and the **2×2 Payload Experience Matrix** across all console surfaces:

### 1. The 2×2 Payload Experience Matrix

Every payload surface operates along two orthogonal axes: **Mode** (`Rich` vs. `Raw`) and **Action** (`View` vs. `Edit`), creating four explicit states:

- **Rich View** *(Default)*: Unescaped, formatted, indented, and foldable JSON/XML tree with syntax highlighting, format/size badges (`JSON`, `XML`, `CDATA`), and in-place child edit chips.
- **Rich Edit**: Direct editing of unescaped, formatted, indented content. When editing an unescaped nested payload, saving automatically validates structure and re-serializes/re-escapes the content back into the parent container envelope.
- **Raw View**: Wire-exact, escaped, and unindented read-only syntax-highlighted block reflecting the literal string payload stored or received over the wire.
- **Raw Edit**: Dual-Layer Code Editor operating on the raw escaped string with syntax highlighting and manual escape character control.

### 2. Universal Reusable Component Architecture

- Implement a single factory function `createPayloadComponent(containerEl, options)` in `payload-lens.js` (zero-NPM, pure ES6 module).
- All six console surfaces mount this identical component:
  1. **View Stub**: Stub cards in the Stubs Catalog tab.
  2. **Edit Stub Mapping Modal**: Form Mode Response Body and Full Stub JSON tab.
  3. **Fullscreen Maximized Viewport**: `#modal-payload-lens`.
  4. **HTTP Tester**: Request Body and Response Body.
  5. **Casual Tester**: Request Buffer and Response Buffer.
  6. **Request Journal**: Request and Response inspection (immutable; `[ ✏️ Edit ]` toggle cleanly hidden).

### 3. Standardized Header Toolbar

Every component instance renders a uniform control bar:
- **Left**: Format & size descriptor badge (`JSON · 0.5 KB`) and contextual path breadcrumbs (`$.response.body`).
- **Right**:
  - `[ 🎨 Rich | 📝 Raw ]` mode toggle.
  - `[ 👁️ View | ✏️ Edit ]` state toggle (hidden on read-only surfaces).
  - `[ Format ]` action button (formats JSON/XML during editing).
  - `[ Copy ]` action button (copies active payload to clipboard).
  - `[ ⛶ Maximize ]` action button (expands the identical component into the Fullscreen Maximized Viewport).

### 4. In-Place Block Editing & Linting

- When exploring nested payloads in Rich View, in-place edit chips allow opening an unescaped editor directly for that child segment.
- Rich Edit incorporates non-blocking syntax linting (visual error pill indicating line and error description) without blocking navigation or switching to Raw Edit.

### 5. Modal Display Specificity Fix

- Update `#editor-json-panel` styling in `style.css` to target `:not(.hidden)` explicitly, guaranteeing mutual exclusivity between Form Mode and Raw JSON mode in `.modal-dialog-wide`.

## Consequences

- Completely unified UI experience across catalog cards, modal dialogs, and maximized viewports.
- Eliminates manual JSON/XML string escaping during everyday stub authoring.
- Provides seamless fallbacks to wire-exact raw views and raw editing when developers require low-level protocol inspection.
- Fixes the modal overlapping defect and maintains zero-NPM bundle invariants.
