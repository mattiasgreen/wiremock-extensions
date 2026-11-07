# 5. Canonical Payload Surface, Fullscreen Maximized Viewport, and Contextual Save Lifecycle

Date: 2026-11-02

## Status

Superseded by [ADR 0008](0008-canonical-payload-surface-and-maximized-viewport.md), [ADR 0010](0010-unified-payload-component-and-2x2-experience-matrix.md), and [ADR 0012](0012-comprehensive-ui-editing-deepening-and-lifecycle-hardening.md)

## Context

Across the WireMock developer console, payload inspection and editing were historically fragmented:
1. **Divergent Implementations**: The Stub Catalog detail panel used static `<pre><code>` blocks, the Stub Editor used unhighlighted raw `<textarea>`s, the Request Journal used dynamic diff tables, and the Nested Payload Lens used a dedicated, narrow sub-modal (`#modal-payload-lens`).
2. **Constrained Screen Real Estate**: Inspecting or editing deeply nested JSON/XML payloads or large stub mappings in modal dialogs was cramped, lacking a full-window view.
3. **Inconsistent Save Mechanics**: Editing a nested payload lens did not cleanly integrate with the surrounding context (e.g. updating a stub in memory vs. snapshotting an immutable journal entry vs. updating an HTTP tester request).
4. **Lack of Layer Stacking & Escape Navigation**: Drilling into multiple nested layers (e.g. JSON -> XML -> Base64) was handled within a single modal state rather than composable, stacked viewport layers that unwind cleanly with the `Escape` key.

## Decision

We introduce the **Canonical Payload Surface** architecture across the Web Console:

1. **Unified Canonical Payload Surface**:
   - Standardize all payload viewing and editing on a single reusable component (`Canonical Payload Surface`).
   - Supports seamless dual modes: **Viewer Mode** (syntax-highlighted pretty-printing, format/charset/size pill badge, interactive auto-unnested previews, copy tools) and **Editor Mode** (live syntax validation, formatting actions, and the `✨ Nested Payloads` bar).
   - Replaces all raw textareas in the Stub Editor (`#editor-body`, `#editor-body-match-pattern`, and `#editor-raw-json`) with the canonical surface.

2. **Fullscreen Maximized Viewport & LIFO Navigation Stack**:
   - Any canonical payload surface (root document or nested child) can be maximized into a **Fullscreen Maximized Viewport** (100vw × 100vh overlay) via `⛶ Maximize` or by clicking a lens pill.
   - When maximizing a nested payload from within an already maximized root view, the child viewport pushes onto a **Viewport Navigation Stack**, rendering as a stacked overlay with an incremental `z-index` and a breadcrumb trail (`Root ▸ $.node1 ▸ $.node2`).
   - Pressing `Escape` or clicking `✕ Close` pops only the topmost viewport layer.

3. **Dirty-State Tracking & Non-Blocking Confirmation Prompt**:
   - When a viewport is edited (`currentText !== initialText`), a `● Unsaved Changes` indicator appears.
   - Closing a dirty viewport via `Escape` or `Close` triggers an in-app non-blocking confirmation dialog (`#modal-unsaved-changes`) with options:
     - **[S] Save Changes**: Executes the Contextual Save Handler and closes the layer.
     - **[D] Discard Changes**: Reverts edits to `initialText` and closes.
     - **[Esc] Cancel**: Dismisses the prompt and returns focus to the editor.
   - Pressing `Ctrl+S` / `Cmd+S` inside the editor triggers an in-place save without closing the viewport.

4. **Contextual Save Handlers**:
   - **Stub Catalog / Stub Editor**: Updates the stub definition in memory (and syncs to WireMock).
   - **Request Journal Entry**: Since historical entries are immutable, saving prompts to **Snapshot as New Stub** or **Send to Tester**.
   - **HTTP / Casual Tester (Request)**: Saves modifications directly to the tester's Request Payload editor ready for dispatch.
   - **HTTP / Casual Tester (Response)**: Prompts to **Snapshot as Stub** or **Copy to Request**.
   - **Nested Payload Lens**: Re-escapes and re-encodes the child payload into its parent layer's Container Encoding Envelope (preserving single-byte charsets) and marks the parent dirty.

5. **Playwright Testing Distribution**:
   - Low-level edge cases (syntax validation, encoding maps, stack operations) are covered in fast headless unit tests (`UiModuleUnitTest`).
   - End-to-end integration is verified in a dedicated, comprehensive chained user journey (`UiChainedWorkflowPlaywrightTest`), minimizing browser setup/teardown overhead while validating full cross-panel workflows.

## Consequences

- 100% visual and functional consistency across every payload surface in the application.
- Distraction-free, full-screen editing for complex APIs and multi-layer nested envelopes.
- Robust safety against accidental data loss through dirty tracking and non-blocking confirmation prompts.
- Fast, maintainable Playwright test suite leveraging chained end-to-end journeys.
