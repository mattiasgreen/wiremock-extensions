package se.laz.casual.network.protocol.decoding;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import java.util.List;
import se.laz.casual.api.network.protocol.messages.CasualNWMessage;
import se.laz.casual.network.ProtocolVersion;
import se.laz.casual.network.protocol.messages.CasualNWMessageHeader;
import se.laz.casual.network.protocol.messages.parseinfo.MessageHeaderSizes;

/**
 * Netty frame decoder for Casual network protocol messages.
 * Decodes 32-byte header followed by variable-length payload.
 * <p>
 * Intended for upstream contribution to {@code se.laz.casual:casual-network-protocol}.
 */
public class CasualFrameDecoder extends ByteToMessageDecoder {

    private static final int HEADER_SIZE = MessageHeaderSizes.getHeaderNetworkSize();

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (in.readableBytes() < HEADER_SIZE) {
            return;
        }

        in.markReaderIndex();
        byte[] headerBytes = new byte[HEADER_SIZE];
        in.readBytes(headerBytes);

        CasualNWMessageHeader header = CasualMessageDecoder.networkHeaderToCasualHeader(headerBytes);
        long payloadSize = header.getPayloadSize();

        if (in.readableBytes() < payloadSize) {
            in.resetReaderIndex();
            return;
        }

        byte[] payloadBytes = new byte[(int) payloadSize];
        in.readBytes(payloadBytes);

        CasualNWMessage<?> message = CasualMessageDecoder.read(payloadBytes, header, () -> ProtocolVersion.VERSION_1_0);
        out.add(message);
    }
}
