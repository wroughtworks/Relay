package dev.relay.api.backend;

import dev.relay.protocol.ProtocolUtils;
import dev.relay.protocol.StateRegistry;
import dev.relay.protocol.packet.PluginMessagePacket;
import dev.relay.health.BackendStats;
import dev.relay.proxy.ConnectedPlayer;
import dev.relay.proxy.RegisteredServer;
import dev.relay.proxy.RelayProxy;
import dev.relay.proxy.ServerConnection;
import dev.relay.proxy.ServerGroup;
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
            // Logged for every plugin message, not just ours: if a backend's request
            // never appears here, the clientbound plugin-message id is wrong and the
            // frame is being relayed to the player instead of being recognised.
            LOG.debug("Backend {} sent a plugin message on '{}'", server.target().name(), channel);
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
        LOG.debug("Backend {} requested '{}' on {}", server.target().name(), subChannel, channel);

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
            case Sub.GET_GROUPS -> reply(channel, out -> {
                StringJoiner names = new StringJoiner(", ");
                proxy.groups().forEach(g -> names.add(g.name()));
                out.writeUTF(Sub.GET_GROUPS);
                out.writeUTF(names.toString());
            });
            case Sub.PLAYER_COUNT -> {
                String target = in.readUTF();
                int count = target.equalsIgnoreCase("ALL")
                        ? proxy.players().count()
                        : proxy.group(target).map(ServerGroup::playerCount)
                                .or(() -> proxy.server(target).map(RegisteredServer::playerCount))
                                .orElse(0);
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
                    for (RegisteredServer source : membersOf(target)) {
                        source.players().forEach(p -> names.add(p.username()));
                    }
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
            case Sub.RUN_COMMAND -> runCommand(player, in.readUTF());
            case Sub.SERVER_STATS -> recordStats(in);
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

    /**
     * Runs a proxy command on behalf of the player who sent it.
     *
     * <p>Dispatched through the same {@code CommandManager} as a typed command, so the
     * permission nodes apply identically and a backend gains nothing by routing a command
     * this way rather than letting the player type it.
     */
    /**
     * Runs a command a backend forwarded, and says plainly why if it will not.
     *
     * <p>Chat-typed commands hide the difference between "no such command" and "not for
     * you", so a player cannot map out what exists by watching which refusals differ.
     * Here that reasoning does not apply and actively misleads: the command arrived
     * because a plugin registered it as a real server command, so it is already in the
     * player's command tree, tab-completes, and is visibly not a typo. Telling them it
     * is unknown sends them hunting for a spelling mistake instead of asking for the
     * permission they actually need.
     */
    private void runCommand(ConnectedPlayer player, String commandLine) {
        LOG.debug("{} ran '{}' via {}", player.username(), commandLine, server.target().name());
        String name = commandLine.strip().split("\\s+")[0];
        switch (proxy.commands().run(player, commandLine)) {
            case HANDLED -> {
            }
            case NO_PERMISSION -> {
                LOG.debug("{} lacks permission for /{}", player.username(), name);
                player.sendMessage(Component.text("You do not have permission to use /" + name,
                        NamedTextColor.RED));
            }
            case NO_SUCH_COMMAND -> player.sendMessage(Component.text("Unknown command: /" + name,
                    NamedTextColor.RED));
        }
    }

    /**
     * Stores a backend's self-report of its own load.
     *
     * <p>Attributed to the connection it arrived on rather than to any name in the
     * payload. A backend could otherwise report figures for a server it is not, and
     * the connection is the one thing about the sender that cannot be forged from
     * inside the message.
     *
     * <p>Fields are read in a fixed order and any that fail to arrive leave the rest
     * intact, because this is the one API message whose sender is a plugin that may be a
     * version behind the proxy. A newer plugin appending a field must not break an older
     * proxy, and an older plugin omitting one must not lose the fields it did send.
     */
    private void recordStats(DataInputStream in) throws IOException {
        double tps1 = in.readDouble();
        double tps5 = in.readDouble();
        double tps15 = in.readDouble();
        double mspt = in.readDouble();
        long usedMemory = in.readLong();
        long maxMemory = in.readLong();
        double cpu = in.readDouble();
        int players = in.readInt();
        long uptime = in.readLong();
        String version = in.readUTF();

        server.target().setStats(new BackendStats(tps1, tps5, tps15, mspt, usedMemory, maxMemory,
                cpu, players, uptime, version, System.currentTimeMillis()));
        LOG.debug("{} reports {} TPS, {}ms/tick, {} players", server.target().name(), tps1, mspt, players);
    }

    /** Every backend a name covers, so a group is addressable wherever one server is. */
    private List<RegisteredServer> membersOf(String name) {
        return proxy.group(name)
                .map(ServerGroup::members)
                .orElseGet(() -> proxy.server(name).map(List::of).orElse(List.of()));
    }

    private void connect(ConnectedPlayer player, String targetName) {
        // A group name works here too. Existing BungeeCord plugins send whatever the
        // operator configured, so making groups usable is a matter of resolving the
        // name the same way /server does rather than of any new sub-channel.
        Optional<RegisteredServer> target = proxy.select(targetName);
        if (target.isEmpty()) {
            LOG.warn("Backend {} asked to move {} to unknown server or group '{}'",
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
            // Every member when the name is a group: a payload addressed to "survival"
            // means all of it, since which member a given player is on is Relay's
            // business rather than the sending plugin's.
            for (RegisteredServer target : membersOf(targetName)) {
                for (ConnectedPlayer player : target.players()) {
                    ServerConnection connection = player.connectedServer();
                    if (connection != null) {
                        destinations.add(connection);
                        break;
                    }
                }
            }
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
        // The id used here is the serverbound one, since Relay is the client on this
        // connection. If a backend never sees a reply it asked for, this is the id to
        // doubt: the request arriving proves only the clientbound id is right.
        LOG.debug("Replying to {} on {} (serverbound plugin message id 0x{})",
                server.target().name(), channel,
                Integer.toHexString(StateRegistry.PLAY.serverbound
                        .idOf(PluginMessagePacket.class, server.player().version())));
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
