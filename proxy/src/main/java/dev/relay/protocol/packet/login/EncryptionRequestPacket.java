package dev.relay.protocol.packet.login;

import dev.relay.net.SessionHandler;
import dev.relay.protocol.Packet;
import dev.relay.protocol.PacketDirection;
import dev.relay.protocol.ProtocolUtils;
import dev.relay.protocol.ProtocolVersion;
import io.netty.buffer.ByteBuf;

/** Relay's public key and a nonce, sent to start online-mode authentication. */
public final class EncryptionRequestPacket implements Packet {

    private String serverId = "";
    private byte[] publicKey = new byte[0];
    private byte[] verifyToken = new byte[0];
    /** 1.20.5+ only: tells the client whether to contact the session server at all. */
    private boolean shouldAuthenticate = true;

    public EncryptionRequestPacket() {
    }

    public EncryptionRequestPacket(String serverId, byte[] publicKey, byte[] verifyToken, boolean shouldAuthenticate) {
        this.serverId = serverId;
        this.publicKey = publicKey;
        this.verifyToken = verifyToken;
        this.shouldAuthenticate = shouldAuthenticate;
    }

    public String serverId() {
        return serverId;
    }

    public byte[] publicKey() {
        return publicKey;
    }

    public byte[] verifyToken() {
        return verifyToken;
    }

    @Override
    public void decode(ByteBuf buf, PacketDirection direction, ProtocolVersion version) {
        this.serverId = ProtocolUtils.readString(buf, 20);
        this.publicKey = ProtocolUtils.readByteArray(buf, 512);
        this.verifyToken = ProtocolUtils.readByteArray(buf, 64);
        if (version.atLeast(ProtocolVersion.MINECRAFT_1_20_5)) {
            this.shouldAuthenticate = buf.readBoolean();
        }
    }

    @Override
    public void encode(ByteBuf buf, PacketDirection direction, ProtocolVersion version) {
        ProtocolUtils.writeString(buf, serverId);
        ProtocolUtils.writeByteArray(buf, publicKey);
        ProtocolUtils.writeByteArray(buf, verifyToken);
        if (version.atLeast(ProtocolVersion.MINECRAFT_1_20_5)) {
            buf.writeBoolean(shouldAuthenticate);
        }
    }

    @Override
    public boolean handle(SessionHandler handler) {
        return handler.handle(this);
    }
}
