package dev.relay.protocol.packet.status;

import dev.relay.net.SessionHandler;
import dev.relay.protocol.Packet;
import dev.relay.protocol.PacketDirection;
import dev.relay.protocol.ProtocolUtils;
import dev.relay.protocol.ProtocolVersion;
import io.netty.buffer.ByteBuf;

/** The MOTD, as a JSON document. */
public final class StatusResponsePacket implements Packet {

    private String json = "";

    public StatusResponsePacket() {
    }

    public StatusResponsePacket(String json) {
        this.json = json;
    }

    public String json() {
        return json;
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
