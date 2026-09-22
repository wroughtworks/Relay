package dev.relay.paper;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;

/**
 * The Bukkit half of a {@link PacketFactory}: a player to send through, and the proxy's
 * answer to "which server am I".
 *
 * <p>Small on purpose. Everything a packet layer can get wrong -- ids, fingerprints,
 * encoding, dispatch -- lives in {@link PacketFactory}, where a test can reach it. What is
 * left here is picking any online player and handing bytes to
 * {@link RelayApi#forward}, which is about as much as can be written without a server to
 * run it on.
 */
final class PluginCarrier implements PacketCarrier, PluginMessageListener {

    private final Plugin plugin;
    private final RelayApi api;

    private volatile String serverName;
    private volatile PacketFactory factory;
    /** So one unanswered question does not become one per packet sent. */
    private volatile boolean asked;

    PluginCarrier(Plugin plugin) {
        this.plugin = plugin;
        this.api = new RelayApi(plugin);
    }

    /** Registers the channels and starts feeding this factory what arrives for it. */
    void listenFor(PacketFactory factory) {
        this.factory = factory;
        plugin.getServer().getMessenger()
                .registerOutgoingPluginChannel(plugin, RelayApi.CHANNEL);
        plugin.getServer().getMessenger()
                .registerIncomingPluginChannel(plugin, RelayApi.CHANNEL, this);
        askWhoWeAre();
    }

    @Override
    public String serverName() {
        if (serverName == null) {
            askWhoWeAre();
        }
        return serverName;
    }

    @Override
    public boolean forward(String target, String subChannel, byte[] payload) {
        Player carrier = anyPlayer();
        if (carrier == null) {
            return false;
        }
        api.forward(carrier, target, subChannel, payload);
        return true;
    }

    @Override
    public boolean forwardToPlayer(String player, String subChannel, byte[] payload) {
        Player carrier = anyPlayer();
        if (carrier == null) {
            return false;
        }
        api.forwardToPlayer(carrier, player, subChannel, payload);
        return true;
    }

    /**
     * Picks up this factory's packets, and the proxy's answer about our own name.
     *
     * <p>Every plugin listening on the BungeeCord channel sees every message on it, so
     * most of what arrives here belongs to somebody else and is left alone.
     */
    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        PacketFactory target = factory;
        if (target == null || !RelayApi.CHANNEL.equals(channel)) {
            return;
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(message))) {
            String subChannel = in.readUTF();
            if ("GetServer".equals(subChannel)) {
                learnName(in.readUTF());
                return;
            }
            if (!target.subChannel().equals(subChannel)) {
                return;
            }
            byte[] payload = new byte[in.readShort() & 0xFFFF];
            in.readFully(payload);
            target.receive(payload);
        } catch (IOException notForUs) {
            // A message on a shared channel that does not parse as we expect is somebody
            // else's, in a shape we do not know. Reading it wrong is not an error of ours.
            plugin.getLogger().fine("Ignored an unreadable message on " + channel);
        }
    }

    /**
     * Learns this server's name and releases anything that was waiting for it.
     *
     * <p>The answer is also the signal that a player is online, which is the other thing
     * sending needs -- so the queue is flushed here rather than on a join event.
     */
    private void learnName(String name) {
        if (name == null || name.isBlank() || name.equals(serverName)) {
            return;
        }
        serverName = name;
        PacketFactory target = factory;
        if (target != null) {
            target.flushHeld();
        }
    }

    private void askWhoWeAre() {
        Player carrier = anyPlayer();
        if (carrier == null) {
            // Nobody to ask through. The next attempt will find one, and until then
            // there is nothing to send anyway.
            asked = false;
            return;
        }
        if (asked) {
            return;
        }
        asked = true;
        api.requestServerName(carrier);
    }

    private Player anyPlayer() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            return player;
        }
        return null;
    }
}
