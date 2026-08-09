package dev.relay.session;

import dev.relay.net.MinecraftConnection;
import io.netty.buffer.ByteBuf;
import io.netty.util.ReferenceCountUtil;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Holds packets that arrived before the other end was ready for them.
 *
 * <p>Both sides of a server switch produce these gaps: the client starts sending
 * configuration packets the moment it acknowledges login, while the backend is still
 * being connected; and a new backend starts sending registry data before the client has
 * agreed to leave play state. Dropping either would corrupt the session.
 *
 * <p>The queue is bounded. An unbounded one would let a peer that never reaches the
 * ready state pin memory indefinitely, so overflow is treated as a protocol failure and
 * reported to the caller rather than absorbed.
 */
final class PacketQueue {

    private static final int MAX_PACKETS = 256;
    private static final int MAX_BYTES = 4 * 1024 * 1024;

    private final Deque<Object> queued = new ArrayDeque<>();
    private int queuedBytes;

    /**
     * Takes ownership of {@code msg}, retaining it if it is reference counted.
     *
     * @return {@code false} if the queue is full, in which case {@code msg} was not
     *         queued and the connection should be dropped
     */
    boolean offer(Object msg) {
        int size = msg instanceof ByteBuf buf ? buf.readableBytes() : 0;
        if (queued.size() >= MAX_PACKETS || queuedBytes + size > MAX_BYTES) {
            return false;
        }
        queued.add(ReferenceCountUtil.retain(msg));
        queuedBytes += size;
        return true;
    }

    /** Writes everything queued, in order, and flushes once. */
    void drainTo(MinecraftConnection connection) {
        Object msg;
        while ((msg = queued.poll()) != null) {
            connection.delayedWrite(msg);
        }
        queuedBytes = 0;
        connection.flush();
    }

    /** Releases anything still queued. Call when the session dies mid-transition. */
    void clear() {
        Object msg;
        while ((msg = queued.poll()) != null) {
            ReferenceCountUtil.release(msg);
        }
        queuedBytes = 0;
    }

    boolean isEmpty() {
        return queued.isEmpty();
    }
}
