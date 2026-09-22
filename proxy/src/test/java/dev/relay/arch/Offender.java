package dev.relay.arch;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;

/**
 * The bug, in miniature, kept so the rule can be shown to catch something.
 *
 * <p>This is what {@code ConnectionWatcher} looked like before the fix: a listener on
 * every write, and no question asked about whether the promise could carry one. Against a
 * Paper 1.21 backend that threw on the first packet after a join and had every player
 * disconnected.
 *
 * <p>It lives in <b>test</b> sources on purpose. The rule scans each module's {@code main}
 * classes, so a fixture that has to be broken cannot sit among them &mdash; it would fail
 * the very check it exists to demonstrate. Keeping it here also means the checker is
 * proved against real compiled bytecode rather than a string somebody wrote by hand.
 */
final class Offender extends ChannelDuplexHandler {

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
        promise.addListener(future -> {
            if (!future.isSuccess()) {
                System.out.println("write failed");
            }
        });
        ctx.write(msg, promise);
    }
}
