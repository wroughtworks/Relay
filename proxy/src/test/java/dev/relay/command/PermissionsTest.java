package dev.relay.command;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PermissionsTest {

    private static final UUID UUID_A = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");
    private static final UUID UUID_B = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private final Permissions permissions = new Permissions(Map.of(
            "default", List.of("relay.command.server"),
            "kopec", List.of("relay.*"),
            UUID_A.toString(), List.of("relay.command.send"),
            "root", List.of("*")));

    @Test
    void defaultNodesApplyToEveryone() {
        assertTrue(permissions.has("anyone", UUID_B, "relay.command.server"));
        assertFalse(permissions.has("anyone", UUID_B, "relay.command.send"));
    }

    @Test
    void grantsMatchOnUsernameCaseInsensitively() {
        assertTrue(permissions.has("KoPeC", UUID_B, "relay.command.send"));
    }

    @Test
    void grantsAlsoMatchOnUuid() {
        assertTrue(permissions.has("someoneelse", UUID_A, "relay.command.send"));
        assertFalse(permissions.has("someoneelse", UUID_B, "relay.command.send"));
    }

    @Test
    void wildcardsMatchBySegmentPrefix() {
        assertTrue(permissions.has("kopec", UUID_B, "relay.command.anything"));
        assertTrue(permissions.has("kopec", UUID_B, "relay.dashboard.view"));
        // "relay.*" must not leak into a differently named namespace.
        assertFalse(permissions.has("kopec", UUID_B, "relayother.thing"));
    }

    @Test
    void bareStarGrantsEverything() {
        assertTrue(permissions.has("root", UUID_B, "anything.at.all"));
    }

    @Test
    void emptyConfigurationGrantsNothing() {
        Permissions empty = new Permissions(Map.of());
        assertFalse(empty.has("anyone", UUID_A, "relay.command.server"));
    }
}
