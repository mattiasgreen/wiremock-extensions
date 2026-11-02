# 6. Browser Chrome DevTools Protocol (CDP) UI Performance Quality Gate

Date: 2026-11-02

## Status

Accepted

## Context

As the embedded Web Console expanded with interactive panels (Stub Catalog, Request Journal, HTTP Tester, Casual XATMI Tester, Stateful Ast Simulation, and Fullscreen Maximized Viewports), client-side performance under high volume and large payloads became critical:

1. **Unbounded DOM Node Expansion**: Syntax highlighting deeply nested or voluminous JSON/XML responses via naive regex span wrapping created over 40,000 DOM `<span>` elements for a modest 30 KB payload.
2. **Main-Thread Freezes (Long Tasks)**: Parsing, styling, and laying out tens of thousands of dynamic DOM nodes triggered multi-hundred-millisecond (>600 ms) blocking Long Tasks on the browser UI thread, freezing tab switching and button interactions.
3. **Eager Repaints & Layout Thrashing**: Interleaved DOM tree mutations and layout queries without CSS containment triggered excessive reflow passes (`LayoutCount > 400`) and thousands of style recalculations.
4. **Lack of Automated Enforcement**: Conventional end-to-end tests only verified functional presence (`isVisible()`, `hasText()`), allowing severe rendering performance regressions to slip into production unnoticed.

## Decision

We introduce an automated **Browser CDP Performance Quality Gate** integrated directly into the outer loop test suite:

1. **In-Process Headless Chromium CDP Profiling**:
   - `UiPerformancePlaywrightTest` connects directly to the underlying headless Chromium browser via the Chrome DevTools Protocol (`CDPSession`).
   - Enables the `Performance` domain to collect authoritative V8/Blink layout reflow counts (`LayoutCount`), style recalculation passes (`RecalcStyleCount`), cumulative layout durations (`LayoutDuration`), and memory usage (`JSHeapUsedSize`).
   - Enables the V8 sampling `Profiler` domain (`Profiler.start` / `Profiler.stop`) to record function-level CPU hit distributions and execution bottlenecks.
   - Injects a standard in-page `PerformanceObserver` to track all Long Tasks (`entryType: 'longtask'`) exceeding 50 ms.

2. **Payload Size Gating for Syntax Highlighting**:
   - `MAX_HIGHLIGHT_PAYLOAD_CHARS` is set to 20 KB (~500 lines).
   - Payloads under this threshold are color-coded with full semantic spans (`.json-key`, `.json-string`, `.json-number`, etc.).
   - Payloads exceeding this threshold bypass tokenized span generation and render safely as escaped text within the `<pre class="code-block">` container, capping DOM element creation to `< 5,000` nodes and eliminating UI thread freezes.

3. **CSS Layout Containment (`contain: content`)**:
   - Applied `contain: content` to `.code-block` and `.code-block-body` containers.
   - Isolates internal payload DOM tree mutations from triggering document-level reflows and style recalculation cascades across surrounding navigation tabs and sidebars.

4. **Hard Outer Loop Performance Budgets**:
   - Every `./gradlew check` and CI run strictly enforces:
     - **Zero Long Tasks**: `assertThat(longTasks).isEmpty()` during user interactions (tab switching, payload formatting, response rendering, and inspector tab toggling).
     - **DOM Node Budget**: Total active document DOM nodes must remain under 10,000 elements (`assertThat(nodes).isLessThan(10000.0)`).
     - **Cumulative Layout Duration**: Total time spent in browser layout during editor/viewer workflows must remain under 1.0 second (`assertThat(layoutDuration).isLessThan(1.0)`).
     - **Heap Memory Limit**: Active V8 JS heap must remain well below 50 MB.

## Consequences

- **Guaranteed UI Responsiveness**: Any new UI feature, highlighter modification, or layout reflow bug that causes a main thread freeze (>50 ms) or DOM explosion immediately breaks CI.
- **Fast Test Execution**: The entire CDP performance test suite completes in under 3 seconds in headless Chromium.
- **Predictable Scalability**: Large stub bodies and live journal responses render smoothly without lag or memory leaks.
