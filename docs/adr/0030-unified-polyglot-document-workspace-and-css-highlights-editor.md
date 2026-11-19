# 30. Unified Polyglot Document Workspace and CSS Custom Highlights Editor

Date: 2026-11-19

## Status

Accepted — Supersedes [ADR 0009](0009-dual-layer-code-editor-and-viewport-layout-hardening.md), [ADR 0010](0010-unified-payload-component-and-2x2-experience-matrix.md), and [ADR 0012](0012-comprehensive-ui-editing-deepening-and-lifecycle-hardening.md)

## Context

Previous UI iterations ([ADR 0009](0009-dual-layer-code-editor-and-viewport-layout-hardening.md), [ADR 0010](0010-unified-payload-component-and-2x2-experience-matrix.md), [ADR 0012](0012-comprehensive-ui-editing-deepening-and-lifecycle-hardening.md)) unified payload viewing around an inline `<pre>`/`<textarea>` dual-layer editor and a separate `#modal-payload-lens` viewport, alongside a dedicated `#stub-editor-modal`. In practice, three architectural limits remained:

1. **DOM Bloat Under Dual-Layer Syntax Highlighting**: Wrapping every token in a `<span>` inside a synchronized `<pre>` backdrop created thousands of DOM nodes for medium payloads, requiring a 20 KB highlighting cutoff and risking main-thread layout stalls during rapid editing.
2. **Fragile String-Replacement Round-Tripping**: Editing nested JSON/XML/Base64/CDATA payloads by re-serializing whole parsed trees altered unrelated keys, collapsed duplicate sibling fields, or drifted surrounding wire whitespace.
3. **Modal & Surface Duplication**: Maintaining separate modals (`#stub-editor-modal` vs. `#modal-payload-lens`) and inline edit swaps duplicated dirty-state guards, template pickers, validation banners, and save/discard lifecycles across Stubs, Request Journal, Tester, OpenAPI, and Queues & Topics.

## Decision

We consolidate all rich document inspection and editing into a single **Document Workspace** backed by a zero-DOM **Polyglot AST** and a **CSS Custom Highlight API** code editor:

1. **Unified Document Workspace Modal (`#modal-document-workspace`, `document-workspace.js`)**:
   - Single modal surface used across Stub editing, Request Journal inspection, Multi-Protocol Tester request/response inspection, OpenAPI spec import, and Queues & Topics message editing.
   - Operates in two clean modes:
     - **`DOCUMENT_VIEWER`**: Non-destructive foldable polyglot tree rendered by `PolyglotViewer` (`polyglot-viewer.js`) with `Rich` / `Raw` display toggle, format/charset/size descriptors, and per-section `Edit` triggers.
     - **`SECTION_EDITOR`**: Full-viewport code editor for either the root document or any nested section, with live validation, formatting, template selection, and dirty-state dismissal guards.

2. **Zero-DOM Polyglot AST & Exact-Span Splicing (`polyglot-ast.js`)**:
   - Recursively discovers nested JSON, XML, CDATA, XML-entity, KV, and Base64 payloads along with their exact `[start, end)` character spans and detected charsets (`charset-codec.js`).
   - `applyNestedPathEdit` splices edited nested content back into the root document by exact character offset rather than full-tree re-serialization, preserving duplicate sibling keys, same-named nested keys, surrounding wire formatting, and non-UTF-8 encodings.

3. **CSS Custom Highlight API Code Editor (`code-editor.js`)**:
   - Defaults to the `highlight-api` engine when the browser supports `CSS.highlights`, `Highlight`, and `StaticRange`, registering token ranges (`key`, `string`, `number`, `bool`, `null`, `punct`, `xml-tag`, `xml-attr`, `xml-val`, `comment`) in a shared `highlightRegistry` with **zero `<span>` DOM nodes**.
   - Retains `legacy-duallayer` (`<pre>` backdrop + transparent `<textarea>`) as an automatic fallback when `CSS.highlights` is unavailable and as an in-memory toggleable engine (`getEditorEngine` / `setEditorEngine`, no `localStorage`).

## Consequences

- **Zero-DOM Syntax Highlighting**: Dramatically reduces DOM node count and layout recalculation during editing while maintaining the Zero-NPM / Pure Vanilla ES6 invariant.
- **Lossless Nested Editing**: Exact-span AST splicing preserves wire fidelity across multi-layered JSON/XML/Base64/CDATA envelopes.
- **Single Editing Lifecycle**: Retires `#modal-payload-lens` and fragmented inline editors in favor of one consistent `#modal-document-workspace` contract across every console tab.
