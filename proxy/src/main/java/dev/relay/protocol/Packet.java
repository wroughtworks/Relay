package dev.relay.protocol;

import dev.relay.net.SessionHandler;
import io.netty.buffer.ByteBuf;

/**
 * A packet Relay understands well enough to inspect or rewrite.
 *
 * <p>Deliberately, this is a small set. Anything not registered in {@link StateRegistry}
 * is forwarded as an opaque frame without ever being parsed &mdash; the proxy only pays
 * decode cost for traffic it actually acts on, and adding a new Minecraft version means
 * checking a handful of ids rather than hundreds.
 */
public interface Packet {

    void decode(ByteBuf buf, PacketDirection direction, ProtocolVersion version);

    void encode(ByteBuf buf, PacketDirection direction, ProtocolVersion version);

    /**
     * Dispatches to the connection's current handler.
     *
     * @return {@code true} if the handler consumed the packet, {@code false} to let the
     *         connection forward it to the peer unchanged
     */
    boolean handle(SessionHandler handler);

    /** Thrown by {@link #decode} on packets Relay only ever writes. */
    default ProtocolException notDecodable() {
        return new ProtocolException(getClass().getSimpleName() + " is write-only");
    }
}
