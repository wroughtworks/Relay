package dev.relay.session;

import dev.relay.protocol.ComponentCodec;
import dev.relay.protocol.ProtocolUtils;
import dev.relay.protocol.ProtocolVersion;
import dev.relay.protocol.StateRegistry;
import dev.relay.protocol.packet.play.PlayDisconnectPacket;
import dev.relay.proxy.ConnectedPlayer;
import dev.relay.proxy.RelayProxy;
import dev.relay.proxy.ServerConnection;
import io.netty.buffer.ByteBuf;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Claims a backend's own kick rather than letting it reach the player.
 *
 * <p>Without this, moving players off a dying backend covers crashes but not the case
 * that actually happens: a planned restart. Paper's shutdown kicks everyone first and
 * closes afterwards, and a kick relayed verbatim takes the client back to the multiplayer
 * menu before Relay has any chance to move them. The close that follows then arrives to
 * find nobody left to rescue.
 *
 * <p>This is the one place Relay reads a clientbound play packet, and the registry is
 * deliberately arranged so it does not have to: that direction is registered write-only
 * so the bulk of traffic has no id dependency. The exception is bought carefully rather
 * than by making the packet decodable.
 *
 * <ul>
 *   <li>The id is <b>peeked</b> at, never decoded through the registry, so a wrong id
 *       cannot make the codec throw and kill a connection.</li>
 *   <li>A frame carrying the right id is still rejected unless its body can be a text
 *       component, which almost no ordinary packet will satisfy by accident.</li>
 *   <li>Rejection means the frame is relayed exactly as before, so the worst outcome of
 *       a wrong id is the old behaviour plus a warning naming the fix.</li>
 * </ul>
 *
 * <p>The reason itself is kept as raw bytes and handed on unchanged if the player ends up
 * with nowhere to go. From 1.20.3 a component is network NBT, which Relay writes but has
 * no reader for &mdash; and a player kicked for being banned should still see why, not a
 * summary Relay invented.
 */
final class BackendKickHandler {

    private static final Logger LOG = LoggerFactory.getLogger(BackendKickHandler.class);

    /** The only NBT tags a component root can legally be. */
    private static final int TAG_STRING = 0x08;
    private static final int TAG_COMPOUND = 0x0A;

    private final RelayProxy proxy;
    private final ServerConnection server;
    private final ProtocolVersion version;
    private final int disconnectId;

    /** So a wrong id warns once rather than on every packet that happens to share it. */
    private boolean warned;

    BackendKickHandler(RelayProxy proxy, ServerConnection server) {
        this.proxy = proxy;
        this.server = server;
        this.version = server.player().version();
        this.disconnectId = StateRegistry.PLAY.clientbound.idOf(PlayDisconnectPacket.class, version);
    }

    /** @return {@code true} if this frame was a kick and has been acted on */
    boolean tryHandle(ByteBuf frame) {
        if (disconnectId < 0) {
            return false;
        }

        ByteBuf body = frame.duplicate();
        int id;
        try {
            id = ProtocolUtils.readVarInt(body);
        } catch (RuntimeException malformed) {
            return false;
        }
        if (id != disconnectId) {
            return false;
        }
        if (!couldBeComponent(body)) {
            if (!warned) {
                warned = true;
                LOG.warn("Frames from {} carry id 0x{}, which Relay believes is the play disconnect "
                                + "at protocol {}, but their contents are not a text component. The id is "
                                + "wrong for this version: these are being relayed untouched, so kicks from "
                                + "this backend will disconnect players instead of moving them. Correct it "
                                + "under [protocol.overrides] in relay.toml.",
                        server.target().name(), Integer.toHexString(id), version.id());
            }
            return false;
        }

        ConnectedPlayer player = server.player();
        Component reason = describe(body);
        LOG.info("{} kicked {} ({}); moving them rather than passing the kick on",
                server.target().name(), player.username(), BackendConnector.plain(reason));

        // Kept whole, including the id, so it can be written back to the player exactly
        // as the backend composed it. A byte array rather than the buffer: it outlives
        // this call, and a retained frame would be one more thing that can leak.
        byte[] verbatim = new byte[frame.readableBytes()];
        frame.getBytes(frame.readerIndex(), verbatim);

        // Detached before the close, so the close is not mistaken for a second loss.
        player.setConnectedServer(null);
        server.disconnect();
        new BackendConnector(proxy, player).fallbackAfterLoss(server.target(), reason, verbatim);
        return true;
    }

    /**
     * Whether {@code body} could hold a text component in this version's encoding.
     *
     * <p>The point is not to validate the component &mdash; it is to make a wrong packet
     * id fail closed. A frame that merely shares an id will almost never also parse as a
     * complete component with nothing left over.
     */
    private boolean couldBeComponent(ByteBuf body) {
        if (!body.isReadable()) {
            return false;
        }
        if (version.atLeast(ProtocolVersion.MINECRAFT_1_20_3)) {
            int tag = body.getUnsignedByte(body.readerIndex());
            return tag == TAG_STRING || tag == TAG_COMPOUND;
        }
        String json = readJson(body);
        return json != null;
    }

    /**
     * The backend's wording, where Relay can read it.
     *
     * <p>Only the JSON encoding is readable here. From 1.20.3 the reason is network NBT
     * and Relay has a writer but no reader, so the player is told which server dropped
     * them and the server's own words are preserved in the raw frame instead.
     */
    private Component describe(ByteBuf body) {
        Component prefix = Component.text("Kicked from " + server.target().name(), NamedTextColor.RED);
        if (version.atLeast(ProtocolVersion.MINECRAFT_1_20_3)) {
            return prefix.append(Component.text("."));
        }
        String json = readJson(body);
        if (json == null) {
            return prefix.append(Component.text("."));
        }
        try {
            return prefix.append(Component.text(": ")).append(ComponentCodec.fromJson(json));
        } catch (RuntimeException notAComponent) {
            return prefix.append(Component.text("."));
        }
    }

    /** The body as a JSON string, or {@code null} if it is not exactly one. */
    private static String readJson(ByteBuf body) {
        try {
            ByteBuf copy = body.duplicate();
            String json = ProtocolUtils.readString(copy);
            if (copy.isReadable() || json.isEmpty()) {
                return null;
            }
            char first = json.charAt(0);
            return first == '{' || first == '[' || first == '"' ? json : null;
        } catch (RuntimeException malformed) {
            return null;
        }
    }
}
