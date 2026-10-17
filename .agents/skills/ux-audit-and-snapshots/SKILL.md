---
name: ux-audit-and-snapshots
description: >-
  Use this skill whenever designing, implementing, or reviewing Web UI or proxy console features
  in wiremock-extension-ui. Enforces the 5-Phase Senior Agentic Development Loop: abstract spec
  alignment, platform binding mapping, visual snapshot capture, multimodal pixel inspection,
  and the 5 Lenses of Senior UX Self-Review.
---

# Senior UX Audit & Visual Snapshot Protocol

Autonomous agents must follow this 5-phase discipline for every UI feature or modification in `wiremock-extension-ui`:

```text
[1. Abstract Spec] ──► [2. Platform Bindings] ──► [3. Implementation & E2E]
                                                          │
[5. Senior Reflection] ◄── [4. Visual Snapshot Inspection] ◄──┘
```

## Phase 1: Abstract Product & Functional Spec Alignment
- Never start directly in HTML/JS/CSS without checking [`docs/spec/functional-specification.md`](../../../docs/spec/functional-specification.md).
- Write or update the **Compact E2E User Story** (Given/When/Then format) first.

## Phase 2: Platform Mapping & Implementation
- Bind abstract concepts to concrete DOM IDs/selectors in [`docs/spec/platform-bindings.md`](../../../docs/spec/platform-bindings.md) and [`docs/spec/ui-guidelines.md`](../../../docs/spec/ui-guidelines.md).
- Follow the Zero-NPM / Native ES6 module boundaries in `wiremock-extension-ui/src/main/resources/ui/modules/`.

## Phase 3: Automated Verification (Fastest Loop First)
1. Static contract & asset verification (< 2s):
   `./gradlew :wiremock-extension-ui:test --tests "UiAssetAndContractTest" -q --console=plain`
2. Headless ES module unit tests (< 3s):
   `./gradlew :wiremock-extension-ui:test --tests "UiModuleUnitTest" -q --console=plain`
3. Targeted Playwright E2E test (~5–10s):
   `./gradlew :wiremock-extension-ui:test --tests "Ui*PlaywrightTest.testSpecificMethod" -q --console=plain`

## Phase 4: Visual Capture & Targeted Multimodal Inspection
1. Capture high-resolution PNG snapshots:
   `./gradlew :wiremock-extension-ui:captureUiSnapshots -q --console=plain`
2. **Token-Smart Visual Inspection**: Call `view_file` **only on the specific screenshot(s)** in `wiremock-extension-ui/build/reports/screenshots/web/` affected by your change (do not load all PNGs if only one tab changed).
3. Verify: no clipped text, balanced visual weight, proper badge contrast, and no "button soup" (at most 1 Primary CTA and 2–3 compact secondary actions per header).

## Phase 5: Senior UX & Cognitive Self-Review (The 5 Lenses)
Include a concise **Senior Self-Review Reflection** in your feature summary evaluating:
1. **CRUD & State Lifecycle Completeness (Law of Symmetry)**: Does every filter have a clear action? Does every created state have edit/delete/reset?
2. **The Negative Space (Empty, Error & Boundary States)**: Are 0-item, filter-miss, and offline states actionable?
3. **Cognitive Ergonomics & Visual Hierarchy**: Hick's Law (logical grouping, Primary vs. Secondary vs. Destructive) + Fitts's Law (contextual actions inside the Detail Inspector header, not duplicated per row) + Immediate Sensory Feedback (`✅ Copied!` toast/flash, never blocking `alert()`).
4. **Persona Ergonomics (The 30-Second Rule)**: Can an engineer triage an unmatched `404` or proxied request and turn it into a stub or replay it in Tester in under 30 seconds?
5. **Platform Idioms & Power-User Accelerators**: Keyboard shortcuts (`Ctrl+F`, `Escape`, `Ctrl+Enter`), deep-link URL hash sync, and 1-click clipboard helpers (`cURL`, `stubFor()`).
