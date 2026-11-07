# 12. Comprehensive UI Editing Deepening and Lifecycle Hardening

Date: 2026-11-07

## Status

Accepted

## Context

Following [ADR 0009](0009-dual-layer-code-editor-and-viewport-layout-hardening.md) and [ADR 0010](0010-unified-payload-component-and-2x2-experience-matrix.md), the UI achieved a unified rendering model for read-only payload surfaces and maximized viewports. However, an architectural audit of all payload and code input surfaces across `wiremock-extension-ui` revealed significant interface friction, shallow abstractions, and state-loss risks:

1. **Fragmented Request Input Editing (HTTP & Casual Testers)**:
   - `tester.js` (`#tester-body`) and `casual-tester.js` (`#casual-request-payload`) hand-rolled separate Dual-Layer editor instances, ad-hoc `✨ Format` button handlers, bespoke descriptor pill DOM modifications, and independent maximize event handlers.
   - Neither input surface benefited from live structural linting (`⚠️ Invalid JSON/XML`), risking accidental submission of malformed payloads.

2. **Leaky Stub Editor Modal Lifecycle (`lifecycle.js`)**:
   - The primary Stub Editor modal (`#stub-editor-modal`) had sub-editors in Form Mode (`#editor-body`, `#editor-body-match-pattern`) that lacked Maximize viewport leverage and `[ 🎨 Rich | 📝 Raw ]` toggles.
   - Switching between Form Mode (`#btn-editor-mode-form`) and Full JSON Mode (`#btn-editor-mode-json`) performed silent state overrides without checking for uncommitted edits.
   - Dismissing the modal (via the `✕` close button, escape key, or clicking the background backdrop) lacked dirty-state guardrails, causing accidental loss of in-progress edits.

3. **Unassisted Plain Textarea Inputs for Complex Specifications**:
   - Stateful AST dynamic rules (`#dynamic-model-json` in `dynamic-stubs.js`), OpenAPI YAML/JSON specifications (`#openapi-spec-content` in `openapi.js`), and WireMock JSON bundle imports (`#bundle-content` in `import-export.js`) used native unassisted `<textarea>` elements.
   - Users had zero syntax highlighting, zero format prettifying, zero live structural diagnostics, and no ability to maximize the editor to full screen for multi-hundred line specifications.

4. **Shallow Bespoke Inline Block Editor**:
   - `activateInlineBlockEditor` in `payload-lens.js` hand-crafted a separate inline toolbar DOM (`.inline-block-editor-toolbar`) and isolated validation routines detached from `createPayloadComponent`, preventing inline block editors from leveraging format prettifying or maximizing into a full-height viewport.

## Decision

We establish a comprehensive deepening of all UI editing surfaces around the **Canonical Payload Surface** (`createPayloadComponent`) and the **Dual-Layer Code Editor** (`attachDualLayerEditor`):

### 1. Generalize `createPayloadComponent` for Request Input Editors

- Bind HTTP Tester (`#tester-body`) and Casual Tester (`#casual-request-payload`) request editors through `createPayloadComponent(null, { action: 'edit', backingTextarea: ... })`.
- Callers provide their backing `<textarea>` and existing toolbar elements; `createPayloadComponent` automatically manages:
  - Synchronized Dual-Layer syntax highlighting.
  - Live non-blocking syntax validation pills (`⚠️ Invalid JSON` / `⚠️ Invalid XML`).
  - Prettify format actions (`✨ Format`).
  - Maximized viewport delegation with bi-directional value round-tripping.
- Deletes over 80 lines of duplicated DOM query and event listener boilerplate from `tester.js` and `casual-tester.js`.

### 2. Deepen Stub Editor Modal Lifecycle and Guard Edit State (`lifecycle.js`)

