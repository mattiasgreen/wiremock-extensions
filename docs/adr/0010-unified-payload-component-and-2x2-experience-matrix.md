# 10. Unified Payload Component, Hierarchical In-Line Editing, and 2×2 Experience Matrix

Date: 2026-11-08

## Status

Accepted (Supersedes Section 2 `Editor Experience Mode` of [ADR 0009](0009-dual-layer-code-editor-and-viewport-layout-hardening.md))

## Context

While [ADR 0005](0005-canonical-payload-surface-and-fullscreen-viewport.md), [ADR 0008](0008-canonical-payload-surface-and-maximized-viewport.md), and [ADR 0009](0009-dual-layer-code-editor-and-viewport-layout-hardening.md) introduced the Canonical Payload Surface and the Dual-Layer Code Editor, critical UX fragmentation and semantic inconsistencies remained across the Web Console:

1. **Divergent Components Across Stub & Payload Views**:
   - **View Stub (`stubs.js`)**: Hand-rendered `<pre id="stub-json-viewer">` using `renderFormattedWithNestedPreviews()`, lacking top-level `[ 🎨 Rich | 📝 Raw ]` or `[ ✏️ Edit ]` controls.
   - **Edit Stub Modal (`lifecycle.js`)**: Dropped users directly into a raw `<textarea>` where nested payloads (`response.body`, `gatewayEnvelope`, `messagePayload`) appeared as unreadable escaped single-line strings (`"{\n  \"transferId\": ..."`), losing the foldable polyglot tree even when `🎨 Rich` was active.
   - **Maximized Viewport (`payload-lens.js`)**: Rendered nested subsections with `[ 🎨 Rich | 📝 Raw | ⛶ Maximize ]`, but lacked `[ ✏️ Edit ]` on subsections and inconsistently mixed root commit actions (such as Journal `Snapshot as Stub`) into the modal footer.
2. **Semantic Collision on `[ 🎨 Rich | 📝 Raw ]`**:
   - ADR 0009 used `[ 🎨 Rich | 📝 Raw ]` on `<textarea>` inputs to toggle syntax highlighting on/off, conflicting with the core domain meaning where **🎨 Rich** means *Unescaped, formatted, foldable polyglot tree* and **📝 Raw** means *Escaped, wire-exact representation*—with **syntax highlighting enabled in all modes**.
3. **The Escaped String Editing Impediment**:
   - Because a native `<textarea>` must maintain 1:1 character alignment with its syntax backdrop, a single `<textarea>` cannot simultaneously unescape and fold embedded strings without breaking valid wire JSON syntax or cursor alignment.

## Decision

We establish the **Unified Payload Component** (`createPayloadComponent` in `payload-lens.js`), the **Root Document Session**, and **Hierarchical In-Line Editing** across all console surfaces:

### 1. The 2×2 Payload Experience Matrix (Syntax Highlighting Always On)

Every document level (both the **Top-Level Root Document** and every **Nested Subsection**) operates along two orthogonal axes—**Mode** (`Rich` vs. `Raw`) and **Action** (`View` vs. `Edit`)—with syntax highlighting active across all four states (up to the 20 KB `MAX_HIGHLIGHT_PAYLOAD_CHARS` guardrail):

| | **👁️ View** (`action: 'view'` — Default) | **✏️ Edit** (`action: 'edit'` — On-Demand In-Line Swap) |
| :--- | :--- | :--- |
| **🎨 Rich** (`mode: 'rich'`) | **Foldable Polyglot Tree Viewer** *(Default everywhere)*: Pretty-printed, syntax-highlighted document with recursively auto-unnested child blocks (`JSON`, `XML`, `CDATA`, `Base64`, `KV`). Every level (root and subsections) displays the standardized header toolbar. | **Unescaped, Auto-Growing In-Line Editor**: Swaps the specific level's `<pre>` box **in-line** into a Dual-Layer Code Editor (`autoGrow: true`, **no vertical scrollbars**) containing *only that level's unescaped, pretty-printed text*. Clicking `[ ✓ Apply ]` validates syntax, re-escapes into the in-memory Root Document, and returns to the Foldable Polyglot Tree Viewer. |
| **📝 Raw** (`mode: 'raw'`) | **Wire-Exact Viewer**: Read-only `<pre class="syntax-view"><code>` with **syntax highlighting enabled** (`highlightPayload`), displaying the exact literal wire/stored string with all escapes intact and without unnesting. | **Wire-Exact In-Line Editor**: Swaps the specific level's `<pre>` box **in-line** into an auto-growing Dual-Layer Code Editor operating directly on the raw wire string (allowing intentional malformed syntax for negative testing). |

### 2. Identical Toolbar Across Root and All Subsections (Disabled with Tooltip When Read-Only)

Every level in the document hierarchy—from the **Top-Level Root Header** down to the deepest **Nested Subsection Header**—renders the exact same canonical toolbar:

```text
[ <Format · Charset · Size> ]   [ 🎨 Rich | 📝 Raw ]   [ ✏️ Edit ]   [ 📋 Copy ]   [ ⛶ Maximize ]
```

