package com.github.mattiasgreen.wiremock.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Locator;
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
}
