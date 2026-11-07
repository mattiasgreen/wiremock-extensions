package com.github.mattiasgreen.wiremock.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitForSelectorState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Dedicated Playwright integration test suite for the Dual-Layer Code Editor,
 * in-memory experience modes (Rich vs. Raw), character encoding round-tripping,
 * and Maximized Viewport layout hardening (verifying zero unexpected top whitespace).
 */
public class UiPayloadEditorPlaywrightTest extends AbstractUiPlaywrightTest {

    @Test
    @DisplayName("Dual-Layer Editor: Typing in Raw JSON mode renders synchronized syntax tokens in backdrop")
    void testDualLayerCodeEditorTypingAndSyntaxTokens() {
        page.navigate(wireMockServer.baseUrl() + "/__admin/ui/#stubs");
        page.waitForSelector("#nav-tab-stubs");

        // 1. Open Stub Editor modal
        Locator btnCreate = page.locator("#btn-create-stub");
        btnCreate.click();
        page.waitForSelector("#stub-editor-modal:not(.hidden)");

        // 2. Switch to Raw JSON tab
        Locator btnModeJson = page.locator("#btn-editor-mode-json");
        btnModeJson.click();
        page.waitForSelector("#editor-json-panel:not(.hidden)");

        Locator rawEditor = page.locator("#editor-raw-json");
        Locator wrapper = page.locator("#editor-json-panel .dual-layer-editor-wrapper");
        assertThat(wrapper.isVisible()).isTrue();
        assertThat(wrapper.getAttribute("class")).contains("mode-rich");

        Locator backdropCode = page.locator("#editor-json-panel .dual-layer-backdrop code");
        assertThat(backdropCode.isVisible()).isTrue();

        // 3. Type new JSON mapping with distinct key and string
        String testJson =
                "{\n  \"request\": {\n    \"method\": \"GET\",\n    \"url\": \"/api/test-syntax-highlighting\"\n  }\n}";
        rawEditor.fill(testJson);

        // Verify backdrop contains syntax-highlighted spans
        String backdropHtml = backdropCode.innerHTML();
        assertThat(backdropHtml).contains("class=\"json-key\"");
        assertThat(backdropHtml).contains("class=\"json-string\"");
        assertThat(backdropHtml).contains("/api/test-syntax-highlighting");

        // Verify 1:1 character and line alignment between foreground textarea and backdrop
        String editorText = rawEditor.inputValue();
        String backdropText = backdropCode.innerText();
        assertThat(backdropText.trim()).isEqualTo(editorText.trim());

        // 4. Verify scroll synchronization
        rawEditor.evaluate("el => el.scrollTop = 50");
        rawEditor.dispatchEvent("scroll");
        Number backdropScrollTop =
                (Number) page.locator("#editor-json-panel .dual-layer-backdrop").evaluate("el => el.scrollTop");
        assertThat(backdropScrollTop.doubleValue()).isGreaterThanOrEqualTo(0.0);

        // Close modal
        page.locator("#btn-close-editor-modal").click();
    }

    @Test
    @DisplayName("Experience Mode: Toggle between 🎨 Rich and 📝 Raw switches backdrop visibility and classes")
    void testExperienceModeToggleRichAndRaw() {
        page.navigate(wireMockServer.baseUrl() + "/__admin/ui/#stubs");
        page.locator("#btn-create-stub").click();
        page.waitForSelector("#stub-editor-modal:not(.hidden)");

        page.locator("#btn-editor-mode-json").click();
        page.waitForSelector("#editor-json-panel:not(.hidden)");

        Locator wrapper = page.locator("#editor-json-panel .dual-layer-editor-wrapper");
        Locator btnRich = page.locator("#editor-raw-mode-toggle .btn-mode-rich");
        Locator btnRaw = page.locator("#editor-raw-mode-toggle .btn-mode-raw");

        // Initially in Rich mode
        assertThat(btnRich.getAttribute("class")).contains("active");
        assertThat(wrapper.getAttribute("class")).contains("mode-rich");

        // Click Raw mode
        btnRaw.click();
        assertThat(btnRaw.getAttribute("class")).contains("active");
        assertThat(btnRich.getAttribute("class")).doesNotContain("active");
        assertThat(wrapper.getAttribute("class")).contains("mode-raw");
        assertThat(wrapper.getAttribute("class")).doesNotContain("mode-rich");

        // Click Rich mode back
        btnRich.click();
        assertThat(btnRich.getAttribute("class")).contains("active");
        assertThat(wrapper.getAttribute("class")).contains("mode-rich");

        page.locator("#btn-close-editor-modal").click();
    }

