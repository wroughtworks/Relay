package dev.relay.net;

/**
 * Handler names, and the order they sit in.
 *
 * <p>Reading down the list is reading the inbound path; reading up it is the outbound
 * path. Getting this order wrong produces symptoms that look like protocol bugs, so it
 * is written out once here rather than inferred at each call site:
 *
 * <pre>
 *   inbound   socket -> cipher-decoder -> frame-decoder -> compression-decoder
 *                    -> minecraft-decoder -> handler
 *   outbound  handler -> minecraft-encoder -> compression-encoder -> frame-encoder
 *                     -> cipher-encoder -> socket
 * </pre>
 *
 * The cipher handlers are absent until encryption is negotiated, and the compression
 * handlers until a threshold is agreed.
 */
public final class Pipeline {

    /** Diagnostic handler recording what closes a connection; present only when configured. */
    public static final String CLOSE_TRACER = "close-tracer";

    /** Optional flush batching; see RelayConfig#flushBatching. Absent unless configured. */
    public static final String FLUSH_CONSOLIDATION = "flush-consolidation";

    /**
     * Wire-byte counter. At the head, so it sees bytes as they are on the network --
     * compressed and encrypted -- rather than as Relay thinks of them.
     */
    public static final String TRAFFIC = "traffic";

    /** PROXY protocol codecs, present only when configured. They sit ahead of everything. */
    public static final String PROXY_PROTOCOL_DECODER = "proxy-protocol-decoder";
    public static final String PROXY_PROTOCOL_HANDLER = "proxy-protocol-handler";
    public static final String PROXY_PROTOCOL_ENCODER = "proxy-protocol-encoder";

    public static final String CIPHER_DECODER = "cipher-decoder";
    public static final String CIPHER_ENCODER = "cipher-encoder";
    public static final String FRAME_DECODER = "frame-decoder";
    public static final String FRAME_ENCODER = "frame-encoder";
    public static final String COMPRESSION_DECODER = "compression-decoder";
    public static final String COMPRESSION_ENCODER = "compression-encoder";
    public static final String MINECRAFT_DECODER = "minecraft-decoder";
    public static final String MINECRAFT_ENCODER = "minecraft-encoder";
    public static final String READ_TIMEOUT = "read-timeout";
    public static final String HANDLER = "handler";

    private Pipeline() {
    }
}
