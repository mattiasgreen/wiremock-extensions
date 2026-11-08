# 12. Comprehensive UI Editing Deepening, Root Document Propagation, and Lifecycle Hardening

Date: 2026-11-08

## Status

Accepted

## Context

Following [ADR 0009](0009-dual-layer-code-editor-and-viewport-layout-hardening.md) and [ADR 0010](0010-unified-payload-component-and-2x2-experience-matrix.md), an architectural audit of all payload viewers and code input surfaces across `wiremock-extension-ui` revealed four remaining sources of interface friction and shallow abstractions:

1. **Raw Escaped Textarea in Stub Editing (`lifecycle.js`)**:
   - While removing the cumbersome 20-field GUI form for editing existing stubs simplified the modal, opening `#stub-editor-modal` directly into a raw `<textarea>` (`#editor-raw-json`) meant that `response.body` and deep nested envelopes appeared as single-line escaped strings (`"{\n  \"transferId\": ..."`).
   - Even when `[ 🎨 Rich ]` was active, the editor did not show the foldable polyglot tree because a single `<textarea>` cannot unescape embedded strings without breaking valid root JSON syntax.
2. **Inconsistent Toolbar Actions Across Root vs. Subsections vs. Read-Only Views**:
   - Nested subsections in the Maximized Viewport lacked `[ ✏️ Edit ]`, while read-only surfaces omitted `[ ✏️ Edit ]` entirely rather than showing a disabled button with an explanatory tooltip.
   - Top-level workflow actions like Request Journal's `Snapshot as Stub` leaked into the Maximized Viewport footer (`#btn-lens-save-root`), confusing the boundary between viewing a payload and executing a top-level Journal row action.
3. **Fragmented Request & Specification Input Editors**:
   - `tester.js` (`#tester-body`), `casual-tester.js` (`#casual-request-payload`), `dynamic-stubs.js` (`#dynamic-model-json`), `openapi.js` (`#openapi-spec-content`), and `import-export.js` (`#bundle-content`) wired separate editor instances or plain textareas rather than sharing the unified **Root Document** and **Hierarchical In-Line Editing** contract.
4. **Modal Dismissal Dirty-State Risks**:
   - Dismissing `#stub-editor-modal` (via `✕`, Escape, or backdrop click) while transient in-memory edits were staged risked silent data loss without confirmation.

## Decision

We deepen all UI viewing and editing surfaces around the **Universal Root Document Model** and **Hierarchical In-Line Editing** defined in [ADR 0010](0010-unified-payload-component-and-2x2-experience-matrix.md):

### 1. Universal Root Document Ownership Across All Six Surfaces

Every surface owns a single **Root Document** in memory, with commit/save and discard/reset actions bound strictly to the top-level surface:

| Surface | Root Document | Top-Level Commit Action | Top-Level Discard Action | `editable` State & Disabled Tooltip |
| :--- | :--- | :--- | :--- | :--- |
| **Stub Catalog Detail (`stubs.js`)** | Selected Stub Mapping JSON | *(None — Read-Only)* | *(None)* | `editable: false` (`"Read-only stub preview — click '✏️ Edit' in the stub header above to modify this stub"`) |
| **Stub Editor Modal (`lifecycle.js`)** | Working Stub Mapping JSON | `[ 💾 Save Mapping ]` | `[ Cancel ]` / `✕` | `editable: true` |
| **HTTP Tester (`tester.js`)** | Request Body (`#tester-body`) / Response Body | `[ 🚀 Send Request ]` | `[ ↺ Reset ]` | Request: `editable: true`; Response: `editable: false` (`"Received response payload is read-only"`) |
| **Casual Tester (`casual-tester.js`)** | Request Buffer (`#casual-request-payload`) / Response Buffer | `[ 🚀 Execute Casual Call ]` | `[ ↺ Reset ]` | Request: `editable: true`; Response: `editable: false` (`"Received response payload is read-only"`) |
| **Request Journal (`journal.js`)** | Recorded Request / Response Body | *(Top-level row action: `📸 Create Stub`)* | *(None)* | `editable: false` (`"Recorded traffic is immutable — use '📸 Create Stub' or '🧪 Replay in Tester' on the journal entry"`) |
| **Spec & Model Modals (`dynamic-stubs.js`, `openapi.js`, `import-export.js`)** | Stateful AST / OpenAPI Spec / Bundle JSON | `[ 💾 Save / 📥 Import ]` | `[ Cancel ]` | `editable: true` |

### 2. Polyglot Tree Viewer by Default & Unified In-Line `[ ✏️ Edit ]` $\rightarrow$ `[ ✓ Apply | ✕ Cancel ]`

- **Stub Editor Modal (`lifecycle.js`)**:
  - For existing or cloned stubs, the GUI form is hidden (retained only as a quickstart template when creating a new stub from scratch).
  - The modal renders the **Foldable Polyglot Tree Viewer** by default so `response.body` and all nested JSON/XML/CDATA payloads are immediately unnested, syntax-highlighted, and foldable.
  - Both the **Top-Level Stub Header** and every **Nested Subsection Header** render the standardized toolbar: `[ 🎨 Rich | 📝 Raw ] [ ✏️ Edit ] [ 📋 Copy ] [ ⛶ Maximize ]`.
  - Clicking `[ ✏️ Edit ]` on the top header swaps the root view in-line into an auto-growing Dual-Layer Code Editor (`overflow-y: hidden`, no nested vertical scrollbars) to edit top-level stub properties; clicking `[ ✓ Apply ]` validates and returns to the polyglot tree.
  - Clicking `[ ✏️ Edit ]` on any nested subsection swaps *only that subsection* in-line into an auto-growing Dual-Layer Code Editor for its unescaped content; clicking `[ ✓ Apply ]` validates, re-escapes into the in-memory Root Document via `applyNestedPayloadEdit`, and returns to the polyglot tree.
  - Internal UI catalog metadata flags (`_disabled`, `_dynamic`, `isDynamic`) are stripped via `cleanStubForWireMock()` before display and before persistence.

### 3. Top-Scope Context Propagation into Maximized Viewport (`#modal-payload-lens`)

- Clicking `[ ⛶ Maximize ]` at any level propagates the top-scope `RootDocumentSession` (`{ rootText, editable, readOnlyReason, onUpdateRoot }`) into the Maximized Viewport.
- In read-only contexts (Stub Detail, Request Journal, Tester Response), `[ ✏️ Edit ]` buttons on the top bar and all nested subsections inside the Maximized Viewport remain visible but **`disabled`** with their contextual tooltip.
- `Snapshot as Stub` is removed from the Maximized Viewport modal footer; creating a stub from recorded traffic is exclusively a top-level Request Journal action (`📸 Create Stub`), which opens the Stub Editor Modal with a new, editable Root Document.

### 4. Dirty-State Guardrails on Modal Dismissal

- `requestCloseStubEditor()` and `requestCloseViewport()` inspect whether uncommitted in-line edits or staged Root Document modifications exist (`isDirty`), prompting for confirmation before discarding changes on `✕`, Escape, or backdrop clicks.

## Consequences

### Positive
- **Complete Architectural Symmetry**: Every viewer and editor across the entire console is governed by the same Root Document Session and renders the exact same header toolbar at every nesting depth.
- **UI Predictability via Disabled Tooltips**: Users never have to guess why an `[ ✏️ Edit ]` button disappeared; it is always in the same spot, disabled with a clear tooltip when inspecting immutable or preview surfaces.
- **Clean Separation of Concerns**: Subsections stage transient edits into the Root Document via `[ ✓ Apply ]`; only the top-level surface commits the Root Document to the server or network.
