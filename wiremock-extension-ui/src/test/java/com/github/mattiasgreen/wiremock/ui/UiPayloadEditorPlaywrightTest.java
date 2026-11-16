package com.github.mattiasgreen.wiremock.ui;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitForSelectorState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Dedicated Playwright E2E test suite for the unified Document Workspace (#modal-document-workspace),
 * beta CSS Custom Highlight API editor, legacy Dual-Layer fallback engine, section splicing,
 * wire-style preservation, non-UTF-8 encoding fidelity, validation/force-apply, and dirty confirm prompts.
 */
public class UiPayloadEditorPlaywrightTest extends AbstractUiPlaywrightTest {

    @Test
    @DisplayName(
            "Document Workspace Beta Editor: highlight-api typing, Tab/Enter auto-indent, and CSS.highlights tokens")
    void testBetaHighlightEditorTypingTabEnterAndTokens() {
        page.navigate(wireMockServer.baseUrl() + "/__admin/ui/#stubs");
        page.waitForSelector("#nav-tab-stubs");

        openNewStubModal();
        page.locator("#btn-workspace-edit-root").click();
        page.waitForSelector("#workspace-editor-pane:not(.hidden)");

        // Default engine is highlight-api (Beta)
        Locator betaBtn = page.locator("#btn-editor-engine-beta");
        assertThat(betaBtn.getAttribute("class")).contains("active");

        Locator surface = page.locator("#workspace-code-editor-host .code-editor-surface.engine-highlight-api");
        assertThat(surface.isVisible()).isTrue();
        Locator preInput = surface.locator("pre.code-editor-pre[data-testid='code-editor-input']");
        assertThat(preInput.isVisible()).isTrue();

        // Populate JSON and verify CSS.highlights token groups are populated
        String testJson =
                "{\n  \"name\": \"Highlight API Stub\",\n  \"request\": {\n    \"method\": \"GET\",\n    \"url\": \"/api/test-syntax-highlighting\"\n  },\n  \"response\": {\n    \"status\": 200,\n    \"jsonBody\": {\n      \"ok\": true,\n      \"count\": 42\n    }\n  }\n}";
        preInput.fill(testJson);
        assertThat(getCodeEditorContent()).contains("/api/test-syntax-highlighting");

        Boolean hasKeyHighlight = (Boolean)
                page.evaluate(
                        "() => typeof CSS !== 'undefined' && Boolean(CSS.highlights) && CSS.highlights.has('wm-hl-key') && CSS.highlights.get('wm-hl-key').size > 0");
        assertThat(hasKeyHighlight).isTrue();

        // Test real Tab and Enter auto-indentation inside <pre contenteditable="plaintext-only">
        preInput.fill("{}");
        page.evaluate(
                """
            () => {
                const pre = document.querySelector('#workspace-code-editor-host [data-testid="code-editor-input"]');
                const textNode = pre.firstChild;
                const sel = window.getSelection();
                const range = document.createRange();
                range.setStart(textNode, 1);
                range.collapse(true);
                sel.removeAllRanges();
                sel.addRange(range);
                pre.focus();
            }
        """);
        preInput.press("Enter");
        assertThat(getCodeEditorContent()).isEqualTo("{\n  }");

        preInput.press("Tab");
        assertThat(getCodeEditorContent()).isEqualTo("{\n    }");

        // Discard dirty section and close cleanly via #modal-confirm
        page.locator("#btn-close-workspace").click();
        acceptConfirmModal();
        page.waitForSelector("#workspace-viewer-pane:not(.hidden)");
        page.locator("#btn-close-workspace").click();
        page.waitForSelector(
                "#modal-document-workspace", new Page.WaitForSelectorOptions().setState(WaitForSelectorState.HIDDEN));
    }

    @Test
    @DisplayName("Legacy Engine Hot-Swap: Switching to Legacy Dual-Layer preserves content, syntax spans, and geometry")
    void testLegacyDualLayerHotSwapAndGeometry() {
        page.navigate(wireMockServer.baseUrl() + "/__admin/ui/#stubs");
        page.waitForSelector("#stub-list");

        openNewStubModal();
        page.locator("#btn-workspace-edit-root").click();
        page.waitForSelector("#workspace-editor-pane:not(.hidden)");

        String initialContent = getCodeEditorContent();
        assertThat(initialContent).contains("/api/v1/items");

        // Hot-swap to legacy-duallayer
        Locator legacyBtn = page.locator("#btn-editor-engine-legacy");
        legacyBtn.click();
        assertThat(legacyBtn.getAttribute("class")).contains("active");

        Locator wrapper = page.locator("#workspace-code-editor-host .dual-layer-editor-wrapper");
        assertThat(wrapper.isVisible()).isTrue();
        assertThat(wrapper.getAttribute("class")).contains("mode-rich");
        assertDualLayerGeometry(wrapper, true);

        // Verify content preserved and backdrop contains highlighted spans
        Locator textarea = wrapper.locator("textarea[data-testid='code-editor-input']");
        Locator backdropCode = wrapper.locator(".dual-layer-backdrop code");
        assertThat(textarea.inputValue()).isEqualTo(initialContent);
        assertThat(backdropCode.innerHTML()).contains("class=\"json-key\"");
        assertThat(backdropCode.innerHTML()).contains("class=\"json-string\"");
        assertThat(backdropCode.innerText().trim())
                .isEqualTo(textarea.inputValue().trim());

        // Type inside legacy textarea and test scroll sync
        String editedJson = initialContent.replace("/api/v1/items", "/api/v1/legacy-swapped");
        textarea.fill(editedJson);
        assertThat(backdropCode.innerText().trim()).isEqualTo(editedJson.trim());

        textarea.evaluate("el => el.scrollTop = 20");
        textarea.dispatchEvent("scroll");
        Number backdropScrollTop =
                (Number) wrapper.locator(".dual-layer-backdrop").evaluate("el => el.scrollTop");
        assertThat(backdropScrollTop.doubleValue()).isGreaterThanOrEqualTo(0.0);

        // Hot-swap back to beta engine preserving edited text
        page.locator("#btn-editor-engine-beta").click();
        assertThat(page.locator("#btn-editor-engine-beta").getAttribute("class"))
                .contains("active");
        assertThat(getCodeEditorContent()).contains("/api/v1/legacy-swapped");

        // Switch back without keeping default polluted: discard section & close
        page.locator("#btn-close-workspace").click();
        acceptConfirmModal();
        page.locator("#btn-close-workspace").click();
        page.waitForSelector(
                "#modal-document-workspace", new Page.WaitForSelectorOptions().setState(WaitForSelectorState.HIDDEN));
    }

    @Test
    @DisplayName("Workspace Mutual Exclusivity & Viewer-First Flow: Zero dual-viewport overlap and template switching")
    void testWorkspaceViewerFirstMutualExclusivityAndTemplates() {
        page.navigate(wireMockServer.baseUrl() + "/__admin/ui/#stubs");
        page.waitForSelector("#stub-list");

        openNewStubModal();

        // Modal dialog is fullscreen/wide and starts in DOCUMENT_VIEWER
        Locator modal = page.locator("#modal-document-workspace");
        assertThat(modal.getAttribute("class")).contains("fullscreen-viewport");
        Locator viewerPane = page.locator("#workspace-viewer-pane");
        Locator editorPane = page.locator("#workspace-editor-pane");

        assertThat(viewerPane.isVisible()).isTrue();
        assertThat(((Number) viewerPane.evaluate("el => el.getBoundingClientRect().height")).doubleValue())
                .isGreaterThan(250.0);
        assertThat(editorPane.getAttribute("class")).contains("hidden");
        assertThat(((Number) editorPane.evaluate("el => el.getBoundingClientRect().height")).doubleValue())
                .isEqualTo(0.0);

        // Switch template via #workspace-template-select
        page.locator("#workspace-template-select").selectOption("body-contains");
        assertThat(viewerPane.innerText()).contains("/api/v1/messages");
        assertThat(viewerPane.innerText()).contains("string X");

        // Enter SECTION_EDITOR via #btn-workspace-edit-root -> viewer height must drop to 0
        page.locator("#btn-workspace-edit-root").click();
        assertThat(editorPane.isVisible()).isTrue();
        assertThat(((Number) editorPane.evaluate("el => el.getBoundingClientRect().height")).doubleValue())
                .isGreaterThan(250.0);
        assertThat(viewerPane.getAttribute("class")).contains("hidden");
        assertThat(((Number) viewerPane.evaluate("el => el.getBoundingClientRect().height")).doubleValue())
                .isEqualTo(0.0);

        // Back to Viewer via #btn-workspace-cancel-section (clean)
        page.locator("#btn-workspace-cancel-section").click();
        assertThat(viewerPane.isVisible()).isTrue();
        assertThat(editorPane.getAttribute("class")).contains("hidden");

        // Save template stub
        saveStubWorkspaceAndWaitClosed();
        page.waitForSelector(".stub-card:has-text('Submit Message (Body Contains)')");
    }

    @Test
    @DisplayName(
            "International Encoding & Wire-Style Override: UTF-8 umlauts/Cyrillic and Base64 KOI8-R section round-trip")
    void testInternationalCharsAndKoi8WireFidelity() {
        page.navigate(wireMockServer.baseUrl() + "/__admin/ui/#tester");
        page.waitForSelector("#tab-tester.active");

        String internationalPayload =
                """
        {
          "greeting": "Guten Tag, Herr Müller!",
          "russian": "Привет мир!",
          "specialChars": "£100 · 200€ · ©2026"
        }
        """
                        .trim();

        setTesterRequestBodyViaWorkspace(internationalPayload);

        // Verify viewer preserves international chars and top pill reflects JSON
        assertThat(page.locator("#tester-request-viewer").innerText()).contains("Müller");
        assertThat(page.locator("#tester-request-viewer").innerText()).contains("Привет мир!");
        assertThat(page.locator("#tester-request-viewer").innerText()).contains("£100 · 200€ · ©2026");

        // Also test KOI8-R XML Base64 nested section round-tripping in Stub Document Workspace
        openStubsTab();
        openNewStubModal();

        String koi8B64 = java.util.Base64.getEncoder()
                .encodeToString("<?xml version=\"1.0\" encoding=\"KOI8-R\"?><msg><text>Привет</text></msg>"
                        .getBytes(java.nio.charset.Charset.forName("KOI8-R")));
        String stubWithKoi8 =
                "{\n  \"name\": \"KOI8R Stub\",\n  \"request\": { \"method\": \"GET\", \"url\": \"/api/v1/koi8\" },\n  \"response\": { \"status\": 200, \"body\": "
                        + quoteJson("{\"doc\":\"" + koi8B64 + "\"}") + " }\n}";
        editWorkspaceRootJson(stubWithKoi8);

        // Edit the Base64 KOI8-R XML section directly in viewer
        Locator sectionEditBtns = page.locator("#workspace-viewer-pane .btn-workspace-edit-section");
        assertThat(sectionEditBtns.count()).isGreaterThanOrEqualTo(2);
        sectionEditBtns.last().click();
        page.waitForSelector("#workspace-editor-pane:not(.hidden)");

        String decodedXml = getCodeEditorContent();
        assertThat(decodedXml).contains("KOI8-R");
        assertThat(decodedXml).contains("Привет");

        // Verify #workspace-wire-style-select is visible for nested section edits
        Locator wireStyleSelect = page.locator("#workspace-wire-style-select");
        assertThat(wireStyleSelect.isVisible()).isTrue();
        wireStyleSelect.selectOption("compact");

        setCodeEditorContent(decodedXml.replace("Привет", "Привет!"));
        page.locator("#btn-workspace-apply-section").click();
        page.waitForSelector("#workspace-viewer-pane:not(.hidden)");
        assertThat(page.locator("#workspace-dirty-badge").isVisible()).isTrue();

        saveStubWorkspaceAndWaitClosed();

        // Confirm saved stub on WireMock re-encoded KOI8-R single-byte payload cleanly
        var mappings = wireMockServer.listAllStubMappings().getMappings();
        var saved = mappings.stream()
                .filter(m -> "KOI8R Stub".equals(m.getName()))
                .findFirst()
                .orElseThrow();
        String savedBody = saved.getResponse().getBody();
        assertThat(savedBody).isNotBlank();
    }

    @Test
    @DisplayName(
            "Validation, Force Apply, and Unsaved Changes Confirmation: #modal-confirm protects edits without native dialogs")
    void testValidationForceApplyAndUnsavedConfirmModal() {
        page.navigate(wireMockServer.baseUrl() + "/__admin/ui/#stubs");
        page.waitForSelector("#stub-list");

        openNewStubModal();
        page.locator("#btn-workspace-edit-root").click();
        page.waitForSelector("#workspace-editor-pane:not(.hidden)");

        // Enter invalid JSON
        setCodeEditorContent("{ \"badJson\": ");
        Locator lintPill = page.locator("#workspace-lint-pill");
        Locator banner = page.locator("#workspace-validation-banner");
        Locator applyBtn = page.locator("#btn-workspace-apply-section");
        Locator forceApplyBtn = page.locator("#btn-workspace-force-apply");

        assertThat(lintPill.isVisible()).isTrue();
        assertThat(lintPill.innerText()).contains("Invalid JSON");
        assertThat(banner.isVisible()).isTrue();
        assertThat(applyBtn.isDisabled()).isTrue();
        assertThat(forceApplyBtn.isVisible()).isTrue();

        // Force apply returns to Viewer with dirty badge visible
        forceApplyBtn.click();
        page.waitForSelector("#workspace-viewer-pane:not(.hidden)");
        assertThat(page.locator("#workspace-dirty-badge").isVisible()).isTrue();

        // Attempt to close dirty Viewer -> #modal-confirm opens with Discard Changes secondary option
        page.locator("#btn-close-workspace").click();
        page.waitForSelector("#modal-confirm:not(.hidden)");
        assertThat(page.locator("#confirm-modal-title").textContent()).contains("Unsaved Changes");

        // Cancel first so workspace remains open
        page.locator("#btn-confirm-cancel").click();
        page.waitForSelector("#modal-confirm", new Page.WaitForSelectorOptions().setState(WaitForSelectorState.HIDDEN));
        assertThat(page.locator("#modal-document-workspace").isVisible()).isTrue();

        // Re-try close and discard changes via secondary button
        page.locator("#btn-close-workspace").click();
        page.waitForSelector("#modal-confirm:not(.hidden)");
        page.locator("#btn-confirm-secondary").click();
        page.waitForSelector("#modal-confirm", new Page.WaitForSelectorOptions().setState(WaitForSelectorState.HIDDEN));
        page.waitForSelector(
                "#modal-document-workspace", new Page.WaitForSelectorOptions().setState(WaitForSelectorState.HIDDEN));
    }

    @Test
    @DisplayName(
            "Deep Nested Section Splicing & Wire-Style Preservation: 5-layer SWIFT edit cascades surgically to root and Stub Detail")
    void testInnermostNestedPayloadInLineEditingCascadesToRoot() {
        String bodyJson =
                """
                {
                  "transferId": "SWIFT-2026-X891",
                  "executionTrace": {
                    "gatewayEnvelope": "{\\"routing\\":{\\"network\\":\\"SWIFT_NET\\",\\"bic\\":\\"NDEAFIHH\\"},\\"messagePayload\\":\\"<swiftMessage xmlns=\\\\\\"http://banking.corp/swift\\\\\\"><header><sender>SWIFT-EXT</sender></header><body><![CDATA[{\\\\\\"account\\\\\\":\\\\\\"ACC-112233\\\\\\",\\\\\\"auditTrail\\\\\\\":\\\\\\"{\\\\\\\\\\\\\\"auditor\\\\\\\\\\\\\\":\\\\\\\\\\\\\\"ISO20022-GATEWAY\\\\\\\\\\\\\\",\\\\\\\\\\\\\\"passed\\\\\\\\\\\\\\":true}\\\\\\"}]]></body></swiftMessage>\\"}"
                  }
                }
                """;

        wireMockServer.stubFor(post(urlEqualTo("/api/v1/b2b/swift-transfer"))
                .withName("B2B SWIFT Transfer")
                .willReturn(ok().withHeader("Content-Type", "application/json").withBody(bodyJson)));

        page.navigate(wireMockServer.baseUrl() + "/__admin/ui/#stubs");
        page.waitForSelector("#stub-list");

        // 1. Select stub in catalog and verify Read-Only Stub Detail has zero disabled Edit buttons
        Locator stubCard = page.locator(".stub-card")
                .filter(new Locator.FilterOptions().setHasText("B2B SWIFT Transfer"))
                .first();
        stubCard.click();
        page.waitForSelector("#stub-json-viewer .nested-payload-block");
        assertThat(page.locator("#stub-json-viewer button[disabled]").count()).isEqualTo(0);
        assertThat(page.locator("#stub-json-viewer .btn-workspace-edit-section").count())
                .isEqualTo(0);
        assertThat(page.locator("#stub-json-viewer .btn-nested-open-workspace").count())
                .isGreaterThan(1);

        // Verify Stub Card Rich/Raw toggle
        Locator stubToggle = page.locator("#stub-viewer-mode-toggle");
        stubToggle.locator(".btn-mode-raw").click();
        assertThat(page.locator("#stub-json-viewer").innerHTML()).contains("class=\"json-key\"");
        stubToggle.locator(".btn-mode-rich").click();

        // 2. Open Edit Stub Mapping -> #modal-document-workspace in DOCUMENT_VIEWER
        page.locator("#btn-edit-stub").click();
        waitForStubEditorOpen();
        page.waitForSelector("#workspace-viewer-pane .btn-workspace-edit-section");

        // 3. Edit innermost section (auditTrail inside CDATA inside XML inside JSON inside body)
        Locator allSectionEdits = page.locator("#workspace-viewer-pane .btn-workspace-edit-section");
        assertThat(allSectionEdits.count()).isGreaterThan(1);
        Locator innermostBtn = allSectionEdits.last();
        innermostBtn.scrollIntoViewIfNeeded();
        innermostBtn.click();
        page.waitForSelector("#workspace-editor-pane:not(.hidden)");

        assertThat(getCodeEditorContent()).contains("ISO20022-GATEWAY");
        assertThat(page.locator("#workspace-breadcrumbs").innerText()).contains("auditTrail");

        // Wire style Auto preserves compact encoding without newline explosions
        Locator wireSelect = page.locator("#workspace-wire-style-select");
        assertThat(wireSelect.isVisible()).isTrue();
        assertThat(wireSelect.inputValue()).isEqualTo("auto");

        setCodeEditorContent("{\"auditor\":\"ISO20022-GATEWAYYY\",\"passed\":true}");
        page.locator("#btn-workspace-apply-section").click();
        page.waitForSelector("#workspace-viewer-pane:not(.hidden)");

        // Viewer shows updated leaf and dirty badge
        assertThat(page.locator("#workspace-dirty-badge").isVisible()).isTrue();
        assertThat(page.locator("#workspace-viewer-pane").innerText()).contains("ISO20022-GATEWAYYY");

        // Save mapping and verify persisted in Stub Detail
        saveStubWorkspaceAndWaitClosed();
        page.waitForSelector("#stub-json-viewer:has-text('ISO20022-GATEWAYYY')");
        assertThat(page.locator("#stub-json-viewer").innerText()).contains("ISO20022-GATEWAYYY");
        assertThat(page.locator("#stub-json-viewer").innerText()).doesNotContain("ISO20022-GATEWAY\"");
    }

    @Test
    @DisplayName(
            "Tester & Stub Rich/Raw Modes: #tester-request-viewer, #tester-response-mode-toggle, and Open in Workspace")
    void testTesterWorkspaceAndResponseModeToggles() {
        wireMockServer.stubFor(post(urlEqualTo("/api/v1/mode-toggle-test"))
                .withName("Mode Toggle Stub")
                .willReturn(okJson("{\"status\":\"OK\",\"nested\":\"{\\\"v\\\":2}\"}")));

        page.navigate(wireMockServer.baseUrl() + "/__admin/ui/#tester");
        page.waitForSelector("#tab-tester.active");

        page.locator("#tester-method").selectOption("POST");
        page.locator("#tester-url").fill(wireMockServer.baseUrl() + "/api/v1/mode-toggle-test");
        setTesterRequestBodyViaWorkspace("{\"hello\":\"world\"}");
        assertThat(page.locator("#tester-request-viewer").innerText()).contains("hello");

        page.locator("#btn-tester-send").click();
        page.waitForSelector(
                "#tester-top-pill-group", new Page.WaitForSelectorOptions().setState(WaitForSelectorState.VISIBLE));

        Locator respToggle = page.locator("#tester-response-mode-toggle");
        assertThat(respToggle.isVisible()).isTrue();
        respToggle.locator(".btn-mode-raw").click();
        assertThat(respToggle.locator(".btn-mode-raw").getAttribute("class")).contains("active");
        respToggle.locator(".btn-mode-rich").click();
        assertThat(respToggle.locator(".btn-mode-rich").getAttribute("class")).contains("active");

        // Open Tester Response in Document Workspace (read-only)
        page.locator("#btn-tester-response-maximize").click();
        waitForStubEditorOpen();
        assertThat(page.locator("#workspace-title").textContent()).contains("Tester Response");
        assertThat(page.locator("#btn-workspace-edit-root").isHidden()).isTrue();
        page.locator("#btn-close-workspace").click();
        page.waitForSelector(
                "#modal-document-workspace", new Page.WaitForSelectorOptions().setState(WaitForSelectorState.HIDDEN));
    }

    private static String quoteJson(String raw) {
        return "\""
                + raw.replace("\\", "\\\\")
                        .replace("\"", "\\\"")
                        .replace("\n", "\\n")
                        .replace("\r", "\\r")
                + "\"";
    }
}