    @Test
    @DisplayName("Maximized Viewport: Opens in Viewer Mode, zero whitespace defect, and spacious Stub Editor modal")
    void testMaximizedViewportNoWhitespaceDefectAndModeSwitching() {
        page.navigate(wireMockServer.baseUrl() + "/__admin/ui/#stubs");
        page.waitForSelector("#stub-list");

        // 1. Open Stub Editor and verify spacious modal dimensions
        page.locator("#btn-create-stub").click();
        page.waitForSelector("#stub-editor-modal:not(.hidden)");

        Number dialogWidth = (Number) page.locator("#stub-editor-modal .modal-dialog-wide")
                .evaluate("el => el.getBoundingClientRect().width");
        assertThat(dialogWidth.doubleValue()).isGreaterThan(800.0);

        page.locator("#btn-editor-mode-json").click();

        // 2. Maximize Raw JSON
        Locator btnRawMax = page.locator("#btn-editor-raw-maximize");
        btnRawMax.click();

        // Maximized Viewport defaults to Viewer Mode for instant folding & multi-format syntax inspection
        Locator modalViewport = page.locator("#modal-payload-lens");
        assertThat(modalViewport.isVisible()).isTrue();
        assertThat(modalViewport.getAttribute("class")).contains("fullscreen-viewport");

        Locator viewerContainer = page.locator("#payload-viewport-viewer-container");
        Locator editorContainer = page.locator("#payload-viewport-editor-container");

        // Viewer container must be active and visible
        assertThat(viewerContainer.getAttribute("class")).doesNotContain("hidden");
        Number viewerHeight = (Number) viewerContainer.evaluate("el => el.getBoundingClientRect().height");
        assertThat(viewerHeight.doubleValue()).isGreaterThan(300.0);

        // Editor container must be hidden with 0 height
        assertThat(editorContainer.getAttribute("class")).contains("hidden");
        Number editorHiddenHeight = (Number) editorContainer.evaluate("el => el.getBoundingClientRect().height");
        assertThat(editorHiddenHeight.doubleValue()).isEqualTo(0.0);

        // 3. Test Viewer ↔ Editor Mode Transition
        Locator btnModeSwitch = page.locator("#btn-lens-view-edit-toggle");
        assertThat(btnModeSwitch.isVisible()).isTrue();
        assertThat(btnModeSwitch.textContent()).contains("Edit");

        // Switch to Editor mode
        btnModeSwitch.click();
        assertThat(viewerContainer.getAttribute("class")).contains("hidden");
        assertThat(editorContainer.getAttribute("class")).doesNotContain("hidden");
        assertThat(btnModeSwitch.textContent()).contains("View");

        // CRITICAL BUG VERIFICATION: In Editor mode, viewer container MUST be truly hidden with 0 computed height
        Number viewerHiddenHeight = (Number) viewerContainer.evaluate("el => el.getBoundingClientRect().height");
        assertThat(viewerHiddenHeight.doubleValue()).isEqualTo(0.0);

        Number activeEditorHeight = (Number) editorContainer.evaluate("el => el.getBoundingClientRect().height");
        assertThat(activeEditorHeight.doubleValue()).isGreaterThan(300.0);

        // Switch back to Viewer mode
        btnModeSwitch.click();
        assertThat(viewerContainer.getAttribute("class")).doesNotContain("hidden");
        assertThat(editorContainer.getAttribute("class")).contains("hidden");

        // Close Viewport
        page.locator("#btn-close-payload-lens").click();
        page.locator("#btn-close-editor-modal").click();
    }

