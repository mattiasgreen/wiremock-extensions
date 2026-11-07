# 11. Parallel Polyglot Nested Editor and Protected Token Islands

Date: 2026-11-07

## Status

Accepted (Extends [ADR 0010](0010-unified-payload-component-and-2x2-experience-matrix.md))

## Context

[ADR 0010](0010-unified-payload-component-and-2x2-experience-matrix.md) established the 2×2 Payload Experience Matrix (`Rich View`, `Rich Edit`, `Raw View`, `Raw Edit`) and In-Place Block Editing for nested payload structures. However, during real-world authoring of deep multi-format documents (e.g., JSON containing JSON envelope containing XML payload containing CDATA JSON containing key-value audit records), two key developer friction points emerged:

1. **Modal/Scope Collapse during Parent Editing**:
   - When a developer wanted to edit the outer envelope (e.g., modifying `"routing": { "network": "SWIFT_NET" }` in the parent JSON), activating the editor for that scope collapsed all inner child payloads (e.g., `messagePayload` XML) into escaped, unformatted raw strings (`"<swiftMessage xmlns=\"...\"..."`).
   - The developer was forced to either edit around delicate raw escape characters or drill down into isolated modal viewports layer by layer.
2. **Brittle Contenteditable Alternatives**:
   - Using full `contenteditable` across the whole document tree initially appears tempting for fluid in-tree cursor navigation. However, browser implementations of `contenteditable` severely violate code editor constraints:
     - Arbitrary HTML tag injection (`<p>`, `<div>`, `<br>`) on Enter rather than `\n`.
     - Silent conversion of consecutive indentation spaces into non-breaking spaces (`&nbsp;` / `\u00A0`), which causes JSON/XML parsers to fail with syntax errors.
     - Destruction of caret/cursor selection whenever syntax re-highlighting modifies inner DOM nodes.
     - Uncontrolled HTML pollution during clipboard paste.
3. **Multi-Scope Mutation Race Conditions**:
   - Eagerly re-escaping and saving back to the root document on every keystroke in deep nested structures causes syntax race conditions while typing intermediate, temporarily invalid expressions.

## Decision

We establish the **Parallel Polyglot Nested Editor** architecture based on **Protected Token Islands** and a **Unified Staging Bar**:

### 1. Protected Token Islands for Seamless In-Tree Editing

Rather than collapsing child payloads into escaped text or relying on fragile `contenteditable` trees, the editor decomposes composite documents into a hierarchy of format-aware slices separated by **Protected Token Islands**:

1. **Token Placeholder Representation (`__LENS_TOKEN_N__`)**:
   - When a parent scope enters an active editing state (e.g. `gatewayEnvelope`), any unnested child payloads inside it (e.g. `messagePayload`) are represented in the parent's underlying text model as immutable token placeholders:
     `"messagePayload": "__LENS_TOKEN_0__"`
   - The child island itself is rendered inline directly within the document flow as an unescaped, fully styled, syntax-highlighted sub-component.
2. **Protected Boundaries**:
   - The user can edit the fields of the parent envelope directly without touching or corrupting the child payload.
   - The child payload remains visible in its unescaped, formatted polyglot state.
3. **Direct Click-to-Focus Navigation**:
   - Clicking directly into the text of any scope claims focus for that specific slice's Dual-Layer Code Editor.
   - Users can jump freely between parent and child scopes without explicit modal transitions or manual expand/collapse cycles.

### 2. Serialization and Round-Tripping

When the document is serialized or staged:
1. Active child subnodes serialize and validate their own formatted syntax.
2. The parent slice retrieves each child's serialized string, wraps it in the child's `Container Encoding Envelope` (escaping for JSON, CDATA, XML entities, or Base64 as defined in [ADR 0001](0001-nested-payload-lens-roundtripping.md)), and replaces the corresponding `__LENS_TOKEN_N__` placeholder.
3. The resulting document is validated and propagated up through parent ancestor frames to update the root WireMock stub mapping.

### 3. Unified Staging Bar (Atomic Multi-Scope Commit/Discard)

To protect developers against intermediate syntax breakage while navigating across multiple active nested layers:
1. Each modified slice tracks its local dirty state.
2. A sticky, bottom-anchored **Unified Staging Bar** appears when any slice in the tree is dirty:
   - Displays count and chips of modified scopes (e.g., `● 2 modified scopes: $.response.body, $.executionTrace.messagePayload`).
   - Provides atomic `[ Save Changes ]` (`Ctrl+S` / `Cmd+S`) and `[ Discard Changes ]` (`Esc`) buttons.
3. Structural validation lint badges remain scoped to each individual slice, pinpointing errors precisely where they occur before commit.

### 4. Ergonomic Improvements in View Modes

1. **Line Wrapping in Raw View**:
   - Raw mode viewers now default to `white-space: pre-wrap; word-break: break-all;` so single-line raw wire payloads wrap naturally within the card/viewer viewport without requiring horizontal scrolling.
2. **Symmetric Header Actions**:
   - Top-level Stub Mapping card header and tester body headers include the exact same `[ ✏️ Edit ]` action alongside `[ Format · Size ]`, `[ 🎨 Rich | 📝 Raw ]`, and `[ ⛶ Maximize ]`, standardizing the component layout across all levels of the document hierarchy.

## Consequences

### Positive
- Zero NPM / zero bundler constraints remain strictly intact (Vanilla ES6).
- Eliminates the `contenteditable` caret reset and non-breaking space parser crash defects.
- Developers can edit parent envelope routing fields and child XML/CDATA payloads simultaneously without collapsing or escaping either.
- Multi-layer edits are staged safely without premature wire-encoding race conditions.

### Negative / Trade-offs
- Parent slices must preserve token island delimiters (`__LENS_TOKEN_N__`) during formatting.
- Re-indentation of parent text must coordinate with child island gutter indentation lines.
