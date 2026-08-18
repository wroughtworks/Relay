package dev.relay.paper;

import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Exercises the proxy's backend API and reports what came back.
 *
 * <p>The API is asynchronous and fire-and-forget: a request that the proxy never
 * understood looks exactly like one it chose not to answer. This sends the read-only
 * requests, records which are outstanding, and after a short wait names any that went
 * unanswered &mdash; turning silence into a specific list.
 *
 * <p>Read-only by design. Nothing here moves a player or changes state, so it is safe to
 * run automatically whenever someone joins.
 */
final class BackendApiProbe implements PluginMessageListener {

    /** How long to wait before deciding a request went unanswered. */
    private static final long REPLY_TIMEOUT_TICKS = 60L;

    private final RelayPlugin plugin;
    private final RelayApi api;
    private final Set<String> outstanding = Collections.synchronizedSet(new LinkedHashSet<>());

    BackendApiProbe(RelayPlugin plugin) {
        this.plugin = plugin;
        this.api = new RelayApi(plugin);
    }

    void register() {
        plugin.getServer().getMessenger()
                .registerOutgoingPluginChannel(plugin, RelayApi.CHANNEL);
        plugin.getServer().getMessenger()
                .registerIncomingPluginChannel(plugin, RelayApi.CHANNEL, this);
    }

    /** Sends every read-only request, then reports which ones were answered. */
    void probe(Player carrier) {
        outstanding.clear();
        Collections.addAll(outstanding,
                "GetServer", "GetServers", "PlayerCount", "PlayerList", "IP", "UUID");

        plugin.report("--- backend API probe, as " + carrier.getName() + " ---");
        // Bukkit only transmits on channels the connection has registered, and silently
        // drops the message otherwise. Printing the set turns "no reply" into either
        // "the proxy never answered" or "the request never left this server".
        plugin.report("  channels this connection registered: "
                + carrier.getListeningPluginChannels());
        api.requestServerName(carrier);
        api.requestServers(carrier);
        api.requestPlayerCount(carrier, "ALL");
        api.requestPlayerList(carrier, "ALL");
        api.requestAddress(carrier);
        sendUuidRequest(carrier);

        plugin.getServer().getScheduler().runTaskLater(plugin, this::reportOutstanding,
                REPLY_TIMEOUT_TICKS);
    }

    /** {@code UUID} has no wrapper method, being a bare sub-channel with no arguments. */
    private void sendUuidRequest(Player carrier) {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (java.io.DataOutputStream out = new java.io.DataOutputStream(bytes)) {
            out.writeUTF("UUID");
        } catch (java.io.IOException e) {
            throw new IllegalStateException("cannot happen on a byte array", e);
        }
        carrier.sendPluginMessage(plugin, RelayApi.CHANNEL, bytes.toByteArray());
    }

    private void reportOutstanding() {
        if (outstanding.isEmpty()) {
            plugin.report("--- backend API probe: every request was answered ---");
            return;
        }
        synchronized (outstanding) {
            plugin.report("--- backend API probe: NO REPLY to " + String.join(", ", outstanding)
                    + ". Either the proxy is not Relay, backend-api is off in relay.toml, "
                    + "or those sub-channels are unimplemented. ---");
        }
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!channel.equals(RelayApi.CHANNEL)) {
            return;
        }
        RelayApi.read(message, in -> {
            String subChannel = in.readUTF();
            outstanding.remove(subChannel);
            switch (subChannel) {
                case "GetServer" -> plugin.report("  GetServer   -> this server is '"
                        + in.readUTF() + "' to the proxy");
                case "GetServers" -> plugin.report("  GetServers  -> " + in.readUTF());
                case "PlayerCount" -> {
                    String scope = in.readUTF();
                    plugin.report("  PlayerCount -> " + in.readInt() + " on " + scope);
                }
                case "PlayerList" -> {
                    String scope = in.readUTF();
                    plugin.report("  PlayerList  -> " + scope + ": " + in.readUTF());
                }
                case "IP" -> plugin.report("  IP          -> " + in.readUTF() + ":" + in.readInt());
                case "UUID" -> plugin.report("  UUID        -> " + in.readUTF());
                // Anything else is a payload another server forwarded to this one, which
                // is the cross-server messaging path rather than a reply.
                default -> {
                    int length = in.readShort() & 0xFFFF;
                    byte[] payload = new byte[length];
                    in.readFully(payload);
                    plugin.report("  forwarded '" + subChannel + "' -> "
                            + new String(payload, StandardCharsets.UTF_8));
                }
            }
        });
    }

    RelayApi api() {
        return api;
    }
}
