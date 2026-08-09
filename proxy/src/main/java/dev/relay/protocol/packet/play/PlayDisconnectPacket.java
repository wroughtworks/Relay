package dev.relay.protocol.packet.play;

import dev.relay.net.SessionHandler;
import dev.relay.protocol.ComponentCodec;
import dev.relay.protocol.Packet;
import dev.relay.protocol.PacketDirection;
import dev.relay.protocol.ProtocolVersion;
import io.netty.buffer.ByteBuf;
import net.kyori.adventure.text.Component;

/** A kick during play state. Write-only: Relay forwards backend kicks verbatim. */
public final class PlayDisconnectPacket implements Packet {

    private Component reason = Component.empty();

    public PlayDisconnectPacket() {
    }

    public static PlayDisconnectPacket of(Component reason) {
        PlayDisconnectPacket packet = new PlayDisconnectPacket();
        packet.reason = reason;
        return packet;
    }

    public Component reason() {
        return reason;
    }

    @Override
    public void decode(ByteBuf buf, PacketDirection direction, ProtocolVersion version) {
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
