package dev.relay.net;

import dev.relay.net.pipeline.CipherDecoder;
import dev.relay.net.pipeline.CipherEncoder;
import dev.relay.net.pipeline.CompressionDecoder;
import dev.relay.net.pipeline.CompressionEncoder;
import dev.relay.net.pipeline.MinecraftDecoder;
import dev.relay.net.pipeline.MinecraftEncoder;
import dev.relay.protocol.Packet;
import dev.relay.protocol.PacketDirection;
import dev.relay.protocol.ProtocolException;
import dev.relay.protocol.ProtocolState;
import dev.relay.protocol.ProtocolVersion;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import java.net.SocketAddress;
// Netty's own timeout type, not java.util.concurrent's. ReadTimeoutException extends
// this one; matching on the JDK class silently never fires.
import io.netty.handler.timeout.TimeoutException;

import static dev.relay.net.Pipeline.CIPHER_DECODER;
import static dev.relay.net.Pipeline.CIPHER_ENCODER;
import static dev.relay.net.Pipeline.COMPRESSION_DECODER;
import static dev.relay.net.Pipeline.COMPRESSION_ENCODER;
import static dev.relay.net.Pipeline.FRAME_DECODER;
import static dev.relay.net.Pipeline.MINECRAFT_DECODER;

/**
 * One Minecraft connection &mdash; a player socket or a backend socket &mdash; and the
 * single place that mutates its pipeline.
 *
 * <p>Protocol state, compression and encryption all have to change mid-stream at exactly
 * the right frame boundary. Concentrating those transitions here means the session
 * handlers can stay declarative about <em>when</em> to switch without each re-deriving
 * <em>how</em>.
 */
public final class MinecraftConnection extends ChannelInboundHandlerAdapter {

    private static final Logger LOG = LoggerFactory.getLogger(MinecraftConnection.class);

    private final Channel channel;
    private final PacketDirection inboundDirection;
    private final MinecraftDecoder decoder;
    private final MinecraftEncoder encoder;

    private ProtocolState state = ProtocolState.HANDSHAKE;
    private ProtocolVersion version = ProtocolVersion.oldest();
    private SessionHandler sessionHandler;
    private SocketAddress realRemoteAddress;
    private boolean closed;
    private volatile boolean closedLocally;

    public MinecraftConnection(Channel channel, PacketDirection inboundDirection,
                               MinecraftDecoder decoder, MinecraftEncoder encoder) {
        this.channel = channel;
        this.inboundDirection = inboundDirection;
        this.decoder = decoder;
        this.encoder = encoder;
    }

    public Channel channel() {
        return channel;
    }

    /**
     * The peer's address, preferring one declared by a PROXY protocol header.
     *
     * <p>Behind a load balancer the socket address is the balancer's, so everything that
     * identifies a player by address &mdash; logging, and the address forwarded on to
     * backends &mdash; has to read through to the declared one.
     */
    public SocketAddress remoteAddress() {
        SocketAddress declared = realRemoteAddress;
        return declared != null ? declared : channel.remoteAddress();
    }

    /** Called by {@link ProxyProtocolHandler} once an inbound PROXY header is parsed. */
    void setRealRemoteAddress(SocketAddress address) {
        this.realRemoteAddress = address;
    }

    public ProtocolState state() {
        return state;
    }

    public ProtocolVersion version() {
        return version;
    }

    public boolean isActive() {
        return channel.isActive() && !closed;
    }

    public SessionHandler sessionHandler() {
        return sessionHandler;
    }

    // ------------------------------------------------------------ state transitions

    public void setState(ProtocolState state) {
        this.state = state;
        decoder.setState(state);
        encoder.setState(state);
    }

    public void setVersion(ProtocolVersion version) {
        this.version = version;
        decoder.setVersion(version);
        encoder.setVersion(version);
    }

    public void setSessionHandler(SessionHandler handler) {
        if (this.sessionHandler != null) {
            this.sessionHandler.deactivated();
        }
        this.sessionHandler = handler;
        handler.activated();
    }

    /**
     * Installs the compression codecs.
     *
     * <p>Must be called only once the Set Compression packet has been fully written or
     * read: the threshold applies from the <em>next</em> frame, so switching a frame too
     * early desynchronises the stream irrecoverably.
     */
    public void enableCompression(int threshold, int level) {
        if (threshold < 0) {
            return;
        }
        if (channel.pipeline().get(COMPRESSION_DECODER) != null) {
            return;
        }
        channel.pipeline().addBefore(MINECRAFT_DECODER, COMPRESSION_DECODER, new CompressionDecoder(threshold));
        channel.pipeline().addBefore(MINECRAFT_DECODER, COMPRESSION_ENCODER, new CompressionEncoder(threshold, level));
    }

    /**
     * Installs the AES/CFB8 codecs at the head of the pipeline, ahead of framing.
     *
     * <p>Same ordering constraint as compression: the Encryption Response is the last
     * plaintext frame in either direction.
     */
    public void enableEncryption(byte[] sharedSecret) {
        if (channel.pipeline().get(CIPHER_DECODER) != null) {
            throw new IllegalStateException("Encryption is already enabled on " + remoteAddress());
        }
        Cipher decrypt = Encryption.cipher(Cipher.DECRYPT_MODE, sharedSecret);
        Cipher encrypt = Encryption.cipher(Cipher.ENCRYPT_MODE, sharedSecret);
        channel.pipeline().addBefore(FRAME_DECODER, CIPHER_DECODER, new CipherDecoder(decrypt));
        channel.pipeline().addBefore(FRAME_DECODER, CIPHER_ENCODER, new CipherEncoder(encrypt));
    }

