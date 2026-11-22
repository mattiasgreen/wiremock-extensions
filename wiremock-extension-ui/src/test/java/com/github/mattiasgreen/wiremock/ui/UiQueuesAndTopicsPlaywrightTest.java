package com.github.mattiasgreen.wiremock.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.mattiasgreen.wiremock.async.engine.AsyncEngine;
import com.github.mattiasgreen.wiremock.async.model.AsyncMessage;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitForSelectorState;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

public class UiQueuesAndTopicsPlaywrightTest extends AbstractUiPlaywrightTest {

    @Test
    @DisplayName("Should navigate to Queues & Topics tab and display protocol filters and destination sidebar")
    void testNavigateToQueuesTab() {
        page.navigate(wireMockServer.baseUrl() + "/__admin/ui/?demo=true#queues");
        page.waitForSelector("#tab-queues.active");

        Locator navTab = page.locator("#nav-tab-queues");
        assertThat(navTab.isVisible()).isTrue();
        assertThat(navTab.textContent()).contains("Queues & Topics");

        Locator sidebar = page.locator("#sidebar-queues");
        assertThat(sidebar.isVisible()).isTrue();

        Locator destItems = page.locator(".queues-dest-item");
        assertThat(destItems.count()).isGreaterThanOrEqualTo(4);
    }

    @Test
    @DisplayName("Should filter destinations by protocol pill and channel search query")
    void testFilterDestinationsInDemoMode() {
        page.navigate(wireMockServer.baseUrl() + "/__admin/ui/?demo=true#queues");
        page.waitForSelector("#tab-queues.active");

        // Click Kafka filter
        page.locator("#btn-filter-proto-kafka").click();
        Locator kafkaItems = page.locator(".queues-dest-item");
        assertThat(kafkaItems.count()).isEqualTo(1);
        assertThat(kafkaItems.first().textContent()).contains("orders.events");
        assertThat(kafkaItems.first().textContent()).contains("KAFKA");

        // Click AMQP filter
        page.locator("#btn-filter-proto-amqp").click();
        Locator amqpItems = page.locator(".queues-dest-item");
        assertThat(amqpItems.count()).isEqualTo(1);
        assertThat(amqpItems.first().textContent()).contains("orders.inbound");
        assertThat(amqpItems.first().textContent()).contains("AMQP");

        // Click Casual filter
        page.locator("#btn-filter-proto-casual").click();
        Locator casualItems = page.locator(".queues-dest-item");
        assertThat(casualItems.count()).isEqualTo(2);
        assertThat(casualItems.first().textContent()).contains("billing.q");
        assertThat(casualItems.first().textContent()).contains("CASUAL");

        // Reset to All
        page.locator("#btn-filter-proto-all").click();
        assertThat(page.locator(".queues-dest-item").count()).isGreaterThanOrEqualTo(4);

        // Search channel by free text
        page.locator("#queues-channel-search").fill("inbound");
        assertThat(page.locator(".queues-dest-item").count()).isEqualTo(1);
        assertThat(page.locator(".queues-dest-item").first().textContent()).contains("orders.inbound");

        page.locator("#queues-channel-search").fill("");
        assertThat(page.locator(".queues-dest-item").count()).isGreaterThanOrEqualTo(4);
    }

    @Test
    @DisplayName("Should inspect peeked messages, filter with boolean queries, and open Document Workspace")
    void testMessageInspectionAndPolyglotLens() {
        page.navigate(wireMockServer.baseUrl() + "/__admin/ui/?demo=true#queues");
        page.waitForSelector("#tab-queues.active");

        // Select Kafka orders destination
        page.locator(".queues-dest-item:has-text('orders.events')").click();
        page.waitForSelector(".queues-message-row");

        Locator rows = page.locator(".queues-message-row");
        assertThat(rows.count()).isEqualTo(1);

        // Test boolean search across payload and keys
        page.locator("#queues-message-search").fill("ORD-9942");
        assertThat(page.locator(".queues-message-row").count()).isEqualTo(1);

        page.locator("#queues-message-search").fill("Acme AND 1250");
        assertThat(page.locator(".queues-message-row").count()).isEqualTo(1);

        page.locator("#queues-message-search").fill("nonexistent_token_999");
        assertThat(page.locator(".queues-message-row").count()).isEqualTo(0);
        assertThat(page.locator("#queues-messages-tbody").textContent()).contains("No messages found");

        page.locator("#queues-message-search").fill("");
        assertThat(page.locator(".queues-message-row").count()).isEqualTo(1);

        // Click Lens on the first row -> opens Document Workspace
        Locator firstLensBtn = page.locator(".btn-msg-lens").first();
        firstLensBtn.click();

        page.waitForSelector("#modal-document-workspace:not(.hidden)");
        Locator modalWorkspace = page.locator("#modal-document-workspace");
        assertThat(modalWorkspace.isVisible()).isTrue();
        assertThat(page.locator("#workspace-viewer-pane").innerText()).contains("ORD-9942");
        assertThat(page.locator("#workspace-viewer-pane").innerText()).contains("legacyXmlInvoice");

        // Close Document Workspace
        page.locator("#btn-close-workspace").click();
        page.waitForSelector(
                "#modal-document-workspace", new Page.WaitForSelectorOptions().setState(WaitForSelectorState.HIDDEN));
        assertThat(modalWorkspace.isVisible()).isFalse();
    }

