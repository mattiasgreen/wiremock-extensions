package com.github.mattiasgreen.wiremock.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Locator;
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
        setTesterRequestBodyViaWorkspace(initialPayload);
        page.locator("#btn-tester-send").click();

        // Expect 404 since no stub matches yet
        page.waitForSelector("#tester-response-status:has-text('404')");
        assertThat(page.locator("#tester-response-status").textContent()).contains("404");

        // Step 3: Switch to Request Journal and locate the logged unmatched request
        openJournalTab();
        page.waitForSelector(".journal-row");

        Locator orderRow =
                page.locator(".journal-row:has-text('/api/v2/orders')").first();
        assertThat(orderRow.isVisible()).isTrue();
        orderRow.click(); // Expand row detail

        // Step 4: Locate Request Body in Journal and open in Document Workspace
        page.waitForSelector(".btn-journal-maximize[data-type='req']");
        Locator btnJournalMax =
                page.locator(".btn-journal-maximize[data-type='req']").first();
        btnJournalMax.click();

        // Verify Document Workspace opened fullscreen
        Locator workspaceModal = page.locator("#modal-document-workspace");
        assertThat(workspaceModal.isVisible()).isTrue();
        assertThat(workspaceModal.getAttribute("class")).contains("fullscreen-viewport");

        Locator workspaceTitle = page.locator("#workspace-title");
        assertThat(workspaceTitle.textContent()).contains("Journal Request");
        assertThat(page.locator("#workspace-viewer-pane").innerText()).contains("ORD-999");

        // Step 5: Close Workspace and click top-level "Create Stub from Request" on the journal row
        page.locator("#btn-close-workspace").click();
        assertThat(workspaceModal.getAttribute("class")).contains("hidden");

        page.locator(".btn-create-stub-from-req").first().click();

        // Document Workspace opens pre-filled from unmatched request
        waitForStubEditorOpen();
        assertThat(workspaceTitle.textContent()).contains("Create Stub from Journal Request");
        assertThat(page.locator("#workspace-viewer-pane").innerText()).contains("/api/v2/orders");

        // Step 6: Enter Root Section Editor, modify name + status + response body, verify dirty tracking
        page.locator("#btn-workspace-edit-root").click();
        page.waitForSelector("#workspace-editor-pane:not(.hidden)");

        Locator dirtyBadge = page.locator("#workspace-dirty-badge");
        assertThat(dirtyBadge.getAttribute("class")).contains("hidden");

        String updatedStubJson =
                """
                {
                  "name": "Chained Order Invoice Stub",
                  "priority": 5,
                  "request": {
                    "method": "POST",
                    "url": "/api/v2/orders"
                  },
                  "response": {
                    "status": 200,
                    "headers": {
                      "Content-Type": "application/json"
                    },
                    "jsonBody": {
                      "status": "CONFIRMED",
                      "invoiceXml": "<invoice id=\\"999\\"><status>APPROVED</status></invoice>"
                    }
                  }
                }
                """
                        .trim();
        setCodeEditorContent(updatedStubJson);

        // Dirty badge should now be visible
        assertThat(dirtyBadge.isVisible()).isTrue();
        assertThat(dirtyBadge.textContent()).contains("Unsaved Changes");

        // Step 7: Press Escape key while dirty in SECTION_EDITOR -> #modal-confirm opens
        page.keyboard().press("Escape");

        Locator confirmModal = page.locator("#modal-confirm");
        assertThat(confirmModal.isVisible()).isTrue();
        assertThat(page.locator("#confirm-modal-title").textContent()).contains("Discard Section Changes");

        // Cancel keep editing: workspace remains in SECTION_EDITOR and dirty
        page.locator("#btn-confirm-cancel").click();
        assertThat(confirmModal.getAttribute("class")).contains("hidden");
        assertThat(workspaceModal.isVisible()).isTrue();
        assertThat(dirtyBadge.isVisible()).isTrue();

        // Apply Section -> returns to DOCUMENT_VIEWER with updated content and dirty badge
        page.locator("#btn-workspace-apply-section").click();
        page.waitForSelector("#workspace-viewer-pane:not(.hidden)");
        assertThat(page.locator("#workspace-viewer-pane").innerText()).contains("APPROVED");
        assertThat(dirtyBadge.isVisible()).isTrue();

        // Press Escape in dirty DOCUMENT_VIEWER -> #modal-confirm prompts Save / Discard / Cancel; click Save!
        page.keyboard().press("Escape");
        assertThat(confirmModal.isVisible()).isTrue();
        assertThat(page.locator("#confirm-modal-title").textContent()).contains("Unsaved Changes");
        page.locator("#btn-confirm-accept").click();

        // Both modals close and stub mapping is committed to WireMock
        page.waitForSelector(
                "#modal-confirm",
                new com.microsoft.playwright.Page.WaitForSelectorOptions()
                        .setState(com.microsoft.playwright.options.WaitForSelectorState.HIDDEN));
        page.waitForSelector(
                "#modal-document-workspace",
                new com.microsoft.playwright.Page.WaitForSelectorOptions()
                        .setState(com.microsoft.playwright.options.WaitForSelectorState.HIDDEN));

        // Step 9: Verify stub appears in Stubs catalog
        openStubsTab();

        Locator stubCard = page.locator(".stub-card:has-text('Chained Order Invoice Stub')")
                .first();
        page.waitForSelector(".stub-card:has-text('Chained Order Invoice Stub')");
        stubCard.click();

        Locator detailView = page.locator("#stub-detail-view");
        assertThat(detailView.isVisible()).isTrue();

        // Maximize stub mapping from detail view and verify clean Escape closes immediately
        page.locator("#btn-stub-maximize").click();
        assertThat(workspaceModal.isVisible()).isTrue();
        assertThat(page.locator("#workspace-title").textContent()).contains("Chained Order Invoice Stub");

        // Not dirty, so pressing Escape closes without prompt
        page.keyboard().press("Escape");
        assertThat(confirmModal.getAttribute("class")).contains("hidden");
        assertThat(workspaceModal.getAttribute("class")).contains("hidden");

        // Step 10: Re-run in HTTP Tester and verify matching mock response
        openTesterTab();

        page.locator("#btn-tester-send").click();
        page.waitForSelector("#tester-response-status:has-text('200')");
        assertThat(page.locator("#tester-response-status").textContent()).contains("200");

        Locator testerResponseBody = page.locator("#tester-response-body");
        assertThat(testerResponseBody.textContent()).contains("APPROVED");
        assertThat(testerResponseBody.textContent()).contains("invoiceXml");

        // Step 11: Maximize Tester Response Body in Document Workspace
        page.locator("#btn-tester-response-maximize").click();
        assertThat(workspaceModal.isVisible()).isTrue();
        assertThat(page.locator("#workspace-title").textContent()).contains("Tester Response");

        // Press Escape to dismiss cleanly
        page.keyboard().press("Escape");
        assertThat(workspaceModal.getAttribute("class")).contains("hidden");

        // Step 12: Ensure zero browser console errors and page errors throughout entire journey
        assertThat(pageErrors).as("Zero uncaught page errors").isEmpty();
        assertThat(consoleErrors).as("Zero console errors").isEmpty();
    }
}