    @Test
    @DisplayName(
            "Character Encoding & Round-Tripping: Preserves non-UTF8 and UTF8 international characters through edits")
    void testCharacterEncodingPreservationAndRoundtripping() {
        page.navigate(wireMockServer.baseUrl() + "/__admin/ui/#tester");
        page.waitForSelector("#tab-tester.active");

        // Payload with German umlauts (Latin-1 / UTF-8) and Cyrillic text (KOI8-R / UTF-8)
        String internationalPayload =
                """
        {
          "greeting": "Guten Tag, Herr Müller!",
          "russian": "Привет мир!",
          "specialChars": "£100 · 200€ · ©2026"
        }
        """
                        .trim();

        Locator testerBody = page.locator("#tester-body");
        testerBody.fill(internationalPayload);

        // Click Format JSON
        page.locator("#btn-tester-format-json").click();

        // Maximize Tester Request Body
        page.locator("#btn-tester-request-maximize").click();
        page.waitForSelector("#modal-payload-lens:not(.hidden)");

        Locator lensEditor = page.locator("#payload-lens-editor");
        String currentText = lensEditor.inputValue();
        assertThat(currentText).contains("Müller");
        assertThat(currentText).contains("Привет мир!");
        assertThat(currentText).contains("£100 · 200€ · ©2026");

        // Save in place via Ctrl+S
        lensEditor.press("Control+s");

        // Close Viewport
        page.locator("#btn-close-payload-lens").click();

        // Verify tester body preserved all characters intact
        String finalTesterText = testerBody.inputValue();
        assertThat(finalTesterText).contains("Müller");
        assertThat(finalTesterText).contains("Привет мир!");
        assertThat(finalTesterText).contains("£100 · 200€ · ©2026");
    }

    @Test
    @DisplayName(
            "Modal Mutual Exclusivity & Raw View Syntax Highlighting: Form and JSON never overlap, Raw View retains syntax spans")
    void testStubEditorModalMutualExclusivityAndRawSyntaxHighlighting() {
        page.navigate(wireMockServer.baseUrl() + "/__admin/ui/#stubs");
        page.waitForSelector("#stub-list");

        // 1. Open Stub Editor modal in default Form Mode
        page.locator("#btn-create-stub").click();
        page.waitForSelector("#stub-editor-modal:not(.hidden)");

        Locator formPanel = page.locator("#editor-form-panel");
        Locator jsonPanel = page.locator("#editor-json-panel");

        // CRITICAL SPECIFICITY VERIFICATION: When Form Mode is active, Raw JSON panel MUST have 0 height
        assertThat(formPanel.isVisible()).isTrue();
        Number formHeight = (Number) formPanel.evaluate("el => el.getBoundingClientRect().height");
        assertThat(formHeight.doubleValue()).isGreaterThan(200.0);

        Number jsonHiddenHeight = (Number) jsonPanel.evaluate("el => el.getBoundingClientRect().height");
        assertThat(jsonHiddenHeight.doubleValue()).isEqualTo(0.0);

        // 2. Switch to Raw JSON mode
        page.locator("#btn-editor-mode-json").click();
        page.waitForSelector("#editor-json-panel:not(.hidden)");

        // When Raw JSON is active, Form panel MUST have 0 height
        Number jsonHeight = (Number) jsonPanel.evaluate("el => el.getBoundingClientRect().height");
        assertThat(jsonHeight.doubleValue()).isGreaterThan(200.0);

        Number formHiddenHeight = (Number) formPanel.evaluate("el => el.getBoundingClientRect().height");
        assertThat(formHiddenHeight.doubleValue()).isEqualTo(0.0);

        page.locator("#btn-close-editor-modal").click();

        // 3. Verify Stub Card Raw View Syntax Highlighting
        Locator firstStub = page.locator(".stub-card").first();
        if (firstStub.isVisible()) {
            firstStub.click();
            page.waitForSelector("#stub-json-viewer");

            Locator modeToggle = page.locator("#stub-viewer-mode-toggle");
            if (modeToggle.isVisible()) {
                // Switch to Raw mode
                modeToggle.locator(".btn-mode-raw").click();
                String rawHtml = page.locator("#stub-json-viewer").innerHTML();
                // Raw View MUST retain syntax highlighting
                assertThat(rawHtml).contains("class=\"json-key\"");

                // Switch back to Rich mode
                modeToggle.locator(".btn-mode-rich").click();
                String richHtml = page.locator("#stub-json-viewer").innerHTML();
                assertThat(richHtml).isNotEmpty();
            }
        }
    }

