package se.laz.casual.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.channel.embedded.EmbeddedChannel;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.laz.casual.api.network.protocol.messages.CasualNWMessage;
import se.laz.casual.api.network.protocol.messages.CasualNWMessageType;
import se.laz.casual.api.network.protocol.messages.CasualNetworkTransmittable;

class CasualServerFailFastTest {

    @Test
    @DisplayName("Should immediately close channel when receiving unsupported or unhandled message type")
    void shouldCloseChannelImmediatelyOnUnsupportedMessageType() {
        CasualServer server = CasualServer.builder().serviceHandler(req -> null).build();

        EmbeddedChannel channel = new EmbeddedChannel(server.createInboundHandler());
        assertThat(channel.isOpen()).isTrue();

        // Send an unhandled frame type (e.g. ENQUEUE_REQUEST)
        CasualNWMessage<CasualNetworkTransmittable> unhandledMsg = new CasualNWMessage<>() {
            @Override
            public CasualNWMessageType getType() {
                return CasualNWMessageType.ENQUEUE_REQUEST;
            }

            @Override
            public UUID getCorrelationId() {
                return UUID.randomUUID();
            }

            @Override
            public CasualNetworkTransmittable getMessage() {
                return null;
            }

            @Override
            public List<byte[]> toNetworkBytes() {
                return List.of();
            }
        };

        channel.writeInbound(unhandledMsg);

        // Fail-fast guarantee: socket must be closed immediately to prevent client hanging
        assertThat(channel.isOpen()).isFalse();
    }

    @Test
    @DisplayName("Should close channel on uncaught channel exception")
    void shouldCloseChannelOnUncaughtException() {
        CasualServer server = CasualServer.builder()
                .serviceHandler(req -> {
                    throw new RuntimeException("Unexpected internal failure");
                })
                .build();

        EmbeddedChannel channel = new EmbeddedChannel(server.createInboundHandler());
        assertThat(channel.isOpen()).isTrue();

        channel.pipeline().fireExceptionCaught(new RuntimeException("Simulated socket error"));

        assertThat(channel.isOpen()).isFalse();
    }
}
