package dev.relay.metrics;

import dev.relay.net.pipeline.TrafficCounter;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The §10 counters, and the handler that feeds them.
 *
 * <p>The handler half matters more than it looks. It sits at the head of every pipeline,
 * on the path every packet takes, and the last thing this project put on that path
 * disconnected every player on 1.21 backends because it touched a promise it should have
 * left alone. A counter is exactly the kind of code nobody re-reads, so what it must
 * <em>not</em> do is asserted here rather than assumed.
 */
class MetricsTest {

    @Test
    void countersAccumulate() {
        Metrics metrics = new Metrics(() -> 3);

        metrics.connectionOpened();
        metrics.connectionOpened();
        metrics.connectionClosed();
        metrics.connectionFailed();
        metrics.switched();
        metrics.failover();
        metrics.routeAttempted();
        metrics.routeAttempted();
        metrics.routeFailed();

        Metrics.Snapshot snapshot = metrics.snapshot();
        assertEquals(2, snapshot.connectionsTotal());
        assertEquals(1, snapshot.connectionsActive(), "one of the two was closed");
        assertEquals(1, snapshot.connectionsFailed());
        assertEquals(1, snapshot.switches());
        assertEquals(1, snapshot.failovers());
        assertEquals(2, snapshot.routeDecisions());
        assertEquals(1, snapshot.routeFailures());
    }

    /**
     * The two sides of the proxy are counted apart.
     *
     * <p>The first version added them together, which made the "in" and "out" charts draw
     * the same line: a forwarder receives from a backend and sends to a player, so the
     * totals agree to within a rounding error and say nothing.
     */
    @Test
    void playerAndBackendTrafficAreSeparate() {
        Metrics metrics = new Metrics(() -> 0);

        metrics.wireBytes(true, true, 100);      // player sent us 100
        metrics.wireBytes(true, false, 5_000);   // we sent the player 5000
        metrics.wireBytes(false, true, 5_000);   // which arrived from a backend
        metrics.wireBytes(false, false, 100);    // and their input went on to it

        Metrics.Snapshot snapshot = metrics.snapshot();
        assertEquals(100, snapshot.playerBytesIn());
        assertEquals(5_000, snapshot.playerBytesOut());
        assertEquals(5_000, snapshot.backendBytesIn());
        assertEquals(100, snapshot.backendBytesOut());
    }

    @Test
    void historyIsEmptyUntilTheSamplerHasRun() {
        Metrics metrics = new Metrics(() -> 0);
        assertEquals(0, metrics.snapshot().history().size(),
                "a chart drawn from a single reading invents a trend that has not happened yet");
        assertTrue(metrics.snapshot().sampleMillis() > 0,
                "a client needs the interval to label the window it is drawing");
    }

    // ------------------------------------------------------------------ the handler

    @Test
    void theCounterSeesBytesInBothDirections() {
        Metrics metrics = new Metrics(() -> 0);
        EmbeddedChannel channel = new EmbeddedChannel(new TrafficCounter(metrics, true));

        channel.writeInbound(Unpooled.copiedBuffer(new byte[64]));
        channel.writeOutbound(Unpooled.copiedBuffer(new byte[16]));

        Metrics.Snapshot snapshot = metrics.snapshot();
        assertEquals(64, snapshot.playerBytesIn());
        assertEquals(16, snapshot.playerBytesOut());
        assertEquals(0, snapshot.backendBytesIn(), "a player-side channel is not a backend");
    }

    /**
     * A void promise must survive contact with the counter.
     *
     * <p>Paper writes play packets with one from 1.21, and {@code addListener} on a void
     * promise throws {@code IllegalStateException}. Relay's own Paper plugin did exactly
     * that and disconnected every player a packet after login. Nothing on the hot path
     * gets to touch a promise, and this fails if something starts.
     */
    @Test
    void aVoidPromiseIsLeftAlone() {
        Metrics metrics = new Metrics(() -> 0);
        EmbeddedChannel channel = new EmbeddedChannel(new TrafficCounter(metrics, false));

        channel.writeAndFlush(Unpooled.copiedBuffer(new byte[32]), channel.voidPromise());

        assertTrue(channel.isActive(), "the counter killed the connection it was measuring");
        assertEquals(32, metrics.snapshot().backendBytesOut());
        assertNotNull(channel.readOutbound(), "the packet never reached the wire");
    }

    /** Anything that is not a buffer passes through untouched and uncounted. */
    @Test
    void nonBufferMessagesAreIgnored() {
        Metrics metrics = new Metrics(() -> 0);
        EmbeddedChannel channel = new EmbeddedChannel(new TrafficCounter(metrics, true));

        channel.writeInbound("a decoded packet, not bytes");

        assertEquals(0, metrics.snapshot().playerBytesIn());
        assertEquals("a decoded packet, not bytes", channel.readInbound());
    }

    /** Reading the size must not consume the buffer the rest of the pipeline needs. */
    @Test
    void theBufferIsPassedOnUnread() {
        Metrics metrics = new Metrics(() -> 0);
        EmbeddedChannel channel = new EmbeddedChannel(new TrafficCounter(metrics, true));

        channel.writeInbound(Unpooled.copiedBuffer(new byte[]{1, 2, 3, 4}));

        ByteBuf forwarded = channel.readInbound();
        assertEquals(4, forwarded.readableBytes(),
                "the counter consumed bytes the decoder was going to need");
        forwarded.release();
    }
}