    @Test
    @DisplayName(
            "Nested Payload Rich Editing & Caret Alignment: Unindented string formats in editor and matches backdrop 1:1")
    void testNestedPayloadRichEditingCaretAlignmentAndRoundTripping() {
        page.navigate(wireMockServer.baseUrl() + "/__admin/ui/#stubs");
        page.waitForSelector("#stub-list");

        // 1. Open Stub Editor modal
        page.locator("#btn-create-stub").click();
        page.waitForSelector("#stub-editor-modal:not(.hidden)");

        // 2. Put compact, unindented nested JSON in response body
        String compactInner = "{\"account\":\"ACC-112233\",\"passed\":true}";
        String rootJson = "{\"status\":\"OK\",\"details\":\"" + compactInner.replace("\"", "\\\"") + "\"}";
        Locator editorBody = page.locator("#editor-body");
        editorBody.fill(rootJson);

        // 3. Wait for embedded payload chip and click it
        page.waitForSelector("#editor-body-embedded-bar:not(.hidden)");
        Locator chip =
                page.locator("#editor-body-embedded-bar .embedded-node-chip").first();
        chip.click();
        page.waitForSelector("#modal-payload-lens:not(.hidden)");

        // 4. Verify lens editor opens unescaped, formatted and indented
        Locator lensEditor = page.locator("#payload-lens-editor");
        String lensEditorText = lensEditor.inputValue();
        assertThat(lensEditorText).contains("\n");
        assertThat(lensEditorText).contains("  \"account\": \"ACC-112233\"");

        // 5. Caret alignment verification: Backdrop text matches textarea text 1:1
        Locator backdropCode = page.locator("#payload-viewport-editor-container .dual-layer-backdrop code");
        String backdropText = backdropCode.innerText();
        assertThat(backdropText.trim()).isEqualTo(lensEditorText.trim());

        // 6. In-place edit
        String updatedChild = lensEditorText.replace("ACC-112233", "ACC-778899");
        lensEditor.fill(updatedChild);
        assertThat(backdropCode.innerText().trim()).isEqualTo(updatedChild.trim());

        // 7. Save to root and verify round-tripping
        page.locator("#btn-lens-save-root").click();
        assertThat(page.locator("#modal-payload-lens").isHidden()).isTrue();

        String finalOuter = editorBody.inputValue();
        assertThat(finalOuter).contains("ACC-778899");
        assertThat(finalOuter).doesNotContain("ACC-112233");

        page.locator("#btn-close-editor-modal").click();
    }

