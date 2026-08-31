package dev.relay.session;

import dev.relay.protocol.Packet;
import io.netty.buffer.ByteBuf;

import java.util.StringJoiner;

/**
 * A short record of the packets most recently relayed on a connection.
 *
 * <p>Exists for one situation: a peer that closes the connection without saying why. The
 * only evidence left is what it was sent immediately beforehand, and by the time the close
 * is noticed those buffers are long gone. Recording the id and size turns "the backend
 * hung up" into a specific packet to look at.
 *
 * <h2>Nothing is allocated here</h2>
 * This runs on every relayed packet, which on a busy network is tens of thousands a second
 * per connection. The first version said it cost nothing and did not: it duplicated the
 * buffer to peek at the id, then built a string, per packet, for a diagnostic almost no
 * connection ever reads. That is garbage created at line rate to describe traffic that is
 * about to be fine.
 *
 * <p>So the ring holds primitives &mdash; the id and size packed into one {@code long},
 * beside the packet's {@link Class} when Relay decoded it &mdash; and the strings are built
 * only if something asks. The id is read straight out of the buffer with absolute indexed
 * gets, which neither allocates nor moves the reader index.
 */
final class PacketTrail {

    private static final int CAPACITY = 8;

    /** Packed {@code id << 32 | size}, valid where {@link #packets} holds null. */
    private final long[] frames = new long[CAPACITY];

    /** The decoded packet's type, or null when the entry is an opaque frame. */
    private final Class<?>[] packets = new Class<?>[CAPACITY];

    /** Where the next entry goes; entries before it, wrapping, are the recent ones. */
    private int next;
    private int size;
    private long total;

    /** An id that could not be read, so a truncated frame still leaves a trace. */
    private static final int UNREADABLE = -1;

    void record(Object msg) {
        total++;
        int slot = next;
        next = (next + 1) % CAPACITY;
        if (size < CAPACITY) {
            size++;
        }
        if (msg instanceof ByteBuf frame) {
            packets[slot] = null;
            frames[slot] = pack(peekId(frame), frame.readableBytes());
        } else {
            // A decoded packet: the type says more than an id would, and holding the
            // Class costs nothing -- it already exists and outlives everything here.
            packets[slot] = msg == null ? null : msg.getClass();
            frames[slot] = 0;
        }
    }

    /**
     * Reads the leading VarInt without touching the buffer's state.
     *
     * <p>Absolute {@code getByte} rather than {@code duplicate()}: a duplicate is a real
     * object, allocated per packet, to read at most five bytes.
     */
    private static int peekId(ByteBuf frame) {
        int index = frame.readerIndex();
        int end = frame.writerIndex();
        int value = 0;
        for (int shift = 0; shift < 35 && index < end; shift += 7) {
            int b = frame.getByte(index++) & 0xFF;
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
        }
        return UNREADABLE;
    }

    private static long pack(int id, int bytes) {
        return ((long) id << 32) | (bytes & 0xFFFFFFFFL);
    }

    long total() {
        return total;
    }

    /** Oldest first, e.g. {@code "0x9(14B), 0x0(2B), 0x7(5B)"}. */
    String recent() {
        if (size == 0) {
            return "nothing";
        }
        StringJoiner joiner = new StringJoiner(", ");
        int oldest = (next - size + CAPACITY) % CAPACITY;
        for (int i = 0; i < size; i++) {
            int slot = (oldest + i) % CAPACITY;
            Class<?> type = packets[slot];
            if (type != null) {
                joiner.add(type.getSimpleName());
                continue;
            }
            long packed = frames[slot];
            int id = (int) (packed >> 32);
            int bytes = (int) packed;
            joiner.add(id == UNREADABLE
                    ? "unreadable(" + bytes + "B)"
                    : "0x" + Integer.toHexString(id) + "(" + bytes + "B)");
        }
        return joiner.toString();
    }
}
