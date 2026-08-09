package dev.relay.session;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.relay.protocol.ComponentCodec;
import dev.relay.protocol.ProtocolVersion;
import dev.relay.proxy.ConnectedPlayer;
import dev.relay.proxy.RelayProxy;
import com.google.gson.JsonParser;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.util.List;
import java.util.UUID;

/** Builds the server-list ping response. */
public final class ServerPing {

    /** How many names to put in the hover tooltip. Vanilla shows about a dozen. */
    private static final int SAMPLE_LIMIT = 12;

    private ServerPing() {
    }

    /**
     * @param clientProtocol the version the client announced, which may be outside
     *                       Relay's supported range
     */
    public static String create(RelayProxy proxy, int clientProtocol) {
        JsonObject root = new JsonObject();

        JsonObject version = new JsonObject();
        version.addProperty("name", "Relay " + ProtocolVersion.supportedRange());
        // Echoing a supported client's own protocol keeps the vanilla list clean. For
        // anything outside the range, reporting Relay's newest makes the client render
        // its own "outdated client/server" hint rather than a bare connection failure.
        version.addProperty("protocol", ProtocolVersion.isSupported(clientProtocol)
                ? clientProtocol
                : ProtocolVersion.newest().id());
        root.add("version", version);

        JsonObject players = new JsonObject();
        players.addProperty("max", proxy.config().maxPlayers());
        players.addProperty("online", proxy.config().showOnlineCount() ? proxy.players().count() : 0);
        if (proxy.config().showOnlineCount()) {
            players.add("sample", sample(proxy.players().snapshot()));
        }
        root.add("players", players);

        Component motd = MiniMessage.miniMessage().deserialize(proxy.config().motd());
        root.add("description", JsonParser.parseString(ComponentCodec.toJson(motd)));

        return root.toString();
    }

    private static JsonArray sample(List<ConnectedPlayer> online) {
        JsonArray sample = new JsonArray();
        for (ConnectedPlayer player : online.subList(0, Math.min(online.size(), SAMPLE_LIMIT))) {
            JsonObject entry = new JsonObject();
            entry.addProperty("name", player.username());
            entry.addProperty("id", player.uuid().toString());
            sample.add(entry);
        }
        if (online.size() > SAMPLE_LIMIT) {
            JsonObject more = new JsonObject();
            more.addProperty("name", "§7... and " + (online.size() - SAMPLE_LIMIT) + " more");
            more.addProperty("id", UUID.nameUUIDFromBytes(new byte[0]).toString());
            sample.add(more);
        }
        return sample;
    }
}