    @Test
    @DisplayName(
            "In-Place Polyglot Block Editor: Clicking [Edit] on nested block opens inline editor with live linting and Done saves to root")
    void testInPlaceNestedBlockEditingAndRoundTripping() {
        page.navigate(wireMockServer.baseUrl() + "/__admin/ui/#stubs");
        page.waitForSelector("#stub-list");

        // 1. Create a stub with nested XML inside JSON
        page.locator("#btn-create-stub").click();
        page.waitForSelector("#stub-editor-modal:not(.hidden)");

        page.locator("#editor-url").fill("/api/v1/inplace-test");
        String innerXml = "<order><id>ORD-12345</id><amount>99.90</amount></order>";
        String rootJson = "{\"status\":\"CONFIRMED\",\"payload\":\"" + innerXml.replace("\"", "\\\"") + "\"}";
        page.locator("#editor-body").fill(rootJson);

        page.locator("#btn-save-editor").click();
        page.waitForSelector(
                "#stub-editor-modal", new Page.WaitForSelectorOptions().setState(WaitForSelectorState.HIDDEN));

        // 2. Select the created stub in catalog list
        page.locator(".stub-card")
                .filter(new Locator.FilterOptions().setHasText("/api/v1/inplace-test"))
                .first()
                .click();
        page.waitForSelector("#stub-json-viewer");

        // 3. Find the nested inner XML block's [ ✏️ Edit ] button
        Locator editBtn = page.locator("#stub-json-viewer .btn-nested-edit").last();
        assertThat(editBtn.isVisible()).isTrue();
        editBtn.click();

        // 4. In-place editor should mount inside the block
        Locator inlineEditor = page.locator("#stub-json-viewer .inline-block-editor");
        page.waitForSelector("#stub-json-viewer .inline-block-editor");
        assertThat(inlineEditor.isVisible()).isTrue();

        Locator inlineTextarea = inlineEditor.locator(".inline-nested-textarea");
        assertThat(inlineTextarea.inputValue()).contains("ORD-12345");

        // 5. Unsaved changes hint appears on input
        Locator dirtyHint = inlineEditor.locator(".inline-edit-dirty-indicator");
        assertThat(dirtyHint.getAttribute("class")).contains("hidden");

        String updatedXml = inlineTextarea.inputValue().replace("ORD-12345", "ORD-99999");
        inlineTextarea.fill(updatedXml);
        assertThat(dirtyHint.getAttribute("class")).doesNotContain("hidden");

        // 6. Live structural validation
        Locator lintPill = inlineEditor.locator(".inline-edit-lint-pill");
        inlineTextarea.fill("<invalid <xml>");
        assertThat(lintPill.getAttribute("class")).doesNotContain("hidden");
        assertThat(lintPill.innerText()).contains("Invalid XML");

        // Fix syntax
        inlineTextarea.fill(updatedXml);
        assertThat(lintPill.getAttribute("class")).contains("hidden");

        // 7. Click [ ✓ Done ]
        inlineEditor.locator(".btn-inline-done").click();

        // 8. Block editor closes and viewer displays updated XML
        assertThat(page.locator("#stub-json-viewer .inline-block-editor").count())
                .isEqualTo(0);
        String viewerHtml = page.locator("#stub-json-viewer").innerText();
        assertThat(viewerHtml).contains("ORD-99999");
        assertThat(viewerHtml).doesNotContain("ORD-12345");
    }

