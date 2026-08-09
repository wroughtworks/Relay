package dev.relay.net.pipeline;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Records what closes a channel, and from where.
 *
 * <p>{@link dev.relay.net.MinecraftConnection} tracks closes it performs itself, but a
 * Netty pipeline has other ways to end a channel: a handler calling {@code ctx.close()}
 * directly, an idle-state handler, a failed write, or the peer hanging up. Those are
 * indistinguishable afterwards &mdash; the channel is simply gone &mdash; and that
 * ambiguity is exactly what makes a connection that dies for no stated reason so hard to
 * pin down.
 *
 * <p>Sits at the head of the pipeline, so an outbound close passes through it last and is
 * seen no matter which handler triggered it.
 */
public final class CloseTracer extends ChannelDuplexHandler {

    private static final Logger LOG = LoggerFactory.getLogger(CloseTracer.class);

    private final String description;
    private final boolean warnOnPeerClose;
    private boolean localCloseSeen;

    /**
     * @param warnOnPeerClose raise the peer-closed message to a warning. Set for backend
     *                        connections, where a peer hanging up mid-session is a fault;
     *                        left off for players, who disconnect normally all the time.
     */
    public CloseTracer(String description, boolean warnOnPeerClose) {
        this.description = description;
        this.warnOnPeerClose = warnOnPeerClose;
    }

    /**
     * A close originating inside this process.
     *
     * <p>The stack trace names the handler responsible, which is the difference between
     * "the peer went away" and "this specific code hung up".
     */
    // An orderly close is routine, so it is logged at DEBUG; only the abnormal cases
    // below --- a failed write, an exception reaching the head --- warrant a warning.

    @Override
    public void close(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception {
        if (!localCloseSeen) {
            localCloseSeen = true;
            LOG.debug("{}: Relay is closing this connection. Called from:", description,
                    new Throwable("close requested here"));
        }
        super.close(ctx, promise);
    }

    @Override
    public void disconnect(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception {
        if (!localCloseSeen) {
            localCloseSeen = true;
            LOG.debug("{}: Relay is disconnecting this connection. Called from:", description,
                    new Throwable("disconnect requested here"));
        }
        super.disconnect(ctx, promise);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        if (!localCloseSeen) {
            String message = "{}: the PEER closed this connection; Relay did not.";
            if (warnOnPeerClose) {
                LOG.warn(message, description);
            } else {
                LOG.debug(message, description);
            }
        }
        super.channelInactive(ctx);
    }

    /**
     * A failed write.
     *
     * <p>Write failures land on the promise rather than reaching {@code exceptionCaught},
     * so without this they close a connection with nothing logged anywhere.
     */
    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (!promise.isVoid()) {
            promise.addListener(future -> {
                if (!future.isSuccess() && future.cause() != null) {
                    LOG.warn("{}: a write failed", description, future.cause());
                }
            });
        }
        super.write(ctx, msg, promise);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        LOG.warn("{}: exception reached the head of the pipeline", description, cause);
        super.exceptionCaught(ctx, cause);
    }
}
