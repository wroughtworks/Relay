package dev.relay.protocol.packet;

import dev.relay.net.SessionHandler;
import dev.relay.protocol.Packet;
import dev.relay.protocol.PacketDirection;
import dev.relay.protocol.ProtocolUtils;
import dev.relay.protocol.ProtocolVersion;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/**
 * A custom-payload message. Registered in both configuration and play state because
 * Relay needs it for the client brand and for BungeeCord-style plugin channels.
 */
public final class PluginMessagePacket implements Packet {

    public static final String BRAND_CHANNEL = "minecraft:brand";

    private String channel = "";
    private byte[] data = new byte[0];

    public PluginMessagePacket() {
    }

    public PluginMessagePacket(String channel, byte[] data) {
        this.channel = channel;
        this.data = data;
    }

    /** Builds a {@code minecraft:brand} message carrying {@code brand}. */
    public static PluginMessagePacket brand(String brand) {
        ByteBuf buf = Unpooled.buffer();
        try {
            ProtocolUtils.writeString(buf, brand);
            byte[] payload = new byte[buf.readableBytes()];
            buf.readBytes(payload);
            return new PluginMessagePacket(BRAND_CHANNEL, payload);
        } finally {
            buf.release();
        }
    }

    public String channel() {
        return channel;
    }

    public byte[] data() {
        return data;
    }

    @Override
    public void decode(ByteBuf buf, PacketDirection direction, ProtocolVersion version) {
        this.channel = ProtocolUtils.readString(buf, 256);
        this.data = ProtocolUtils.readRemaining(buf);
    }

    @Override
    public void encode(ByteBuf buf, PacketDirection direction, ProtocolVersion version) {
        ProtocolUtils.writeString(buf, channel);
        buf.writeBytes(data);
    }

    @Override
    public boolean handle(SessionHandler handler) {
        return handler.handle(this);
    }
}
