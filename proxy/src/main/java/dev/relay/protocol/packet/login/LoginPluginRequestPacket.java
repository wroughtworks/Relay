package dev.relay.protocol.packet.login;

import dev.relay.net.SessionHandler;
import dev.relay.protocol.Packet;
import dev.relay.protocol.PacketDirection;
import dev.relay.protocol.ProtocolUtils;
import dev.relay.protocol.ProtocolVersion;
import io.netty.buffer.ByteBuf;

/**
 * A login-time query from the backend. Modern forwarding rides on this channel: the
 * backend asks {@code velocity:player_info} and Relay answers with the signed profile.
 */
public final class LoginPluginRequestPacket implements Packet {

    private int messageId;
    private String channel = "";
    private byte[] data = new byte[0];

    public int messageId() {
        return messageId;
    }

    public String channel() {
        return channel;
    }

    public byte[] data() {
        return data;
    }

    @Override
    public void decode(ByteBuf buf, PacketDirection direction, ProtocolVersion version) {
        this.messageId = ProtocolUtils.readVarInt(buf);
        this.channel = ProtocolUtils.readString(buf, 256);
        this.data = ProtocolUtils.readRemaining(buf);
    }

    @Override
    public void encode(ByteBuf buf, PacketDirection direction, ProtocolVersion version) {
        ProtocolUtils.writeVarInt(buf, messageId);
        ProtocolUtils.writeString(buf, channel);
        buf.writeBytes(data);
    }

    @Override
    public boolean handle(SessionHandler handler) {
        return handler.handle(this);
    }
}
