package dev.relay.net;

import dev.relay.net.pipeline.CipherDecoder;
import dev.relay.net.pipeline.CipherEncoder;
import dev.relay.net.pipeline.CompressionDecoder;
import dev.relay.net.pipeline.CompressionEncoder;
import dev.relay.net.pipeline.MinecraftDecoder;
import dev.relay.net.pipeline.Precompressed;
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
import io.netty.channel.socket.DuplexChannel;
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

    /**
     * The peer that spoke PROXY protocol on a player's behalf, or {@code null}.
     *
     * <p>When a header has been read, the socket's own peer is no longer the player: it
     * is whatever sat in front and declared them. That is the only evidence Relay has
     * that an upstream exists at all, and it is worth keeping rather than discarding
     * once the real address has been recovered from it.
     */
    public SocketAddress upstreamAddress() {
        return realRemoteAddress == null ? null : channel.remoteAddress();
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

    /**
     * Relays a frame, reusing the sender's compression work when this connection can.
     *
     * <p>A frame arriving from a backend was inflated so Relay could read its packet id.
     * Deflating it again to send it on is the single most expensive thing this proxy does
     * -- about ten times the cost of the inflate -- and produces different bytes that mean
     * the same thing. When the two connections agree closely enough on compression, the
     * original can go out untouched.
     *
     * <h2>When that is allowed</h2>
     * This connection's threshold must be <em>at or below</em> the source's. A client
     * rejects a frame whose declared uncompressed size is under the threshold it
     * negotiated, and the source only compressed frames at or above its own; so a lower
     * threshold here accepts everything the source produced, and a higher one would not.
     * Equal thresholds are the common case and satisfy it.
     *
     * <p>Uncompressed frames pass through under the same rule and are always safe: the
     * zero marker means "not compressed" and carries no size to check.
     *
     * @param source the connection the frame was read from
     */
    public void relayFrom(MinecraftConnection source, Object msg) {
        if (msg instanceof ByteBuf frame && source != null) {
            CompressionDecoder theirs = source.compressionDecoder();
            CompressionEncoder mine = compressionEncoder();
            if (theirs != null && mine != null && mine.threshold() <= theirs.threshold()) {
                ByteBuf original = theirs.takeOriginalFor(frame);
                if (original != null) {
                    // Ownership of `original` passes to the write; the inflated frame is
                    // still owned by the caller and released by them as usual.
                    write(new Precompressed(original));
                    return;
                }
            }
        }
        relay(msg);
    }

    private CompressionDecoder compressionDecoder() {
        return channel.pipeline().get(CompressionDecoder.class);
    }

    private CompressionEncoder compressionEncoder() {
        return channel.pipeline().get(CompressionEncoder.class);
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

    /**
     * Sends a frame received from elsewhere, then closes once it has reached the socket.
     *
     * <p>For handing on a packet Relay cannot rebuild: a backend's kick, whose reason is
     * network NBT from 1.20.3 onward and which Relay writes but cannot read. Forwarding
     * the bytes keeps the server's exact wording rather than replacing it with a summary.
     */
    public void closeWithFrame(ByteBuf frame) {
        if (!isActive()) {
            frame.release();
            return;
        }
        closed = true;
        closedLocally = true;
        channel.writeAndFlush(frame).addListener(ChannelFutureListener.CLOSE);
    }

    public void close() {
        closed = true;
        closedLocally = true;
        if (channel.isActive()) {
            channel.close();
        }
    }

    /**
     * Closes the way a peer would prefer: a half-close first, then a full one.
     *
     * <p>Closing a socket outright while data is still arriving on it makes TCP answer
     * with a reset, and the peer's next write fails. A backend mid-switch is always in
     * that state -- it has no idea the player is leaving and keeps sending world updates
     * -- so a plain close made Paper log a stack trace, {@code Connection reset by peer},
     * every single time anyone typed {@code /server}.
     *
     * <p>Shutting down only the outbound half sends a FIN instead. The backend reads end
     * of stream, sees an ordinary disconnect, and closes its own side; reads keep draining
     * here in the meantime so nothing is left unread to provoke a reset. If the peer does
     * not take the hint, {@code linger} closes it properly anyway &mdash; a half-open
     * socket left forever would be worse than the noise this avoids.
     */
    public void closeGracefully(long lingerMillis) {
        closed = true;
        closedLocally = true;
        if (!channel.isActive()) {
            return;
        }
        if (!(channel instanceof DuplexChannel duplex)) {
            channel.close();
            return;
        }

        // Reading stays on deliberately. Anything still in flight is discarded by the
        // session handler, and draining it is what keeps the close from being a reset.
        duplex.shutdownOutput().addListener(future -> {
            if (!future.isSuccess()) {
                channel.close();
            }
        });
        channel.eventLoop().schedule(() -> {
            if (channel.isActive()) {
                channel.close();
            }
        }, lingerMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
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
