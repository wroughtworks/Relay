package dev.relay.protocol.packet;

import dev.relay.net.SessionHandler;
import dev.relay.protocol.Packet;
import dev.relay.protocol.PacketDirection;
import dev.relay.protocol.ProtocolUtils;
import dev.relay.protocol.ProtocolVersion;
import io.netty.buffer.ByteBuf;

/** The first packet on every connection: which version, where it thinks it is going, and why. */
public final class HandshakePacket implements Packet {

    /** Generous, but bounded: forwarding schemes append data to the host field. */
    private static final int MAX_HOSTNAME_LENGTH = 255;

    private int protocolVersion;
    private String serverAddress = "";
    private int port;
    private int nextState;

    public HandshakePacket() {
    }

    public HandshakePacket(int protocolVersion, String serverAddress, int port, int nextState) {
        this.protocolVersion = protocolVersion;
        this.serverAddress = serverAddress;
        this.port = port;
        this.nextState = nextState;
    }

    public int protocolVersion() {
        return protocolVersion;
    }

    /**
     * The hostname the client connected to, before forwarding data is appended.
     * Everything from the first NUL onward belongs to a forwarding scheme, not the host.
     */
    public String cleanServerAddress() {
        int nul = serverAddress.indexOf('\0');
        return nul == -1 ? serverAddress : serverAddress.substring(0, nul);
    }

    public String serverAddress() {
        return serverAddress;
    }

    public void setServerAddress(String serverAddress) {
        this.serverAddress = serverAddress;
    }

    public int port() {
        return port;
    }

    public int nextState() {
        return nextState;
    }

    @Override
    public void decode(ByteBuf buf, PacketDirection direction, ProtocolVersion version) {
        this.protocolVersion = ProtocolUtils.readVarInt(buf);
        this.serverAddress = ProtocolUtils.readString(buf, MAX_HOSTNAME_LENGTH);
        this.port = buf.readUnsignedShort();
        this.nextState = ProtocolUtils.readVarInt(buf);
    }

    @Override
    public void encode(ByteBuf buf, PacketDirection direction, ProtocolVersion version) {
        ProtocolUtils.writeVarInt(buf, protocolVersion);
        ProtocolUtils.writeString(buf, serverAddress);
        buf.writeShort(port);
        ProtocolUtils.writeVarInt(buf, nextState);
    }

    @Override
    public boolean handle(SessionHandler handler) {
        return handler.handle(this);
    }
}
