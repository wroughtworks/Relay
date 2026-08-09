package dev.relay.protocol.packet.login;

import dev.relay.net.SessionHandler;
import dev.relay.protocol.Packet;
import dev.relay.protocol.PacketDirection;
import dev.relay.protocol.ProtocolUtils;
import dev.relay.protocol.ProtocolVersion;
import io.netty.buffer.ByteBuf;

/** The client's RSA-wrapped AES key plus the echoed nonce. */
public final class EncryptionResponsePacket implements Packet {

    private byte[] sharedSecret = new byte[0];
    private byte[] verifyToken = new byte[0];

    public byte[] sharedSecret() {
        return sharedSecret;
    }

    public byte[] verifyToken() {
        return verifyToken;
    }

    @Override
    public void decode(ByteBuf buf, PacketDirection direction, ProtocolVersion version) {
        this.sharedSecret = ProtocolUtils.readByteArray(buf, 256);
        this.verifyToken = ProtocolUtils.readByteArray(buf, 256);
    }

    @Override
    public void encode(ByteBuf buf, PacketDirection direction, ProtocolVersion version) {
        ProtocolUtils.writeByteArray(buf, sharedSecret);
        ProtocolUtils.writeByteArray(buf, verifyToken);
    }

    @Override
    public boolean handle(SessionHandler handler) {
        return handler.handle(this);
    }
}
