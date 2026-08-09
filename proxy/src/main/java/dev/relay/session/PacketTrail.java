package dev.relay.session;

import dev.relay.protocol.Packet;
import dev.relay.protocol.ProtocolUtils;
import io.netty.buffer.ByteBuf;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.StringJoiner;

/**
 * A short record of the packets most recently relayed on a connection.
 *
 * <p>Exists for one situation: a peer that closes the connection without saying why. The
 * only evidence left is what it was sent immediately beforehand, and by the time the
 * close is noticed those buffers are long gone. Recording just the id and size costs
 * nothing per packet and turns "the backend hung up" into a specific packet to look at.
 */
final class PacketTrail {

    private static final int CAPACITY = 8;

    private final Deque<String> recent = new ArrayDeque<>(CAPACITY);
    private long total;

    /** Records a relayed message. Peeks at the id without disturbing the read position. */
    void record(Object msg) {
        total++;
        if (recent.size() == CAPACITY) {
            recent.removeFirst();
        }
        recent.addLast(describe(msg));
    }

    private static String describe(Object msg) {
        if (msg instanceof ByteBuf frame) {
            try {
                ByteBuf peek = frame.duplicate();
                int id = ProtocolUtils.readVarInt(peek);
                return "0x" + Integer.toHexString(id) + "(" + frame.readableBytes() + "B)";
            } catch (RuntimeException e) {
                return "unreadable(" + frame.readableBytes() + "B)";
            }
        }
        if (msg instanceof Packet packet) {
            return packet.getClass().getSimpleName();
        }
        return String.valueOf(msg);
    }

    long total() {
        return total;
    }

    /** Oldest first, e.g. {@code "0x9(14B), 0x0(2B), 0x7(5B)"}. */
    String recent() {
        if (recent.isEmpty()) {
            return "nothing";
        }
        StringJoiner joiner = new StringJoiner(", ");
        recent.forEach(joiner::add);
        return joiner.toString();
    }
}
