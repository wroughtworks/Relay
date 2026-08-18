package dev.relay.paper;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * A typed wrapper around the proxy's plugin-message API, for use from a backend plugin.
 *
 * <p>The wire format is the one BungeeCord established: a {@code DataOutputStream} of
 * {@code writeUTF} strings and big-endian primitives, sent as a plugin message through
 * any online player. Relay intercepts it on the way past. Writing that by hand is
 * error-prone and easy to get subtly wrong, so this exists to make the common operations
 * one call.
 *
 * <p>Because the format is BungeeCord's, a plugin already written against BungeeCord or
 * Velocity works against Relay unchanged &mdash; this class is a convenience, not a
 * requirement.
 *
 * <h2>Registering</h2>
 * A plugin must register the channels before using them:
 * <pre>{@code
 * getServer().getMessenger().registerOutgoingPluginChannel(this, RelayApi.CHANNEL);
 * getServer().getMessenger().registerIncomingPluginChannel(this, RelayApi.CHANNEL, listener);
 * }</pre>
 *
 * <h2>Replies</h2>
 * Requests that return data do so asynchronously, as a plugin message on the same
 * channel. Register a {@link PluginMessageListener} and read the sub-channel name first
 * to tell replies apart.
 */
public final class RelayApi {

    /**
     * The channel to register with Bukkit.
     *
     * <p>Deliberately the legacy name. Bukkit rewrites {@code bungeecord:main} to
     * {@code BungeeCord} when it records what a connection has registered, but does not
     * rewrite the argument to {@code sendPluginMessage} before checking that set --- so
     * passing the modern name means the lookup misses and the message is dropped with no
     * error at all. Every BungeeCord plugin uses this string for the same reason.
     *
     * <p>On the wire it still travels as {@code bungeecord:main}; Bukkit converts it
     * back on the way out.
     */
    public static final String CHANNEL = "BungeeCord";

    /** Relay's own channel, for behaviour BungeeCord never had. */
    public static final String RELAY_CHANNEL = "relay:main";

    private final Plugin plugin;

    public RelayApi(Plugin plugin) {
        this.plugin = plugin;
    }

    /** Moves {@code player} to another backend. */
    public void connect(Player player, String server) {
        send(player, out -> {
            out.writeUTF("Connect");
            out.writeUTF(server);
        });
    }

    /** Moves a player who may be on any backend, named rather than referenced. */
    public void connectOther(Player carrier, String playerName, String server) {
        send(carrier, out -> {
            out.writeUTF("ConnectOther");
            out.writeUTF(playerName);
            out.writeUTF(server);
        });
    }

    /** Asks which backend this server is, as the proxy knows it. Replies on {@code GetServer}. */
    public void requestServerName(Player carrier) {
        send(carrier, out -> out.writeUTF("GetServer"));
    }

    /** Asks for every configured backend. Replies on {@code GetServers}, comma separated. */
    public void requestServers(Player carrier) {
        send(carrier, out -> out.writeUTF("GetServers"));
    }

    /**
     * Asks for every group name.
     *
     * <p>Relay's own request. A group is what a player normally types, so anything
     * offering destinations wants these alongside the backends from
     * {@link #requestServers}.
     */
    public void requestGroups(Player carrier) {
        send(carrier, out -> out.writeUTF("GetGroups"));
    }

    /**
     * Asks how many players are on a backend.
     *
     * @param server a backend name, or {@code ALL} for the whole network
     */
    public void requestPlayerCount(Player carrier, String server) {
        send(carrier, out -> {
            out.writeUTF("PlayerCount");
            out.writeUTF(server);
        });
    }

    /** Asks who is on a backend, or on {@code ALL} of them. */
    public void requestPlayerList(Player carrier, String server) {
        send(carrier, out -> {
            out.writeUTF("PlayerList");
            out.writeUTF(server);
        });
    }

    /** Sends a chat message to a player anywhere on the network, or to {@code ALL}. */
    public void sendMessage(Player carrier, String playerName, String json) {
        send(carrier, out -> {
            out.writeUTF("Message");
            out.writeUTF(playerName);
            out.writeUTF(json);
        });
    }

    /** Disconnects a player anywhere on the network. */
    public void kick(Player carrier, String playerName, String reasonJson) {
        send(carrier, out -> {
            out.writeUTF("KickPlayer");
            out.writeUTF(playerName);
            out.writeUTF(reasonJson);
        });
    }

    /**
     * Runs a proxy command as {@code player}.
     *
     * <p>Sent on Relay's own channel rather than the BungeeCord one, since BungeeCord
     * never had this. The proxy applies the same permission nodes as it would to a typed
     * command, so this grants nothing the player could not do themselves.
     *
     * @param commandLine the command without its leading slash
     */
    public void runCommand(Player player, String commandLine) {
        sendOn(player, RELAY_CHANNEL, out -> {
            out.writeUTF("RunCommand");
            out.writeUTF(commandLine);
        });
    }

    /** Asks for the carrier's real address as the proxy sees it. Replies on {@code IP}. */
    public void requestAddress(Player carrier) {
        send(carrier, out -> out.writeUTF("IP"));
    }

    /**
     * Relays an opaque payload to another backend, or to {@code ALL} of them.
     *
     * <p>This is the cross-server messaging primitive: the receiving server gets a plugin
     * message on this channel whose first field is {@code subChannel}. It only reaches
     * servers that currently have a player on them, since a plugin message needs a player
     * connection to travel on &mdash; the same limitation BungeeCord has.
     */
    public void forward(Player carrier, String server, String subChannel, byte[] payload) {
        send(carrier, out -> {
            out.writeUTF("Forward");
            out.writeUTF(server);
            out.writeUTF(subChannel);
            out.writeShort(payload.length);
            out.write(payload);
        });
    }

    /** As {@link #forward}, but addressed to whichever backend a named player is on. */
    public void forwardToPlayer(Player carrier, String playerName, String subChannel, byte[] payload) {
        send(carrier, out -> {
            out.writeUTF("ForwardToPlayer");
            out.writeUTF(playerName);
            out.writeUTF(subChannel);
            out.writeShort(payload.length);
            out.write(payload);
        });
    }

    /**
     * Sends a request through {@code carrier}.
     *
     * <p>Any online player will do &mdash; the message travels on their connection but is
     * consumed by the proxy, never reaching their client.
     */
    private void send(Player carrier, Writer body) {
        sendOn(carrier, CHANNEL, body);
    }

    private void sendOn(Player carrier, String channel, Writer body) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            body.write(out);
        } catch (IOException e) {
            // A ByteArrayOutputStream cannot fail.
            throw new IllegalStateException("failed to encode a Relay API request", e);
        }
        carrier.sendPluginMessage(plugin, channel, bytes.toByteArray());
    }

    /**
     * Convenience for reading a reply body without repeating the stream plumbing.
     *
     * <p>The reader may throw {@link IOException}, because every {@code DataInputStream}
     * read does; a plain {@code Consumer} would force every caller to wrap each field.
     */
    public static void read(byte[] message, Reader reader) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(message))) {
            reader.read(in);
        } catch (IOException e) {
            throw new IllegalStateException("failed to read a Relay API reply", e);
        }
    }

    @FunctionalInterface
    public interface Reader {
        void read(DataInputStream in) throws IOException;
    }

    @FunctionalInterface
    private interface Writer {
        void write(DataOutputStream out) throws IOException;
    }
}
