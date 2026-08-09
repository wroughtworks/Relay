package dev.relay.auth;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class GameProfileTest {

    /**
     * Offline UUIDs must match what Bukkit, BungeeCord and Velocity derive, or a network
     * switching to Relay would hand every player a new identity and orphan their data.
     * The value is a name-based (version 3) UUID over {@code OfflinePlayer:<name>}.
     */
    @Test
    void offlineUuidMatchesTheEcosystemConvention() {
        assertEquals(UUID.nameUUIDFromBytes("OfflinePlayer:Notch".getBytes(StandardCharsets.UTF_8)),
                GameProfile.offline("Notch").uuid());
        assertEquals(3, GameProfile.offline("Notch").uuid().version(), "must be a name-based UUID");
    }

    @Test
    void offlineUuidIsStableAndNameSensitive() {
        assertEquals(GameProfile.offline("kopec").uuid(), GameProfile.offline("kopec").uuid());
        assertNotEquals(GameProfile.offline("kopec").uuid(), GameProfile.offline("Kopec").uuid());
    }

    @Test
    void offlineProfilesCarryNoTextures() {
        assertNull(GameProfile.offline("kopec").textures());
    }
}
