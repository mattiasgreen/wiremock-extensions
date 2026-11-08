package com.github.mattiasgreen.wiremock.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitForSelectorState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * End-to-end chained workflow test exercising the complete developer journey:
 * 1. Dispatching unmatched request in HTTP Tester
 * 2. Inspecting Request Journal and opening Fullscreen Maximized Viewport on raw request payload
 * 3. Snapshotting payload as a new stub mapping into the Stub Editor
 * 4. Editing Stub Response Body within the Fullscreen Viewport with dirty-state tracking
 * 5. Testing Escape key handling, unsaved changes confirmation dialog (Cancel, Save)
 * 6. Saving the new stub mapping to WireMock
 * 7. Inspecting Stub catalog detail view with Canonical Payload Surface and maximizing full stub JSON
 * 8. Dispatching matching request in HTTP Tester and verifying mock response wire fidelity
 * 9. Maximizing Tester response and verifying clean Escape dismissal
 */
public class UiChainedWorkflowPlaywrightTest extends AbstractUiPlaywrightTest {

    @Test
    @DisplayName(
            "Chained E2E Journey: Tester -> Journal -> Fullscreen Lens -> Snapshot -> Edit & Dirty Escape -> WireMock Save -> Verify")
    void testChainedDeveloperJourneyAcrossSurfaces() {
        // Step 1: Open HTTP Tester
        page.navigate(wireMockServer.baseUrl() + "/__admin/ui/#tester");
        page.waitForSelector("#tab-tester.active");

        // Step 2: Send unmatched POST request with nested XML inside JSON
        page.locator("#tester-method").selectOption("POST");
        page.locator("#tester-url").fill("/api/v2/orders");
        String initialPayload =
                """
        {
          "orderId": "ORD-999",
          "xmlData": "<order id=\\"999\\"><status>PENDING</status></order>"
        }
        """
                        .trim();
        page.locator("#tester-body").fill(initialPayload);
        page.locator("#btn-tester-send").click();

        // Expect 404 since no stub matches yet
        page.waitForSelector("#tester-response-status:has-text('404')");
        assertThat(page.locator("#tester-response-status").textContent()).contains("404");

        // Step 3: Switch to Request Journal and locate the logged unmatched request
        page.locator("#nav-tab-journal").click();
        page.waitForSelector("#tab-journal.active");
        page.waitForSelector(".journal-row");

        Locator orderRow =
                page.locator(".journal-row:has-text('/api/v2/orders')").first();
        assertThat(orderRow.isVisible()).isTrue();
        orderRow.click(); // Expand row detail

        // Step 4: Locate Request Body in Journal and maximize in Fullscreen Viewport
        page.waitForSelector(".btn-journal-maximize[data-type='req']");
        Locator btnJournalMax =
                page.locator(".btn-journal-maximize[data-type='req']").first();
        btnJournalMax.click();

        // Verify Fullscreen Maximized Viewport opened
        Locator viewportModal = page.locator("#modal-payload-lens");
        assertThat(viewportModal.isVisible()).isTrue();
        assertThat(viewportModal.getAttribute("class")).contains("fullscreen-viewport");

        Locator viewportTitle = page.locator("#payload-lens-title");
        assertThat(viewportTitle.textContent()).contains("Journal Request");

        Locator viewportEditor = page.locator("#payload-lens-editor");
        assertThat(viewportEditor.inputValue()).contains("ORD-999");

        // Step 5: Close Viewport and click top-level "Create Stub from Request" on the journal row
        page.locator("#btn-close-payload-lens").click();
        assertThat(viewportModal.getAttribute("class")).contains("hidden");

        page.locator(".btn-create-stub-from-req").first().click();

        // Stub Editor modal opens
        Locator stubEditorModal = page.locator("#stub-editor-modal");
        assertThat(stubEditorModal.isVisible()).isTrue();

        Locator editorNameInput = page.locator("#editor-name");
        editorNameInput.fill("Chained Order Invoice Stub");

        page.locator("#editor-status").fill("200");

        Locator editorUrlInput = page.locator("#editor-url");
        assertThat(editorUrlInput.inputValue()).isEqualTo("/api/v2/orders");

        // Step 6: Maximize Stub Response Body in Fullscreen Viewport and test dirty tracking
        Locator btnBodyMax = page.locator("#btn-editor-body-maximize");
        btnBodyMax.click();

        // Viewport opens in Stub Editor context
        assertThat(viewportModal.isVisible()).isTrue();
        assertThat(page.locator("#payload-lens-title").textContent()).contains("Stub Response Body");

        // Dirty badge should initially be hidden
        Locator dirtyBadge = page.locator("#payload-lens-dirty-indicator");
        assertThat(dirtyBadge.getAttribute("class")).contains("hidden");

        // Modify content in the viewport
        String updatedResponseBody =
                """
        {
          "status": "CONFIRMED",
          "invoiceXml": "<invoice id=\\"999\\"><status>APPROVED</status></invoice>"
        }
        """
                        .trim();
        viewportEditor.fill(updatedResponseBody);

        // Dirty badge should now be visible
        assertThat(dirtyBadge.isVisible()).isTrue();
        assertThat(dirtyBadge.textContent()).contains("Unsaved Changes");

        // Step 7: Press Escape key -> Unsaved Changes confirmation prompt appears
        page.keyboard().press("Escape");

        Locator unsavedModal = page.locator("#modal-unsaved-changes");
        assertThat(unsavedModal.isVisible()).isTrue();

        // Test Cancel: viewport remains open and dirty
        page.locator("#btn-unsaved-cancel").click();
        assertThat(unsavedModal.getAttribute("class")).contains("hidden");
        assertThat(viewportModal.isVisible()).isTrue();
        assertThat(dirtyBadge.isVisible()).isTrue();

        // Press Escape again and click Save
        page.keyboard().press("Escape");
        assertThat(unsavedModal.isVisible()).isTrue();
        page.locator("#btn-unsaved-save").click();

        // Both modals close and changes are saved back into the Stub Editor
        assertThat(unsavedModal.getAttribute("class")).contains("hidden");
        assertThat(viewportModal.getAttribute("class")).contains("hidden");

        Locator editorBody = page.locator("#editor-body");
        assertThat(editorBody.inputValue()).contains("APPROVED");

        // Step 8: Save stub mapping to WireMock
        page.locator("#btn-save-editor").click();
        page.waitForSelector(
                "#stub-editor-modal", new Page.WaitForSelectorOptions().setState(WaitForSelectorState.HIDDEN));

        // Step 9: Verify stub appears in Stubs catalog
        page.locator("#nav-tab-stubs").click();
        page.waitForSelector("#tab-stub-detail.active");

        Locator stubCard = page.locator(".stub-card:has-text('Chained Order Invoice Stub')")
                .first();
        page.waitForSelector(".stub-card:has-text('Chained Order Invoice Stub')");
        stubCard.click();

        Locator detailView = page.locator("#stub-detail-view");
        assertThat(detailView.isVisible()).isTrue();

        // Maximize stub mapping from detail view and verify clean Escape closes immediately
        page.locator("#btn-stub-maximize").click();
        assertThat(viewportModal.isVisible()).isTrue();
        assertThat(page.locator("#payload-lens-title").textContent()).contains("Chained Order Invoice Stub");

        // Not dirty, so pressing Escape closes without prompt
        page.keyboard().press("Escape");
        assertThat(unsavedModal.getAttribute("class")).contains("hidden");
        assertThat(viewportModal.getAttribute("class")).contains("hidden");

        // Step 10: Re-run in HTTP Tester and verify matching mock response
        page.locator("#nav-tab-tester").click();
        page.waitForSelector("#tab-tester.active");

        page.locator("#btn-tester-send").click();
        page.waitForSelector("#tester-response-status:has-text('200')");
        assertThat(page.locator("#tester-response-status").textContent()).contains("200");

        Locator testerResponseBody = page.locator("#tester-response-body");
        assertThat(testerResponseBody.textContent()).contains("APPROVED");
        assertThat(testerResponseBody.textContent()).contains("invoiceXml");

        // Step 11: Maximize Tester Response Body in Fullscreen Viewport
        page.locator("#btn-tester-response-maximize").click();
        assertThat(viewportModal.isVisible()).isTrue();
        assertThat(page.locator("#payload-lens-title").textContent()).contains("Tester Response");

        // Press Escape to dismiss cleanly
        page.keyboard().press("Escape");
        assertThat(viewportModal.getAttribute("class")).contains("hidden");

        // Step 12: Ensure zero browser console errors and page errors throughout entire journey
        assertThat(pageErrors).as("Zero uncaught page errors").isEmpty();
        assertThat(consoleErrors).as("Zero console errors").isEmpty();
    }
}
