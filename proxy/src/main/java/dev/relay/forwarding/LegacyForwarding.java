package dev.relay.forwarding;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.relay.auth.GameProfile;
import dev.relay.protocol.ProtocolUtils.GameProfileProperty;

import java.util.UUID;

/**
 * BungeeCord-style forwarding: profile data smuggled into the handshake hostname,
 * NUL-separated.
 *
 * <p>There is no signature and no shared secret &mdash; anything that can open a socket
 * to the backend can assert any identity. It is here for backends that only speak this,
 * and it is safe only where the backend port is unreachable from outside. Prefer
 * {@link ModernForwarding}.
 */
public final class LegacyForwarding {

    private static final char SEPARATOR = '\0';

    private LegacyForwarding() {
    }

    /**
     * Builds the replacement handshake hostname.
     *
     * @param originalHost the host the player connected to, kept first so backends that
     *                     key off it (forced hosts, vhosts) still behave
     * @param playerAddress the player's IP, without a port
     */
    public static String createHostname(String originalHost, String playerAddress, GameProfile profile) {
        return originalHost
                + SEPARATOR + playerAddress
                + SEPARATOR + undashed(profile.uuid())
                + SEPARATOR + propertiesJson(profile);
    }

    private static String undashed(UUID uuid) {
        return uuid.toString().replace("-", "");
    }

    private static String propertiesJson(GameProfile profile) {
        JsonArray array = new JsonArray();
        for (GameProfileProperty property : profile.properties()) {
            JsonObject object = new JsonObject();
            object.addProperty("name", property.name());
            object.addProperty("value", property.value());
            if (property.signature() != null) {
                object.addProperty("signature", property.signature());
            }
            array.add(object);
        }
        return array.toString();
    }
}
