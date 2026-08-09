package dev.relay.net;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.haproxy.HAProxyCommand;
import io.netty.handler.codec.haproxy.HAProxyMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;

/**
 * Reads the PROXY header from an inbound connection and adopts the address it declares.
 *
 * <p>For use when something sits in front of Relay &mdash; HAProxy, or a load balancer.
 * Without this the proxy would see every player as coming from that device, which breaks
 * per-IP logging, and would then forward that same wrong address on to backends.
 *
 * <p><b>This trusts whatever the header says.</b> Anything able to open a TCP connection
 * to the listener can claim any source address, so it must only be enabled when the port
 * is reachable solely by the upstream proxy.
 */
public final class ProxyProtocolHandler extends ChannelInboundHandlerAdapter {

    private static final Logger LOG = LoggerFactory.getLogger(ProxyProtocolHandler.class);

    private final MinecraftConnection connection;

    public ProxyProtocolHandler(MinecraftConnection connection) {
        this.connection = connection;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof HAProxyMessage header)) {
            ctx.fireChannelRead(msg);
            return;
        }

        try {
            if (header.command() == HAProxyCommand.PROXY && header.sourceAddress() != null) {
                connection.setRealRemoteAddress(
                        new InetSocketAddress(header.sourceAddress(), header.sourcePort()));
            }
            // LOCAL is the upstream's own health check rather than a relayed player;
            // there is no address to adopt, and the connection continues as itself.
        } finally {
            header.release();
        }

        // One header per connection. Both this and the decoder ahead of it have done
        // their job, and leaving the decoder in place would be fatal: on the next read it
        // would try to parse game traffic as another header and call ctx.close() before
        // throwing, killing the connection with no exception anyone can log.
        //
        // The decoder may already have removed itself, so its absence is not an error.
        if (ctx.pipeline().get(Pipeline.PROXY_PROTOCOL_DECODER) != null) {
            ctx.pipeline().remove(Pipeline.PROXY_PROTOCOL_DECODER);
        }
        ctx.pipeline().remove(this);
        LOG.debug("Adopted PROXY protocol source address {}", connection.remoteAddress());
    }
}
