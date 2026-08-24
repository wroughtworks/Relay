package dev.relay.paper;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Watches one player's channel and reports anything that ends it.
 *
 * <p>Sits at the head of the pipeline so it observes every close, whichever handler
 * causes it. The important method is {@link #close}: it captures a stack trace at the
 * moment the close is requested, which names the server code responsible. Without it, a
 * connection that Paper tears down internally is indistinguishable from a player
 * quitting.
 */
final class ConnectionWatcher extends ChannelDuplexHandler {

    private final Reporter plugin;
    private final String player;
    private final boolean logPackets;

    private volatile boolean closeReported;
    private long packetsIn;
    private long packetsOut;

    ConnectionWatcher(Reporter plugin, String player, boolean logPackets) {
        this.plugin = plugin;
        this.player = player;
        this.logPackets = logPackets;
    }

    /**
     * A close requested from inside the server.
     *
     * <p>The stack trace is the whole point: it distinguishes "the player's client went
     * away" from "this specific server code decided to hang up", and names the latter.
     */
    @Override
    public void close(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception {
        if (!closeReported) {
            closeReported = true;
            plugin.report(String.format(
                    "%s: the SERVER is closing this connection after %d packets in / %d out.%n"
                            + "Called from:%n%s",
                    player, packetsIn, packetsOut, currentStack()));
        }
        super.close(ctx, promise);
    }

    /** An exception Paper would otherwise log at DEBUG, or swallow entirely. */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        plugin.report(String.format(
                "%s: exception on the connection after %d packets in / %d out",
                player, packetsIn, packetsOut), cause);
        super.exceptionCaught(ctx, cause);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        if (!closeReported) {
            // No close() came through this handler, so the other end hung up first.
            plugin.report(String.format(
                    "%s: the connection went away from the CLIENT side after %d packets in / %d out. "
                            + "The server did not close it.",
                    player, packetsIn, packetsOut));
        }
        super.channelInactive(ctx);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        packetsIn++;
        if (logPackets) {
            plugin.detail(player + " IN  " + describe(msg));
        }
        super.channelRead(ctx, msg);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        packetsOut++;
        if (logPackets) {
            plugin.detail(player + " OUT " + describe(msg));
        }
        // A write that fails is a common way for a connection to die quietly: the failure
        // lands on the promise, not on exceptionCaught. So the promise is worth watching
        // -- unless it is a *void* promise, which cannot carry a listener at all.
        // addListener on one throws IllegalStateException("void future").
        //
        // Paper sends play packets that way from 1.21 onwards. This handler therefore
        // threw on the first packet after a player joined, the exception travelled up to
        // Connection.exceptionCaught, and Paper disconnected them: every player, every
        // join, on every 1.21 backend. A diagnostic that caused the fault it existed to
        // observe, and invisible on 1.20.2, where the promises are real.
        //
        // Nothing is lost by skipping it. Netty reports a void promise's failure through
        // exceptionCaught instead, which this handler already watches -- that is what
        // "void" means. Unvoiding to keep the listener would work too, at the cost of an
        // allocation for every packet the server sends.
        if (!promise.isVoid()) {
            promise.addListener(future -> {
                if (!future.isSuccess() && future.cause() != null) {
                    plugin.report(player + ": a write to this connection failed", future.cause());
                }
            });
        }
        super.write(ctx, msg, promise);
    }

    private static String describe(Object msg) {
        if (msg == null) {
            return "null";
        }
        String name = msg.getClass().getSimpleName();
        return name.isEmpty() ? msg.getClass().getName() : name;
    }

    private static String currentStack() {
        StringWriter writer = new StringWriter();
        // Deliberately constructed, not thrown: the trace is the diagnostic.
        new Throwable("close requested here").printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }
}
