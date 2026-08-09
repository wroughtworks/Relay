package dev.relay.protocol.packet.config;

import dev.relay.net.SessionHandler;
import dev.relay.protocol.ComponentCodec;
import dev.relay.protocol.Packet;
import dev.relay.protocol.PacketDirection;
import dev.relay.protocol.ProtocolVersion;
import io.netty.buffer.ByteBuf;
import net.kyori.adventure.text.Component;

/**
 * A kick during configuration state. Unlike the login-state disconnect this follows the
 * version-dependent component encoding &mdash; JSON on 1.20.2, network NBT after.
 */
public final class ConfigDisconnectPacket implements Packet {

    private Component reason = Component.empty();

    public ConfigDisconnectPacket() {
    }

    public static ConfigDisconnectPacket of(Component reason) {
        ConfigDisconnectPacket packet = new ConfigDisconnectPacket();
        packet.reason = reason;
        return packet;
    }

    public Component reason() {
        return reason;
    }

    @Override
    public void decode(ByteBuf buf, PacketDirection direction, ProtocolVersion version) {
        // Relay forwards backend kicks as opaque frames rather than re-encoding them,
        // so this only ever needs to be written.
        throw notDecodable();
    }

    @Override
    public void encode(ByteBuf buf, PacketDirection direction, ProtocolVersion version) {
        ComponentCodec.write(buf, reason, version);
    }

    @Override
    public boolean handle(SessionHandler handler) {
        return handler.handle(this);
    }
}
