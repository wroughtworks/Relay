package dev.relay.net.pipeline;

import dev.relay.metrics.Metrics;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;

/**
 * Counts wire bytes, and nothing else.
 *
 * <p>Sits at the head of the pipeline, which is the only place the numbers mean what an
 * operator assumes they mean: after encryption and compression on the way out, before
 * either on the way in. Counted anywhere further down and "bandwidth" would be the size of
 * the data before it was made smaller, which is a different and much less useful quantity.
 *
 * <h2>Rules this handler obeys</h2>
 * It is on the path every packet takes, so it does the least it possibly can:
 *
 * <ul>
 *   <li><b>It never touches the promise.</b> Not even to read it. Paper's own promises are
 *       void from 1.21, and Relay's plugin learned the hard way that {@code addListener} on
 *       a void promise throws, reaches {@code exceptionCaught}, and disconnects the player
 *       -- a diagnostic that caused the fault it was watching for. A counter has no reason
 *       to know whether a write succeeded.</li>
 *   <li><b>It cannot throw.</b> The only work is an {@code instanceof} and an add.</li>
 *   <li><b>It never retains or releases a buffer.</b> Reading {@code readableBytes} does
 *       not consume, so ownership is untouched and every message is passed straight on.</li>
 * </ul>
 *
 * <p>{@code LongAdder} underneath, which is what makes this cheap enough to sit here: every
 * event loop writes to its own cell, and nothing contends until somebody reads.
 */
public final class TrafficCounter extends ChannelDuplexHandler {

    private final Metrics metrics;
    private final boolean playerSide;

    /** @param playerSide true when this channel faces a player rather than a backend */
    public TrafficCounter(Metrics metrics, boolean playerSide) {
        this.metrics = metrics;
        this.playerSide = playerSide;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof ByteBuf buf) {
            metrics.wireBytes(playerSide, true, buf.readableBytes());
        }
        ctx.fireChannelRead(msg);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
        if (msg instanceof ByteBuf buf) {
            metrics.wireBytes(playerSide, false, buf.readableBytes());
        }
        ctx.write(msg, promise);
    }
}
