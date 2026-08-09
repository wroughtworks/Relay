package dev.relay.protocol.packet.login;

import dev.relay.net.SessionHandler;
import dev.relay.protocol.Packet;
import dev.relay.protocol.PacketDirection;
import dev.relay.protocol.ProtocolUtils;
import dev.relay.protocol.ProtocolVersion;
import io.netty.buffer.ByteBuf;

/** Relay's answer to a {@link LoginPluginRequestPacket}. */
public final class LoginPluginResponsePacket implements Packet {

    private int messageId;
    private boolean success;
    private byte[] data = new byte[0];

    public LoginPluginResponsePacket() {
    }

    public LoginPluginResponsePacket(int messageId, boolean success, byte[] data) {
        this.messageId = messageId;
        this.success = success;
        this.data = data;
    }

    public int messageId() {
        return messageId;
    }

    public boolean success() {
        return success;
    }

    public byte[] data() {
        return data;
    }

    @Override
    public void decode(ByteBuf buf, PacketDirection direction, ProtocolVersion version) {
        this.messageId = ProtocolUtils.readVarInt(buf);
        this.success = buf.readBoolean();
        this.data = ProtocolUtils.readRemaining(buf);
    }

    @Override
    public void encode(ByteBuf buf, PacketDirection direction, ProtocolVersion version) {
        ProtocolUtils.writeVarInt(buf, messageId);
        buf.writeBoolean(success);
        buf.writeBytes(data);
    }

    @Override
    public boolean handle(SessionHandler handler) {
        return handler.handle(this);
    }
}
