package dev.relay.protocol.packet.login;

import dev.relay.net.SessionHandler;
import dev.relay.protocol.ComponentCodec;
import dev.relay.protocol.Packet;
import dev.relay.protocol.PacketDirection;
import dev.relay.protocol.ProtocolUtils;
import dev.relay.protocol.ProtocolVersion;
import io.netty.buffer.ByteBuf;
import net.kyori.adventure.text.Component;

/**
 * Refuses a login. Always JSON-encoded, at every version &mdash; this packet precedes
 * registry sync, so the client has no NBT component codec available yet.
 */
public final class LoginDisconnectPacket implements Packet {

    private String json = "";

    public LoginDisconnectPacket() {
    }

    public static LoginDisconnectPacket of(Component reason) {
        LoginDisconnectPacket packet = new LoginDisconnectPacket();
        packet.json = ComponentCodec.toJson(reason);
        return packet;
    }

    public Component reason() {
        return ComponentCodec.fromJson(json);
    }

    @Override
    public void decode(ByteBuf buf, PacketDirection direction, ProtocolVersion version) {
        this.json = ProtocolUtils.readString(buf, 262144);
    }

    @Override
    public void encode(ByteBuf buf, PacketDirection direction, ProtocolVersion version) {
        ProtocolUtils.writeString(buf, json);
    }

    @Override
    public boolean handle(SessionHandler handler) {
        return handler.handle(this);
    }
}
