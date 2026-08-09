package dev.relay.auth;

import dev.relay.protocol.ProtocolUtils.GameProfileProperty;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/** An authenticated (or, offline, a derived) player identity. */
public record GameProfile(UUID uuid, String name, List<GameProfileProperty> properties) {

    public GameProfile {
        properties = List.copyOf(properties);
    }

    /**
     * The offline-mode identity for {@code name}.
     *
     * <p>The exact input string matters: {@code OfflinePlayer:<name>} hashed as MD5 is
     * what vanilla, Bukkit and every other proxy use. Deriving it differently would give
     * players new UUIDs and orphan their existing backend data.
     */
    public static GameProfile offline(String name) {
        UUID uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
        return new GameProfile(uuid, name, List.of());
    }

    /** The skin/cape property, if the session server supplied one. */
    public GameProfileProperty textures() {
        for (GameProfileProperty property : properties) {
            if (property.name().equals("textures")) {
                return property;
            }
        }
        return null;
    }
}
