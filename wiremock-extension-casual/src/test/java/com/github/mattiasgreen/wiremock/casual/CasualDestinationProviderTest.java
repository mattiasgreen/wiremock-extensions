package com.github.mattiasgreen.wiremock.casual;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.mattiasgreen.wiremock.async.connection.AsyncBrokerRegistry;
import com.github.mattiasgreen.wiremock.async.destination.AsyncDestination;
import com.github.mattiasgreen.wiremock.async.model.AsyncMessage;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CasualDestinationProviderTest {

    @Test
    void shouldListPublishPeekDeleteAndPurgeLocalCasualQueuesDirectlyWithoutReflection() {
        CasualQueueStore store = new CasualQueueStore();
        AsyncBrokerRegistry registry = AsyncBrokerRegistry.createForTest();
        CasualDestinationProvider provider = new CasualDestinationProvider(() -> Optional.of(store), registry);

        assertThat(provider.getProtocol()).isEqualTo("casual");
        assertThat(provider.listDestinations()).isEmpty();

        AsyncMessage msg = AsyncMessage.builder()
                .id("msg-1")
                .protocol("casual")
                .destination("billing.q")
                .correlationId("corr-casual-10")
                .replyTo("billing.reply")
                .timestamp(Instant.now())
                .headers(Map.of("X-Casual-Buffer-Type", "JSON"))
                .payload("{\"invoiceId\":\"INV-10\"}")
                .build();

        assertThat(provider.publishMessage("billing.q", msg)).isTrue();

        List<AsyncDestination> destinations = provider.listDestinations();
        assertThat(destinations)
                .extracting(AsyncDestination::name)
                .containsExactlyInAnyOrder("billing.q", "billing.q.error");
        AsyncDestination primary = destinations.stream()
                .filter(d -> "billing.q".equals(d.name()))
                .findFirst()
                .orElseThrow();
        assertThat(primary.messageCount()).isEqualTo(1);

        List<AsyncMessage> peeked = provider.peekMessages("billing.q", 10);
        assertThat(peeked).hasSize(1);
        assertThat(peeked.get(0).correlationId()).isEqualTo("corr-casual-10");
        assertThat(peeked.get(0).replyTo()).isEqualTo("billing.reply");
        assertThat(peeked.get(0).payload()).contains("INV-10");

        String msgId = peeked.get(0).id();
        assertThat(provider.deleteMessage("billing.q", msgId)).isTrue();
        assertThat(provider.peekMessages("billing.q", 10)).isEmpty();

        provider.publishMessage("billing.q", msg);
        provider.purgeDestination("billing.q");
        assertThat(provider.peekMessages("billing.q", 10)).isEmpty();
    }
}
