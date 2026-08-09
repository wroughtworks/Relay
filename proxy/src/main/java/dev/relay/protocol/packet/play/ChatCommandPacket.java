package dev.relay.protocol.packet.play;

import dev.relay.net.SessionHandler;
import dev.relay.protocol.Packet;
import dev.relay.protocol.PacketDirection;
import dev.relay.protocol.ProtocolUtils;
import dev.relay.protocol.ProtocolVersion;
import io.netty.buffer.ByteBuf;

/**
 * An unsigned command the player typed, minus the leading slash.
 *
 * <p>Only the unsigned form is registered. Signed commands (those carrying argument
 * signatures for chat reporting) are a separate packet id and are always forwarded
 * untouched &mdash; proxy commands like {@code /server} are never signed, so Relay has
 * no reason to open them, and rewriting a signed packet would invalidate its signature.
 */
public final class ChatCommandPacket implements Packet {

    public static final int MAX_COMMAND_LENGTH = 256;

    private String command = "";
    private byte[] tail = new byte[0];

    public String command() {
        return command;
    }

    @Override
    public void decode(ByteBuf buf, PacketDirection direction, ProtocolVersion version) {
        this.command = ProtocolUtils.readString(buf, MAX_COMMAND_LENGTH);
        // Everything after the command string — timestamp, salt, the acknowledged-message
        // bitset, and on 1.20.2/1.20.3 the argument signatures — is kept as opaque bytes.
        // Relay has no use for those fields, but a command it does not claim has to be
        // forwarded byte-for-byte: re-encoding from parsed fields would risk invalidating
        // a signature the backend is about to check.
        this.tail = ProtocolUtils.readRemaining(buf);
    }

    @Override
    public void encode(ByteBuf buf, PacketDirection direction, ProtocolVersion version) {
        ProtocolUtils.writeString(buf, command);
        buf.writeBytes(tail);
    }

    @Override
    public boolean handle(SessionHandler handler) {
        return handler.handle(this);
    }
}