- **Format, Charset & Size Badge** (`JSON · 0.5 KB`, `XML · UTF-8 · 0.2 KB`): Clickable on subsections to collapse/expand the block inline.
- **Mode Toggle**: `[ 🎨 Rich | 📝 Raw ]` (controls unescaped polyglot presentation vs. literal wire string).
- **Edit Button (`[ ✏️ Edit ]`) — Never Omitted, Disabled with Tooltip in Read-Only Contexts**:
  - For UI consistency, `[ ✏️ Edit ]` is **always rendered** on every toolbar (root and nested subsections).
  - When the top-scope `RootDocumentSession` is **editable** (`editable: true`), `[ ✏️ Edit ]` is enabled (`title="Edit this section in-line"`).
  - When the top-scope `RootDocumentSession` is **read-only** (`editable: false`), `[ ✏️ Edit ]` is rendered with the `disabled` attribute and an explicit contextual tooltip explaining why:
    - *Stub Catalog Detail*: `"Read-only stub preview — click '✏️ Edit' in the stub header above to modify this stub"`
    - *Request Journal*: `"Recorded traffic is immutable — use '📸 Create Stub' or '🧪 Replay in Tester' on the journal entry"`
    - *Tester Response*: `"Received response payload is read-only"`
- **Active Edit State Toolbar (`[ ✨ Format ] [ ✓ Apply ] [ ✕ Cancel ]`)**:
  - While a level (root or subsection) is in `Edit` state, `[ ✏️ Edit ]` transforms into `[ ✨ Format ]`, `[ ✓ Apply ]`, and `[ ✕ Cancel ]`, accompanied by a live non-blocking lint pill (`⚠️ Invalid JSON/XML`) if syntax errors exist.

### 3. In-Line Subsection & Top-Level Editing Lifecycle (Zero Vertical Scrollbars)

1. **Entering Edit Mode**:
   - Clicking `[ ✏️ Edit ]` on the **Top-Level Root Header** switches the root viewport in-line into a Dual-Layer Code Editor for the entire root document (e.g., full WireMock stub JSON), allowing direct edits to top-level properties (`method`, `urlPattern`, `status`, `headers`, `metadata`).
   - Clicking `[ ✏️ Edit ]` on a **Nested Subsection Header** (e.g., `$.response.body`, `gatewayEnvelope`, `messagePayload`) swaps **only that subsection's `<pre>` container** in-line into a Dual-Layer Code Editor containing that subsection's unescaped text.
   - All in-line editors size dynamically to their exact line count (`autoGrow: true`, `overflow-y: hidden`) so there are **never nested vertical scrollbars** or empty dark voids.
2. **Applying Changes (`[ ✓ Apply ]`) — Transient In-Memory Staging**:
   - Validates syntax when in `🎨 Rich` mode.
   - For a nested subsection, `applyNestedPayloadEdit` automatically re-encodes and re-escapes the modified text up through its ancestor chain into the in-memory **Root Document**.
   - Updates the in-memory **Root Document** transiently (`isDirty = true`) without firing any network/server request.
   - Immediately transitions the edited level back to **Foldable Polyglot Tree Viewer** (`🎨 Rich View`), displaying the freshly updated tree in-place.
3. **Canceling Local Edits (`[ ✕ Cancel ]`)**:
   - Discards uncommitted text in that level's editor and returns immediately to the Foldable Polyglot Tree Viewer without touching the Root Document.

### 4. Top-Scope Root Document Propagation & Commit Ownership

- **Root Commit/Discard Ownership**: Save, commit, and discard actions apply **exclusively to the Root Document** at the top-level UI surface (e.g., `[ 💾 Save Mapping ]` / `[ Cancel ]` in Stub Editor; `[ 🚀 Send Request ]` in HTTP/Casual Tester; `[ 💾 Save Model ]` in Stateful AST Editor; `[ 📥 Import Spec ]` in OpenAPI Importer).
- **Context Propagation into Maximized Viewport (`#modal-payload-lens`)**:
  - Clicking `[ ⛶ Maximize ]` on the root header or any nested subsection opens `#modal-payload-lens` bound to the originating top-scope `RootDocumentSession` (`editable`, `readOnlyReason`, `onUpdateRoot`).
  - Edits applied via `[ ✏️ Edit ]` $\rightarrow$ `[ ✓ Apply ]` inside the Maximized Viewport transiently update the same underlying Root Document.
  - Top-level workflow actions such as Request Journal's `📸 Create Stub` belong strictly to the top-level Journal row toolbar, never inside the payload viewer modal.

## Consequences

- **100% Toolbar & Interaction Symmetry**: Every header (top-level root and every nested subsection) across every console view has the exact same buttons, tooltips, and `[ ✏️ Edit ]` $\rightarrow$ `[ ✓ Apply | ✕ Cancel ]` lifecycle.
- **Best of Both Worlds in Stub & Payload Editing**: Developers browse the full foldable polyglot tree by default, edit any nested XML/JSON payload cleanly without `\"` escape noise, or click `[ ✏️ Edit ]` on the top header to edit root WireMock stub properties—all staged transiently in memory until the root `[ 💾 Save Mapping ]` action is clicked.
- **Zero Nested Scrollbars or Layout Voids**: Auto-growing in-line editors preserve natural document scrolling and eliminate visual fragmentation.
