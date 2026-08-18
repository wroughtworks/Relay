package dev.relay.api.backend;

import dev.relay.protocol.ProtocolUtils;
import dev.relay.protocol.StateRegistry;
import dev.relay.protocol.packet.PluginMessagePacket;
import dev.relay.proxy.ConnectedPlayer;
import dev.relay.proxy.RegisteredServer;
import dev.relay.proxy.RelayProxy;
import dev.relay.proxy.ServerConnection;
import dev.relay.session.BackendConnector;
import io.netty.buffer.ByteBuf;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;

import static dev.relay.api.backend.BackendApi.Sub;

/**
 * Handles plugin messages a backend addresses to the proxy.
 *
 * <p>Messages are recognised by peeking at raw frames rather than by registering a
 * decoder. That is deliberate: clientbound play packets are otherwise never parsed, and
 * registering one would mean a wrong packet id makes Relay misread ordinary backend
 * traffic and kill the connection. Peeking degrades instead &mdash; anything that does
 * not parse cleanly as a plugin message on an API channel is forwarded untouched, so the
 * worst case is that this feature quietly does nothing.
 */
public final class BackendApiHandler {

    private static final Logger LOG = LoggerFactory.getLogger(BackendApiHandler.class);

    private final RelayProxy proxy;
    private final ServerConnection server;

    public BackendApiHandler(RelayProxy proxy, ServerConnection server) {
        this.proxy = proxy;
        this.server = server;
    }

    /**
     * Consumes {@code frame} if it is an API message.
     *
     * @return {@code true} if the frame was handled and must not be relayed to the
     *         player; {@code false} to forward it unchanged
     */
    public boolean tryHandle(ByteBuf frame) {
        int pluginMessageId = StateRegistry.PLAY.clientbound
                .idOf(PluginMessagePacket.class, server.player().version());
        if (pluginMessageId < 0) {
            return false;
        }

        String channel;
        byte[] data;
        try {
            // A duplicate, so a frame that turns out not to be ours is forwarded with its
            // reader index untouched.
            ByteBuf peek = frame.duplicate();
            if (ProtocolUtils.readVarInt(peek) != pluginMessageId) {
                return false;
            }
            channel = ProtocolUtils.readString(peek, 256);
            if (!BackendApi.isApiChannel(channel)) {
                return false;
            }
            data = ProtocolUtils.readRemaining(peek);
        } catch (RuntimeException e) {
            // Not a plugin message after all. Forwarding it unchanged is always safe.
            return false;
        }

        try {
            handle(channel, data);
        } catch (IOException | RuntimeException e) {
            LOG.warn("Backend {} sent a malformed API message on {}: {}",
                    server.target().name(), channel, e.toString());
        }
        // Consumed either way: a client has no business seeing proxy control traffic.
        return true;
    }

    private void handle(String channel, byte[] data) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
        String subChannel = in.readUTF();
        ConnectedPlayer player = server.player();

