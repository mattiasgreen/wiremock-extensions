package com.github.mattiasgreen.wiremock.async.destination;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.mattiasgreen.wiremock.async.bridge.InMemoryMessageBroker;
import com.github.mattiasgreen.wiremock.async.engine.AsyncEngine;
import com.github.mattiasgreen.wiremock.async.model.AsyncMessage;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AsyncDestinationServiceTest {

    private AsyncEngine engine;
    private AsyncDestinationService service;

    @BeforeEach
    void setUp() {
        InMemoryMessageBroker testBroker = new InMemoryMessageBroker("test-broker");
        InMemoryMessageBroker upstreamBroker = new InMemoryMessageBroker("upstream-broker");
        engine = AsyncEngine.create(testBroker, upstreamBroker);
        service = AsyncDestinationService.createForTest(engine);
    }

    @Test
    @DisplayName("Should list in-memory destinations, peek messages, delete single message, and purge")
    void shouldManageInMemoryDestinations() {
        String channel = "orders.inbound";
        AsyncMessage m1 = AsyncMessage.builder()
                .id("msg-1")
                .destination(channel)
                .payload("{\"orderId\": 101}")
                .timestamp(Instant.now())
                .build();
        AsyncMessage m2 = AsyncMessage.builder()
                .id("msg-2")
                .destination(channel)
                .payload("{\"orderId\": 102}")
                .timestamp(Instant.now())
                .build();

        engine.publish(channel, m1);
        engine.publish(channel, m2);

        List<AsyncDestination> destinations = service.listAllDestinations();
        assertThat(destinations).hasSize(1);
        assertThat(destinations.get(0).name()).isEqualTo(channel);
        assertThat(destinations.get(0).messageCount()).isEqualTo(2);

        // Peek
        List<AsyncDestinationMessage> peeked = service.peekMessages("in-memory", channel, 10);
        assertThat(peeked).hasSize(2);
        assertThat(peeked.get(0).id()).isEqualTo("msg-1");
        assertThat(peeked.get(1).id()).isEqualTo("msg-2");

        // Delete single message
        boolean deleted = service.deleteMessage("in-memory", channel, "msg-1");
        assertThat(deleted).isTrue();
        assertThat(service.peekMessages("in-memory", channel, 10)).hasSize(1);

        // Purge
        service.purgeDestination("in-memory", channel);
        assertThat(service.peekMessages("in-memory", channel, 10)).isEmpty();
    }
}