    @Test
    @DisplayName("Should expand message drawer and purge destination via #modal-confirm")
    void testMessageDrawerAndPurgeModal() {
        page.navigate(wireMockServer.baseUrl() + "/__admin/ui/?demo=true#queues");
        page.waitForSelector("#tab-queues.active");

        page.locator(".queues-dest-item:has-text('orders.events')").click();
        page.waitForSelector(".queues-message-row");

        // Expand first row to show inline drawer
        page.locator(".queues-message-row").first().click();
        page.waitForSelector(".queues-drawer-row");
        Locator drawer = page.locator(".queues-drawer-row");
        assertThat(drawer.isVisible()).isTrue();
        assertThat(drawer.textContent()).contains("Message Details:");
        assertThat(drawer.textContent()).contains("Payload Preview");
        assertThat(drawer.textContent()).contains("ORD-9942");

        // Click Purge All button -> opens #modal-confirm
        page.locator("#btn-queues-purge").click();
        page.waitForSelector("#modal-confirm:not(.hidden)");
        assertThat(page.locator("#confirm-modal-message").textContent()).contains("orders.events");

        // Confirm Purge via #btn-confirm-accept
        acceptConfirmModal();

        // Destination should now have 0 messages
        assertThat(page.locator(".queues-message-row").count()).isEqualTo(0);
        assertThat(page.locator("#queues-messages-tbody").textContent()).contains("No messages found");
    }

    @Test
    @DisplayName("Should inspect and update live destination via WireMock Async Admin API")
    void testLiveWireMockAsyncDestinationIntegration() {
        // Publish a message to in-memory broker
        AsyncEngine engine = asyncEngine;
        AsyncMessage liveMsg = AsyncMessage.builder()
                .id("msg-live-1")
                .protocol("in-memory")
                .destination("test.live.queue")
                .key("KEY-1")
                .correlationId("corr-1")
                .timestamp(java.time.Instant.now())
                .headers(Map.of("x-env", "test"))
                .payload("{\"alert\":\"system-healthy\",\"code\":200}")
                .build();
        engine.publishMessage("in-memory", "test.live.queue", liveMsg);

        page.navigate(wireMockServer.baseUrl() + "/__admin/ui/#queues");
        page.waitForSelector("#tab-queues.active");

        // Wait for live destination list to render
        page.locator("#btn-queues-refresh").click();
        page.waitForSelector(".queues-dest-item:has-text('test.live.queue')");

        Locator destItem = page.locator(".queues-dest-item:has-text('test.live.queue')");
        assertThat(destItem.isVisible()).isTrue();

        destItem.click();
        page.waitForSelector(".queues-message-row");

        Locator rows = page.locator(".queues-message-row");
        assertThat(rows.count()).isGreaterThanOrEqualTo(1);
        assertThat(page.locator("#queues-messages-tbody").textContent()).contains("system-healthy");
    }

    @Test
    @DisplayName("Should inspect broker connections, load protocol template, register via JSON, and export")
    void testBrokerConnectionsModal() {
        page.navigate(wireMockServer.baseUrl() + "/__admin/ui/?demo=true#queues");
        page.waitForSelector("#tab-queues.active");

        // Open Broker Connections modal
        page.locator("#btn-queues-manage-brokers").click();
        page.waitForSelector("#modal-queues-brokers:not(.hidden)");

        Locator modal = page.locator("#modal-queues-brokers");
        assertThat(modal.isVisible()).isTrue();
        assertThat(page.locator("#queues-brokers-tbody").textContent()).contains("embedded-artemis");
        assertThat(page.locator("#queues-brokers-tbody").textContent()).contains("ActiveMQ Artemis (Embedded)");
        assertThat(page.locator("#queues-brokers-export-json").textContent()).contains("embedded-artemis");

        Locator jsonEditor = page.locator("#queues-broker-json-editor");

        // Load IBM MQ Protocol Template too
        page.selectOption("#queues-broker-template-select", "ibmmq-k8s");
        assertThat(jsonEditor.inputValue()).contains("corp-ibmmq-k8s");
        assertThat(jsonEditor.inputValue()).contains("${IBM_MQ_PASSWORD}");

        // Load AMQP Protocol Template
        page.selectOption("#queues-broker-template-select", "amqp-k8s");
        assertThat(jsonEditor.inputValue()).contains("artemis-k8s");
        assertThat(jsonEditor.inputValue()).contains("${AMQP_PASSWORD}");

        // Prettify
        page.locator("#btn-queues-prettify-json").click();
        assertThat(page.locator("#queues-broker-feedback").textContent()).contains("valid and formatted");

        // Submit & Register
        page.locator("#btn-queues-submit-json").click();
        page.waitForSelector("#queues-brokers-tbody:has-text('artemis-k8s')");
        assertThat(page.locator("#queues-brokers-tbody").textContent()).contains("artemis-k8s");
        assertThat(page.locator("#queues-broker-feedback").textContent()).contains("successfully registered");

        // Close modal
        page.locator("#btn-close-queues-brokers").click();
        page.waitForSelector(
                "#modal-queues-brokers", new Page.WaitForSelectorOptions().setState(WaitForSelectorState.HIDDEN));
        assertThat(modal.isVisible()).isFalse();
    }
}