        switch (subChannel) {
            case Sub.CONNECT -> connect(player, in.readUTF());
            case Sub.CONNECT_OTHER -> {
                String target = in.readUTF();
                proxy.players().byName(target).ifPresent(other -> {
                    try {
                        connect(other, in.readUTF());
                    } catch (IOException e) {
                        LOG.warn("Malformed ConnectOther from {}", server.target().name());
                    }
                });
            }
            case Sub.GET_SERVER -> reply(channel, out -> {
                out.writeUTF(Sub.GET_SERVER);
                out.writeUTF(server.target().name());
            });
            case Sub.GET_SERVERS -> reply(channel, out -> {
                StringJoiner names = new StringJoiner(", ");
                proxy.servers().forEach(s -> names.add(s.name()));
                out.writeUTF(Sub.GET_SERVERS);
                out.writeUTF(names.toString());
            });
            case Sub.PLAYER_COUNT -> {
                String target = in.readUTF();
                int count = target.equalsIgnoreCase("ALL")
                        ? proxy.players().count()
                        : proxy.server(target).map(RegisteredServer::playerCount).orElse(0);
                reply(channel, out -> {
                    out.writeUTF(Sub.PLAYER_COUNT);
                    out.writeUTF(target);
                    out.writeInt(count);
                });
            }
            case Sub.PLAYER_LIST -> {
                String target = in.readUTF();
                StringJoiner names = new StringJoiner(", ");
                if (target.equalsIgnoreCase("ALL")) {
                    proxy.players().snapshot().forEach(p -> names.add(p.username()));
                } else {
                    proxy.server(target).ifPresent(s -> s.players().forEach(p -> names.add(p.username())));
                }
                reply(channel, out -> {
                    out.writeUTF(Sub.PLAYER_LIST);
                    out.writeUTF(target);
                    out.writeUTF(names.toString());
                });
            }
            case Sub.MESSAGE, Sub.MESSAGE_RAW -> {
                String target = in.readUTF();
                String json = in.readUTF();
                Component message = dev.relay.protocol.ComponentCodec.fromJson(json);
                if (target.equalsIgnoreCase("ALL")) {
                    proxy.players().snapshot().forEach(p -> p.sendMessage(message));
                } else {
                    proxy.players().byName(target).ifPresent(p -> p.sendMessage(message));
                }
            }
            case Sub.KICK_PLAYER -> {
                String target = in.readUTF();
                String reason = in.readUTF();
                proxy.players().byName(target).ifPresent(p ->
                        p.disconnect(dev.relay.protocol.ComponentCodec.fromJson(reason)));
            }
            case Sub.IP -> reply(channel, out -> {
                out.writeUTF(Sub.IP);
                out.writeUTF(player.remoteIp());
                out.writeInt(player.remoteAddress() instanceof InetSocketAddress inet ? inet.getPort() : 0);
            });
            case Sub.UUID -> reply(channel, out -> {
                out.writeUTF(Sub.UUID);
                out.writeUTF(player.uuid().toString().replace("-", ""));
            });
            case Sub.UUID_OTHER -> {
                String target = in.readUTF();
                proxy.players().byName(target).ifPresent(other -> reply(channel, out -> {
                    out.writeUTF(Sub.UUID_OTHER);
                    out.writeUTF(other.username());
                    out.writeUTF(other.uuid().toString().replace("-", ""));
                }));
            }
            case Sub.FORWARD -> forward(channel, in.readUTF(), in);
            case Sub.FORWARD_TO_PLAYER -> {
                String target = in.readUTF();
                ConnectedPlayer other = proxy.players().byName(target).orElse(null);
                ServerConnection destination = other == null ? null : other.connectedServer();
                forwardTo(channel, destination == null ? List.of() : List.of(destination), in);
            }
            default -> LOG.debug("Backend {} used an unknown sub-channel '{}'",
                    server.target().name(), subChannel);
        }
    }

    private void connect(ConnectedPlayer player, String targetName) {
        Optional<RegisteredServer> target = proxy.server(targetName);
        if (target.isEmpty()) {
            LOG.warn("Backend {} asked to move {} to unknown server '{}'",
                    server.target().name(), player.username(), targetName);
            return;
        }
        new BackendConnector(proxy, player).connect(target.get()).whenComplete((result, error) -> {
            if (error != null || result == null || !result.successful()) {
                player.sendMessage(Component.text("Could not move you to " + targetName,
                        NamedTextColor.RED));
            }
        });
    }

    /**
     * Relays an opaque payload to every player on a named server, or on all of them.
     *
     * <p>This is how cross-server plugin messaging works: the payload is re-wrapped with
     * the sender's chosen sub-channel and delivered to a player on the far side, whose
     * server plugin picks it up.
     */
    private void forward(String channel, String targetName, DataInputStream in) throws IOException {
        List<ServerConnection> destinations = new ArrayList<>();
        if (targetName.equalsIgnoreCase("ALL")) {
            for (ConnectedPlayer player : proxy.players().snapshot()) {
                ServerConnection connection = player.connectedServer();
                // One player per server is enough; the plugin message reaches the server,
                // not the player.
                if (connection != null && connection != server
                        && destinations.stream().noneMatch(d -> d.target() == connection.target())) {
                    destinations.add(connection);
                }
            }
        } else {
            proxy.server(targetName).ifPresent(target -> {
                for (ConnectedPlayer player : target.players()) {
                    ServerConnection connection = player.connectedServer();
                    if (connection != null) {
                        destinations.add(connection);
                        return;
                    }
                }
            });
        }
        forwardTo(channel, destinations, in);
    }

    private void forwardTo(String channel, List<ServerConnection> destinations, DataInputStream in)
            throws IOException {
        String subChannel = in.readUTF();
        int length = in.readShort() & 0xFFFF;
        if (length > BackendApi.MAX_FORWARD_BYTES) {
            LOG.warn("Backend {} tried to forward {} bytes, over the {} byte limit",
                    server.target().name(), length, BackendApi.MAX_FORWARD_BYTES);
            return;
        }
        byte[] payload = new byte[length];
        in.readFully(payload);

        if (destinations.isEmpty()) {
            // Nobody is on the far side, so there is no connection to carry it. This is
            // the same limitation BungeeCord has, and worth saying out loud because it
            // looks like a dropped message.
            LOG.debug("Nothing to forward '{}' to: no player is on the target server", subChannel);
            return;
        }

        for (ServerConnection destination : destinations) {
            destination.connection().write(new PluginMessagePacket(channel,
                    encode(out -> {
                        out.writeUTF(subChannel);
                        out.writeShort(payload.length);
                        out.write(payload);
                    })));
        }
    }

    private void reply(String channel, Payload payload) {
        server.connection().write(new PluginMessagePacket(channel, encode(payload)));
    }

    private static byte[] encode(Payload payload) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            payload.write(out);
        } catch (IOException e) {
            // A ByteArrayOutputStream cannot fail; this is unreachable.
            throw new IllegalStateException("failed to encode an API reply", e);
        }
        return bytes.toByteArray();
    }

    @FunctionalInterface
    private interface Payload {
        void write(DataOutputStream out) throws IOException;
    }
}
