package dev.relay.protocol.packet.play;

import dev.relay.net.SessionHandler;
import dev.relay.protocol.ComponentCodec;
import dev.relay.protocol.Packet;
import dev.relay.protocol.PacketDirection;
import dev.relay.protocol.ProtocolVersion;
import io.netty.buffer.ByteBuf;
import net.kyori.adventure.text.Component;

/**
 * An unsigned message from the server, used for all proxy-generated output &mdash;
 * command results, switch notices, errors.
 */
public final class SystemChatPacket implements Packet {

    private Component message = Component.empty();
    private boolean actionBar;

    public SystemChatPacket() {
    }

    public static SystemChatPacket chat(Component message) {
        SystemChatPacket packet = new SystemChatPacket();
        packet.message = message;
        return packet;
    }

    public static SystemChatPacket actionBar(Component message) {
        SystemChatPacket packet = new SystemChatPacket();
        packet.message = message;
        packet.actionBar = true;
        return packet;
    }

    @Override
    public void decode(ByteBuf buf, PacketDirection direction, ProtocolVersion version) {
        throw notDecodable();
    }

    @Override
    public void encode(ByteBuf buf, PacketDirection direction, ProtocolVersion version) {
        ComponentCodec.write(buf, message, version);
        buf.writeBoolean(actionBar);
    }

    @Override
    public boolean handle(SessionHandler handler) {
        return handler.handle(this);
    }
}