    // ------------------------------------------------------------ writing

    /** Queues a packet without flushing. Use when several packets go out together. */
    public void delayedWrite(Object msg) {
        if (isActive()) {
            channel.write(msg, channel.voidPromise());
        } else {
            ReferenceCountUtil.release(msg);
        }
    }

    /**
     * Writes a message, taking ownership of it.
     *
     * <p>Use for messages this connection created. To pass on something that arrived on
     * the <em>other</em> connection, use {@link #relay} instead.
     */
    public void write(Object msg) {
        if (isActive()) {
            channel.writeAndFlush(msg, channel.voidPromise());
        } else {
            ReferenceCountUtil.release(msg);
        }
    }

    /**
     * Passes on a message received from the peer connection, without taking ownership.
     *
     * <p>The retain is load-bearing. A relayed frame has exactly one reference, held by
     * the reading connection's {@link #channelRead}, which releases it when the callback
     * returns. Writing it here hands it to a {@link io.netty.handler.codec.MessageToByteEncoder}
     * further down the pipeline, and that releases every message it accepts &mdash; so
     * without this retain the frame is freed twice, and the second release throws.
     *
     * <p>That throw lands on the connection the frame was <em>read</em> from, so a
     * backend relaying world data to a player kills the backend link on its first play
     * packet: login and configuration both complete, then the client waits forever for a
     * world that is never forwarded.
     */
    public void relay(Object msg) {
        write(ReferenceCountUtil.retain(msg));
    }

    public void flush() {
        if (isActive()) {
            channel.flush();
        }
    }

    /** Sends a final packet, then closes once it has actually reached the socket. */
    public void closeWith(Packet packet) {
        if (!isActive()) {
            return;
        }
        closed = true;
        closedLocally = true;
        ChannelFuture future = channel.writeAndFlush(packet);
        future.addListener(ChannelFutureListener.CLOSE);
    }

    public void close() {
        closed = true;
        closedLocally = true;
        if (channel.isActive()) {
            channel.close();
        }
    }

    /**
     * Whether Relay closed this connection, as opposed to the peer hanging up.
     *
     * <p>The two are indistinguishable once the socket is gone, but they point at
     * completely different faults: one is a bug in Relay, the other in the peer.
     */
    public boolean closedLocally() {
        return closedLocally;
    }

    // ------------------------------------------------------------ netty callbacks

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (sessionHandler == null) {
            ReferenceCountUtil.release(msg);
            return;
        }
        try {
            if (msg instanceof Packet packet) {
                if (!packet.handle(sessionHandler)) {
                    sessionHandler.handleUnhandled(packet);
                }
            } else if (msg instanceof ByteBuf frame) {
                sessionHandler.handleUnknown(frame);
            }
        } finally {
            ReferenceCountUtil.release(msg);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        closed = true;
        if (sessionHandler != null) {
            sessionHandler.disconnected();
        }
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) {
        if (sessionHandler != null) {
            sessionHandler.writabilityChanged();
        }
    }

    /** True when this connection is Relay talking to a backend, rather than to a player. */
    private boolean isBackend() {
        return inboundDirection == PacketDirection.CLIENTBOUND;
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        boolean expected = cause instanceof ProtocolException || cause instanceof TimeoutException;
        boolean established = state == ProtocolState.PLAY || state == ProtocolState.CONFIGURATION;
        if (expected && !isBackend() && !established) {
            // A malformed or stalled peer is routine on an internet-facing port before a
            // session exists, and logging every one at WARN would let anyone flood the
            // log from outside.
            LOG.debug("{} dropped: {}", remoteAddress(), cause.getMessage());
        } else if (expected && !isBackend()) {
            // Past login the peer is a real player whose client Relay already accepted.
            // A protocol error here is Relay's bug, not abuse, and silencing it hides
            // exactly the failure that presents as a client stuck loading the world.
            LOG.warn("Dropping {} in {} state: {}", remoteAddress(), state,
                    cause instanceof TimeoutException
                            ? "no data received before the read timeout elapsed"
                            : cause.getMessage());
            LOG.debug("Player protocol error detail", cause);
        } else if (expected) {
            // A backend is trusted infrastructure. If Relay cannot parse what it sends,
            // or it goes quiet, that is a version or configuration mismatch an operator
            // has to see.
            // ReadTimeoutException carries no message, so say what the silence means.
            String detail = cause instanceof TimeoutException
                    ? "no data received before the read timeout elapsed"
                    : cause.getMessage();
            LOG.error("{} talking to backend {}: {}",
                    cause instanceof TimeoutException ? "Timed out" : "Protocol error",
                    remoteAddress(), detail);
            LOG.debug("Backend failure detail", cause);
        } else {
            LOG.error("Unhandled exception on connection from {}", remoteAddress(), cause);
        }
        if (sessionHandler != null) {
            sessionHandler.exception(cause);
        }
        close();
    }
}
