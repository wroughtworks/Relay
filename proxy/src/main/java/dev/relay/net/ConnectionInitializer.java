package dev.relay.net;

import dev.relay.net.pipeline.CloseTracer;
import dev.relay.net.pipeline.MinecraftDecoder;
import dev.relay.net.pipeline.MinecraftEncoder;
import dev.relay.net.pipeline.VarintFrameDecoder;
import dev.relay.net.pipeline.VarintLengthEncoder;
import dev.relay.protocol.PacketDirection;
import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.haproxy.HAProxyMessageDecoder;
import io.netty.handler.codec.haproxy.HAProxyMessageEncoder;
import io.netty.handler.timeout.ReadTimeoutHandler;

import java.util.concurrent.TimeUnit;

/**
 * Builds the standard pipeline for a Minecraft channel.
 *
 * <p>Both ends use the same layout; only the direction of the codecs differs. See
 * {@link Pipeline} for why the order is what it is.
 */
public final class ConnectionInitializer {

    private ConnectionInitializer() {
    }

    /**
     * @param inbound the direction of traffic Relay <em>receives</em> on this channel:
     *                serverbound from a player, clientbound from a backend
     */
    public static MinecraftConnection initialize(Channel channel, PacketDirection inbound, int readTimeoutMillis) {
        return initialize(channel, inbound, readTimeoutMillis, false, false);
    }

    /**
     * @param acceptProxyProtocol read a PROXY header from the peer before anything else
     * @param sendProxyProtocol   allow a PROXY header to be written ahead of the handshake
     */
    public static MinecraftConnection initialize(Channel channel, PacketDirection inbound, int readTimeoutMillis,
                                                 boolean acceptProxyProtocol, boolean sendProxyProtocol) {
        return initialize(channel, inbound, readTimeoutMillis, acceptProxyProtocol, sendProxyProtocol, null);
    }

    /**
     * @param traceDescription when non-null, installs a {@link CloseTracer} under this
     *                         label to record what ends the connection
     */
    public static MinecraftConnection initialize(Channel channel, PacketDirection inbound, int readTimeoutMillis,
                                                 boolean acceptProxyProtocol, boolean sendProxyProtocol,
                                                 String traceDescription) {
        MinecraftDecoder decoder = new MinecraftDecoder(inbound);
        MinecraftEncoder encoder = new MinecraftEncoder(inbound.opposite());

        ChannelPipeline pipeline = channel.pipeline();
        pipeline.addLast(Pipeline.READ_TIMEOUT,
                new ReadTimeoutHandler(readTimeoutMillis, TimeUnit.MILLISECONDS));
        // Only a player can open with a pre-1.7 legacy ping; a backend never does.
        pipeline.addLast(Pipeline.FRAME_DECODER,
                new VarintFrameDecoder(inbound == PacketDirection.SERVERBOUND));
        pipeline.addLast(Pipeline.FRAME_ENCODER, VarintLengthEncoder.INSTANCE);
        pipeline.addLast(Pipeline.MINECRAFT_DECODER, decoder);
        pipeline.addLast(Pipeline.MINECRAFT_ENCODER, encoder);

        MinecraftConnection connection = new MinecraftConnection(channel, inbound, decoder, encoder);
        pipeline.addLast(Pipeline.HANDLER, connection);

        if (acceptProxyProtocol) {
            // Ahead of the frame decoder: the header arrives before, and outside of,
            // Minecraft's own length framing.
            pipeline.addFirst(Pipeline.PROXY_PROTOCOL_HANDLER, new ProxyProtocolHandler(connection));
            pipeline.addFirst(Pipeline.PROXY_PROTOCOL_DECODER, new HAProxyMessageDecoder());
        }
        if (traceDescription != null) {
            // At the very head: an outbound close passes through it last, so it is seen
            // whichever handler triggered it, and an exception fired at the head reaches
            // it before anything else can swallow the connection.
            // A backend hanging up mid-session is always a fault, so those are warned
            // about; players disconnect normally and stay at debug level.
            boolean isBackend = inbound == PacketDirection.CLIENTBOUND;
            pipeline.addFirst(Pipeline.CLOSE_TRACER, new CloseTracer(traceDescription, isBackend));
        }
        if (sendProxyProtocol) {
            // Outbound handlers run from the tail towards the head, so placing this
            // first makes it the last thing to touch a write -- meaning the header it
            // emits is not wrapped in a length prefix by the frame encoder, and lands on
            // the wire exactly as the peer expects to read it.
            pipeline.addFirst(Pipeline.PROXY_PROTOCOL_ENCODER, HAProxyMessageEncoder.INSTANCE);
        }
        return connection;
    }
}
