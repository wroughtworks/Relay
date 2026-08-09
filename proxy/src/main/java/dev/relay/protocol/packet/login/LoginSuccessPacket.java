package dev.relay.protocol.packet.login;

import dev.relay.net.SessionHandler;
import dev.relay.protocol.Packet;
import dev.relay.protocol.PacketDirection;
import dev.relay.protocol.ProtocolUtils;
import dev.relay.protocol.ProtocolUtils.GameProfileProperty;
import dev.relay.protocol.ProtocolVersion;
import io.netty.buffer.ByteBuf;

import java.util.List;
import java.util.UUID;

/**
 * The authenticated profile.
 *
 * <p>Watch the trailing boolean: {@code strictErrorHandling} exists on 1.20.5 and 1.21
 * only. It was added in 1.20.5 and removed again in 1.21.2, so it is bracketed rather
 * than gated on a lower bound alone.
 */
public final class LoginSuccessPacket implements Packet {

    private UUID uuid;
    private String username = "";
    private List<GameProfileProperty> properties = List.of();
    private boolean strictErrorHandling = true;

    public LoginSuccessPacket() {
    }

    public LoginSuccessPacket(UUID uuid, String username, List<GameProfileProperty> properties) {
        this.uuid = uuid;
        this.username = username;
        this.properties = properties;
    }

    public UUID uuid() {
        return uuid;
    }

    public String username() {
        return username;
    }

    public List<GameProfileProperty> properties() {
        return properties;
    }

    private static boolean hasStrictErrorHandling(ProtocolVersion version) {
        return version.atLeast(ProtocolVersion.MINECRAFT_1_20_5) && version.atMost(ProtocolVersion.MINECRAFT_1_21);
    }

    @Override
    public void decode(ByteBuf buf, PacketDirection direction, ProtocolVersion version) {
        this.uuid = ProtocolUtils.readUuid(buf);
        this.username = ProtocolUtils.readString(buf, LoginStartPacket.MAX_USERNAME_LENGTH);
        this.properties = ProtocolUtils.readProperties(buf);
        if (hasStrictErrorHandling(version)) {
            this.strictErrorHandling = buf.readBoolean();
        }
    }

    @Override
    public void encode(ByteBuf buf, PacketDirection direction, ProtocolVersion version) {
        ProtocolUtils.writeUuid(buf, uuid);
        ProtocolUtils.writeString(buf, username);
        ProtocolUtils.writeProperties(buf, properties);
        if (hasStrictErrorHandling(version)) {
            buf.writeBoolean(strictErrorHandling);
        }
    }

    @Override
    public boolean handle(SessionHandler handler) {
        return handler.handle(this);
    }
}