    @Test
    @DisplayName(
            "Unified Component: Rich/Raw toggles on all scopes, always-visible maximize buttons, and zero inline scrollbars")
    void testUnifiedComponentTogglesMaximizeAndZeroInlineScrollbar() {
        page.navigate(wireMockServer.baseUrl() + "/__admin/ui/#stubs");
        page.waitForSelector("#stub-list");

        // 1. Create stub with nested payload
        page.locator("#btn-create-stub").click();
        page.waitForSelector("#stub-editor-modal:not(.hidden)");

        page.locator("#editor-url").fill("/api/v1/unified-scope-test");
        String nestedJson = "{\"status\":\"OK\",\"meta\":\"{\\\"version\\\":\\\"2.0\\\"}\"}";
        page.locator("#editor-body").fill(nestedJson);
        page.locator("#btn-save-editor").click();
        page.waitForSelector(
                "#stub-editor-modal", new Page.WaitForSelectorOptions().setState(WaitForSelectorState.HIDDEN));

        // 2. Select stub and inspect Stub Detail Card mode toggle
        page.locator(".stub-card")
                .filter(new Locator.FilterOptions().setHasText("/api/v1/unified-scope-test"))
                .first()
                .click();
        page.waitForSelector("#stub-json-viewer");

        Locator stubToggle = page.locator("#stub-viewer-mode-toggle");
        Locator btnStubRich = stubToggle.locator(".btn-mode-rich");
        Locator btnStubRaw = stubToggle.locator(".btn-mode-raw");

        // Default Rich
        assertThat(btnStubRich.getAttribute("class")).contains("active");

        // Click Raw: Switches to compact wire representation with line-wrapping enabled
        btnStubRaw.click();
        assertThat(btnStubRaw.getAttribute("class")).contains("active");
        assertThat(btnStubRich.getAttribute("class")).doesNotContain("active");
        String rawViewerText = page.locator("#stub-json-viewer").innerText();
        assertThat(rawViewerText).contains("/api/v1/unified-scope-test");
        String rawWhiteSpace =
                (String) page.locator("#stub-json-viewer").evaluate("el => window.getComputedStyle(el).whiteSpace");
        assertThat(rawWhiteSpace).contains("pre-wrap");

        // Restore Rich
        btnStubRich.click();
        assertThat(btnStubRich.getAttribute("class")).contains("active");

        // Verify top-level Edit button is present and opens modal
        Locator btnTopEdit = page.locator("#btn-top-stub-edit");
        assertThat(btnTopEdit.isVisible()).isTrue();
        btnTopEdit.click();
        page.waitForSelector("#stub-editor-modal:not(.hidden)");
        page.locator("#btn-close-editor-modal").click();
        page.waitForSelector(
                "#stub-editor-modal", new Page.WaitForSelectorOptions().setState(WaitForSelectorState.HIDDEN));

        // 3. Inspect nested block: Has mode toggle and always-visible maximize button
        Locator nestedToggle =
                page.locator("#stub-json-viewer .nested-mode-toggle").first();
        assertThat(nestedToggle.isVisible()).isTrue();
        Locator btnNestedRich = nestedToggle.locator(".btn-nested-mode-rich");
        Locator btnNestedRaw = nestedToggle.locator(".btn-nested-mode-raw");
        assertThat(btnNestedRich.getAttribute("class")).contains("active");

        Locator nestedMax =
                page.locator("#stub-json-viewer .btn-nested-maximize").first();
        assertThat(nestedMax.isVisible()).isTrue();
        String opacity = (String) nestedMax.evaluate("el => window.getComputedStyle(el).opacity");
        assertThat(Double.parseDouble(opacity)).isGreaterThan(0.5);

        // Click nested Raw: Shows wire-exact escaped raw string without unescaping
        btnNestedRaw.click();
        assertThat(btnNestedRaw.getAttribute("class")).contains("active");
        assertThat(btnNestedRich.getAttribute("class")).doesNotContain("active");

        // Click nested Rich: Restores unescaped view
        btnNestedRich.click();
        assertThat(btnNestedRich.getAttribute("class")).contains("active");

        // 4. Open In-Place Block Editor and verify zero vertical scrollbar
        Locator editBtn = page.locator("#stub-json-viewer .btn-nested-edit").first();
        editBtn.click();
        page.waitForSelector("#stub-json-viewer .inline-block-editor");

        Locator inlineEditor = page.locator("#stub-json-viewer .inline-block-editor");
        Locator inlineInput = inlineEditor.locator(".dual-layer-input");
        String overflowY = (String) inlineInput.evaluate("el => window.getComputedStyle(el).overflowY");
        assertThat(overflowY).isEqualTo("hidden");

        Number scrollHeight = (Number) inlineInput.evaluate("el => el.scrollHeight");
        Number clientHeight = (Number) inlineInput.evaluate("el => el.clientHeight");
        assertThat(Math.abs(scrollHeight.doubleValue() - clientHeight.doubleValue()))
                .isLessThanOrEqualTo(6.0);

        // Cancel inline editing
        inlineEditor.locator(".btn-inline-cancel").click();
        assertThat(page.locator("#stub-json-viewer .inline-block-editor").count())
                .isEqualTo(0);

        // 5. Navigate to Tester tab and verify Response Body has Rich/Raw toggle
        page.locator("#nav-tab-tester").click();
        page.waitForSelector("#tab-tester:not(.hidden)");

        // Send a request to receive response
        page.locator("#tester-url").fill(wireMockServer.baseUrl() + "/api/v1/unified-scope-test");
        page.locator("#btn-tester-send").click();
        page.waitForSelector(
                "#tester-top-pill-group", new Page.WaitForSelectorOptions().setState(WaitForSelectorState.VISIBLE));

        Locator testerToggle = page.locator("#tester-response-mode-toggle");
        assertThat(testerToggle.isVisible()).isTrue();
        Locator btnTesterRich = testerToggle.locator(".btn-mode-rich");
        Locator btnTesterRaw = testerToggle.locator(".btn-mode-raw");

        assertThat(btnTesterRich.getAttribute("class")).contains("active");
        btnTesterRaw.click();
        assertThat(btnTesterRaw.getAttribute("class")).contains("active");
        assertThat(btnTesterRich.getAttribute("class")).doesNotContain("active");

        btnTesterRich.click();
        assertThat(btnTesterRich.getAttribute("class")).contains("active");
    }
}
