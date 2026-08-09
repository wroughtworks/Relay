package dev.relay.protocol.packet.status;

import dev.relay.net.SessionHandler;
import dev.relay.protocol.Packet;
import dev.relay.protocol.PacketDirection;
import dev.relay.protocol.ProtocolVersion;
import io.netty.buffer.ByteBuf;

/**
 * The latency probe. The same packet id and payload travel in both directions &mdash;
 * the client sends a token and the server echoes it back verbatim.
 */
public final class StatusPingPacket implements Packet {

    private long token;

    public StatusPingPacket() {
    }

    public StatusPingPacket(long token) {
        this.token = token;
    }

    public long token() {
        return token;
    }

    @Override
    public void decode(ByteBuf buf, PacketDirection direction, ProtocolVersion version) {
        this.token = buf.readLong();
    }

    @Override
    public void encode(ByteBuf buf, PacketDirection direction, ProtocolVersion version) {
        buf.writeLong(token);
    }

    @Override
    public boolean handle(SessionHandler handler) {
        return handler.handle(this);
    }
}
