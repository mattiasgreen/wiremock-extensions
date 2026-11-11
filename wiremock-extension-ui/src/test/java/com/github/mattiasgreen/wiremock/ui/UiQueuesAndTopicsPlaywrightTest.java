package com.github.mattiasgreen.wiremock.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.mattiasgreen.wiremock.async.destination.AsyncDestinationMessage;
import com.github.mattiasgreen.wiremock.async.destination.AsyncDestinationService;
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
        assertThat(amqpItems.first().textContent()).contains("payments.requests");
        assertThat(amqpItems.first().textContent()).contains("AMQP");

        // Click Casual filter
        page.locator("#btn-filter-proto-casual").click();
        Locator casualItems = page.locator(".queues-dest-item");
        assertThat(casualItems.count()).isEqualTo(1);
        assertThat(casualItems.first().textContent()).contains("billing.forward");
        assertThat(casualItems.first().textContent()).contains("CASUAL");

        // Reset to All
        page.locator("#btn-filter-proto-all").click();
        assertThat(page.locator(".queues-dest-item").count()).isGreaterThanOrEqualTo(4);

        // Search channel by free text
        page.locator("#queues-channel-search").fill("payment");
        assertThat(page.locator(".queues-dest-item").count()).isEqualTo(1);
        assertThat(page.locator(".queues-dest-item").first().textContent()).contains("payments.requests");

        page.locator("#queues-channel-search").fill("");
        assertThat(page.locator(".queues-dest-item").count()).isGreaterThanOrEqualTo(4);
    }

    @Test
    @DisplayName("Should inspect peeked messages, filter with boolean queries, and open Polyglot Lens")
    void testMessageInspectionAndPolyglotLens() {
        page.navigate(wireMockServer.baseUrl() + "/__admin/ui/?demo=true#queues");
        page.waitForSelector("#tab-queues.active");

        // Select Kafka orders destination
        page.locator(".queues-dest-item:has-text('orders.events')").click();
        page.waitForSelector(".queues-message-row");

        Locator rows = page.locator(".queues-message-row");
        assertThat(rows.count()).isEqualTo(3);

        // Test boolean search across payload and keys
        page.locator("#queues-message-search").fill("ORD-8821");
        assertThat(page.locator(".queues-message-row").count()).isEqualTo(1);

        page.locator("#queues-message-search").fill("Acme AND 1250");
        assertThat(page.locator(".queues-message-row").count()).isEqualTo(1);

        page.locator("#queues-message-search").fill("nonexistent_token_999");
        assertThat(page.locator(".queues-message-row").count()).isEqualTo(0);
        assertThat(page.locator("#queues-messages-tbody").textContent()).contains("No messages found");

        page.locator("#queues-message-search").fill("");
        assertThat(page.locator(".queues-message-row").count()).isEqualTo(3);

        // Click Lens on the first row
        Locator firstLensBtn = page.locator(".btn-msg-lens").first();
        firstLensBtn.click();

        page.waitForSelector("#modal-payload-lens:not(.hidden)");
        Locator modalLens = page.locator("#modal-payload-lens");
        assertThat(modalLens.isVisible()).isTrue();

        Locator editor = page.locator("#payload-lens-editor");
        assertThat(editor.inputValue()).contains("ORD-8821");
        assertThat(editor.inputValue()).contains("legacyXmlInvoice");

        // Close Lens Modal
        page.locator("#btn-cancel-payload-lens").click();
        page.waitForSelector(
                "#modal-payload-lens", new Page.WaitForSelectorOptions().setState(WaitForSelectorState.HIDDEN));
        assertThat(modalLens.isVisible()).isFalse();
    }

    @Test
    @DisplayName("Should expand message drawer and purge destination via confirmation modal")
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
        assertThat(drawer.textContent()).contains("Headers");
        assertThat(drawer.textContent()).contains("Payload Preview");

        // Click Purge All button
        page.locator("#btn-queues-purge").click();
        page.waitForSelector("#modal-queues-purge:not(.hidden)");
        Locator modalPurge = page.locator("#modal-queues-purge");
        assertThat(modalPurge.isVisible()).isTrue();
        assertThat(page.locator("#queues-purge-target-name").textContent()).contains("orders.events");

        // Confirm Purge
        page.locator("#btn-confirm-queues-purge").click();
        page.waitForSelector(
                "#modal-queues-purge", new Page.WaitForSelectorOptions().setState(WaitForSelectorState.HIDDEN));

        // Destination should now have 0 messages
        assertThat(page.locator(".queues-message-row").count()).isEqualTo(0);
        assertThat(page.locator("#queues-messages-tbody").textContent()).contains("No messages found");
    }

    @Test
    @DisplayName("Should inspect and update live destination via WireMock Async Admin API")
    void testLiveWireMockAsyncDestinationIntegration() {
        // Publish a message to in-memory broker
        AsyncDestinationService destinationService = AsyncDestinationService.getInstance();
        AsyncDestinationMessage liveMsg = new AsyncDestinationMessage(
                "msg-live-1",
                "in-memory",
                "test.live.queue",
                "KEY-1",
                "corr-1",
                null,
                java.time.Instant.now(),
                0,
                Map.of("x-env", "test"),
                "{\"alert\":\"system-healthy\",\"code\":200}",
                null,
                null);
        destinationService.publishMessage("in-memory", "test.live.queue", liveMsg);

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
    @DisplayName("Should inspect broker connections modal and copy declarative GitOps JSON")
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
        assertThat(page.locator("#queues-brokers-gitops-json").textContent()).contains("embedded-artemis");

        // Close modal
        page.locator("#btn-close-queues-brokers").click();
        page.waitForSelector(
                "#modal-queues-brokers", new Page.WaitForSelectorOptions().setState(WaitForSelectorState.HIDDEN));
        assertThat(modal.isVisible()).isFalse();
    }
}
