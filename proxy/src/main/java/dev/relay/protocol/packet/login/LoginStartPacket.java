package dev.relay.protocol.packet.login;

import dev.relay.net.SessionHandler;
import dev.relay.protocol.Packet;
import dev.relay.protocol.PacketDirection;
import dev.relay.protocol.ProtocolUtils;
import dev.relay.protocol.ProtocolVersion;
import io.netty.buffer.ByteBuf;

import java.util.UUID;

/**
 * The player's opening bid: a username and the UUID the client believes it has.
 *
 * <p>Neither field is trusted. In online mode the authoritative profile comes from the
 * session server after encryption; in offline mode Relay derives the UUID itself.
 */
public final class LoginStartPacket implements Packet {

    public static final int MAX_USERNAME_LENGTH = 16;

    private String username = "";
    private UUID uuid;

    public LoginStartPacket() {
    }

    public LoginStartPacket(String username, UUID uuid) {
        this.username = username;
        this.uuid = uuid;
    }

    public String username() {
        return username;
    }

    public UUID uuid() {
        return uuid;
    }

    @Override
    public void decode(ByteBuf buf, PacketDirection direction, ProtocolVersion version) {
        this.username = ProtocolUtils.readString(buf, MAX_USERNAME_LENGTH);
        // Unconditional from 1.20.2 on, which is Relay's floor.
        this.uuid = ProtocolUtils.readUuid(buf);
    }

    @Override
    public void encode(ByteBuf buf, PacketDirection direction, ProtocolVersion version) {
        ProtocolUtils.writeString(buf, username);
        ProtocolUtils.writeUuid(buf, uuid);
    }

    @Override
    public boolean handle(SessionHandler handler) {
        return handler.handle(this);
    }
}