- Deepen the Stub Editor lifecycle seam in `lifecycle.js`:
  - Equip modal sub-editors (`#editor-body`, `#editor-body-match-pattern`) with `createPayloadComponent` bindings, unlocking fullscreen Maximize capabilities directly from the modal form.
  - Enforce safe bi-directional state synchronization when switching between Form Mode and Full Raw JSON Mode: if uncommitted edits exist that would be overwritten by a mode switch, the user is warned and prompted before proceeding.
  - Add `requestCloseStubEditor()`: when closing via `✕`, Escape, or backdrop click, inspect dirty state across sub-editors and Raw JSON. If unsaved changes are present, prompt for confirmation before discarding.

### 3. Reusable Code Input Adapter (`mountCodeInputEditor`)

- Introduce a deep `mountCodeInputEditor(textareaEl, options)` adapter in `payload-lens.js`:
  - Mounts a Dual-Layer Code Editor with auto-detected or specified syntax (JSON, YAML, AST).
  - Automatically equips the input surface with a standardized action bar featuring live syntax validation pills, format prettifying (`✨ Format`), and fullscreen Maximize (`⛶ Maximize`).
  - Powers `#dynamic-model-json` (Stateful AST), `#openapi-spec-content` (OpenAPI specs), and `#bundle-content` (WireMock bundle imports).

### 4. Direct Canonical JSON Stub Editing (Form Removal for Existing Stubs)

- The canonical representation of a WireMock stub is its JSON mapping specification. WireMock developers do not want a complex, fragmented 20-field GUI form when modifying existing stubs.
- When opening the stub editor for existing stubs (`stub != null` or cloned stubs):
  - The modal opens directly in **JSON Editor Mode** (`activeEditorMode = 'json'`).
  - The editor mode toggle pills (`.editor-mode-pills`) and form panel (`#editor-form-panel`) are completely hidden.
  - WireMock developers edit clean, syntax-highlighted JSON directly with line numbers, live validation, formatting, and maximize support.
- The GUI form is retained strictly as an optional **Quickstart Template** when creating a brand-new stub from scratch (`stub == null`).
- Internal UI catalog metadata flags (`_disabled`, `_dynamic`, `isDynamic`) are cleanly stripped via `cleanStubForWireMock()` before populating the JSON editor or persisting to WireMock's Admin API, eliminating Jackson deserialization failures.

### 5. Purge of Inline Block Editing from Read-Only Inspection Views

- Attempts to embed in-situ editing widgets and protected token replacements inside read-only stub detail and journal request/response trees ([ADR 0011](0011-parallel-polyglot-nested-editor-and-protected-token-islands.md)) resulted in massive accidental complexity, visual fragmentation, detached XML/JSON blocks, and awkward layout voids.
- `payload-lens.js` and `style.css` are purged of all inline block editing machinery (`activateInlineBlockEditor`, `.btn-nested-edit`, `.inline-block-editor`).
- Read-only inspection views (Stub Details, Journal Request/Response, Casual Payloads) are dedicated strictly to clean visual inspection, polyglot syntax highlighting, format detection pills, nested unnesting, copying, and fullscreen maximize viewing.
- Mutation belongs exclusively in dedicated editing surfaces (the modal JSON Editor or the HTTP/Casual Request Testers).

## Consequences

### Positive
- **Elimination of Visual & Cognitive Overhead**: Read-only surfaces are pristine, legible, and unencumbered by detached editing boxes or confusing nested edit buttons.
- **Fast, Direct Developer Workflow**: Editing stubs opens directly to full JSON with zero clicks wasted on form-to-JSON mode switching.
- **Zero Schema Deserialization Friction**: Stripping internal catalog markers prevents WireMock Jackson deserializer errors (`UnrecognizedPropertyException`).
- **Complete Editing Consistency**: Code inputs across HTTP Tester, Casual Tester, dynamic AST specifications, and OpenAPI imports share standardized syntax highlighting, validation, formatting, and maximize capabilities via `mountCodeInputEditor` and `createPayloadComponent`.
- **Accidental Edit Loss Elimination**: Developers are protected against losing complex configurations during accidental modal dismissals or dirty transitions.

### Negative / Trade-offs
- Creating complex new stubs from scratch requires either typing raw JSON or using the initial form template quickstart.
- Modal close handlers prompt when dirty state is detected before discarding.
