package dev.relay.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigLoaderTest {

    @Test
    void parsesIpv4AndIpv6Addresses() {
        assertEquals(InetSocketAddress.createUnresolved("0.0.0.0", 25565),
                ConfigLoader.parseAddress("0.0.0.0:25565", "bind"));
        assertEquals(InetSocketAddress.createUnresolved("mc.example.com", 25566),
                ConfigLoader.parseAddress("mc.example.com:25566", "bind"));
        // A bare IPv6 literal is full of colons, so the port separator has to be found
        // after the closing bracket rather than at the last colon.
        assertEquals(InetSocketAddress.createUnresolved("::1", 25565),
                ConfigLoader.parseAddress("[::1]:25565", "bind"));
        assertEquals(InetSocketAddress.createUnresolved("2001:db8::1", 25577),
                ConfigLoader.parseAddress("[2001:db8::1]:25577", "bind"));
    }

    @Test
    void rejectsMalformedAddresses() {
        assertThrows(IllegalArgumentException.class, () -> ConfigLoader.parseAddress("0.0.0.0", "bind"));
        assertThrows(IllegalArgumentException.class, () -> ConfigLoader.parseAddress("0.0.0.0:abc", "bind"));
        assertThrows(IllegalArgumentException.class, () -> ConfigLoader.parseAddress("0.0.0.0:0", "bind"));
        assertThrows(IllegalArgumentException.class, () -> ConfigLoader.parseAddress("0.0.0.0:70000", "bind"));
        assertThrows(IllegalArgumentException.class, () -> ConfigLoader.parseAddress("[::1:25565", "bind"));
    }

    @Test
    void writesAStarterConfigOnFirstRun(@TempDir Path dir) throws IOException {
        Path path = dir.resolve("relay.toml");
        RelayConfig config = ConfigLoader.load(path);

        assertTrue(Files.exists(path), "a default config should have been written");
        assertEquals(ForwardingMode.MODERN, config.forwardingMode());
        assertFalse(config.servers().isEmpty());

        // The shipped placeholder must never survive into a real file, or every install
        // would deploy with the same forwarding secret.
        String written = Files.readString(path);
        assertFalse(written.contains("CHANGE-ME"), "the placeholder secret should have been replaced");
        assertTrue(config.forwardingSecret().length >= 32);
    }

    @Test
    void generatesADistinctSecretPerInstall(@TempDir Path dir) throws IOException {
        RelayConfig first = ConfigLoader.load(dir.resolve("a/relay.toml"));
        RelayConfig second = ConfigLoader.load(dir.resolve("b/relay.toml"));
        assertFalse(java.util.Arrays.equals(first.forwardingSecret(), second.forwardingSecret()));
    }

    @Test
    void parsesAFullConfiguration(@TempDir Path dir) throws IOException {
        Path path = dir.resolve("relay.toml");
        Files.writeString(path, """
                bind = "0.0.0.0:25577"
                motd = "<red>Test"
                max-players = 42
                online-mode = false
                forwarding-mode = "legacy"
                health.enabled = false
                compression-threshold = 128
                intercept-commands = false
                try = ["survival", "lobby"]

                [servers]
                lobby = "127.0.0.1:25566"
                survival = "127.0.0.1:25567"

                [forced-hosts]
                "Survival.Example.com" = ["survival"]

                [permissions]
                default = ["relay.command.server"]
                "kopec" = ["relay.*"]

                [protocol.overrides]
                "play.clientbound.start_configuration.772" = 113
                """);

        RelayConfig config = ConfigLoader.load(path);

        assertEquals(25577, config.bind().getPort());
        assertEquals(42, config.maxPlayers());
        assertFalse(config.onlineMode());
        assertFalse(config.interceptCommands());
        assertEquals(ForwardingMode.LEGACY, config.forwardingMode());
        assertEquals(128, config.compressionThreshold());
        assertEquals(2, config.servers().size());
        assertEquals(25567, config.servers().get("survival").address().getPort());
        assertEquals(java.util.List.of("survival", "lobby"), config.tryOrder());

        // Declaration order drives the /server listing and the implicit fallback, so it
        // has to survive being copied into the immutable config.
        assertEquals(java.util.List.of("lobby", "survival"),
                java.util.List.copyOf(config.servers().keySet()));

        // Forced hosts are matched case-insensitively against the handshake hostname.
        assertEquals(java.util.List.of("survival"),
                config.initialCandidates("survival.example.com"));
        assertEquals(java.util.List.of("survival", "lobby"),
                config.initialCandidates("something.else.com"));

        assertEquals(java.util.List.of("relay.*"), config.permissions().get("kopec"));

        assertEquals(1, config.protocolOverrides().size());
        assertEquals(772, config.protocolOverrides().get(0).protocolVersion());
        assertEquals(113, config.protocolOverrides().get(0).packetId());
        assertEquals("start_configuration", config.protocolOverrides().get(0).packet());
    }

    /**
     * Windows editors and PowerShell's {@code Set-Content -Encoding utf8} both prepend a
     * byte order mark. Left in place it becomes part of the first key, and TOML rejects
     * it as an invalid bare key while the line looks perfectly correct on screen.
     */
    @Test
    void toleratesAByteOrderMark(@TempDir Path dir) throws IOException {
        Path path = dir.resolve("relay.toml");
        Files.writeString(path, "﻿" + """
                bind = "0.0.0.0:25599"
                forwarding-mode = "none"
                health.enabled = false

                [servers]
                lobby = "127.0.0.1:25566"
                """);

        RelayConfig config = ConfigLoader.load(path);
        assertEquals(25599, config.bind().getPort());
        assertEquals(path.toAbsolutePath(), config.sourcePath());
    }

    @Test
    void modernForwardingRequiresASecret(@TempDir Path dir) throws IOException {
        Path path = dir.resolve("relay.toml");
        Files.writeString(path, """
                bind = "0.0.0.0:25565"
                forwarding-mode = "modern"
                health.enabled = false

                [servers]
                lobby = "127.0.0.1:25566"
                """);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ConfigLoader.load(path));
        assertTrue(error.getMessage().contains("forwarding-secret"), error.getMessage());
    }

    @Test
    void legacyAndNoneForwardingNeedNoSecret(@TempDir Path dir) throws IOException {
        Path path = dir.resolve("relay.toml");
        Files.writeString(path, """
                bind = "0.0.0.0:25565"
                forwarding-mode = "none"
                health.enabled = false

                [servers]
                lobby = "127.0.0.1:25566"
                """);
        assertEquals(ForwardingMode.NONE, ConfigLoader.load(path).forwardingMode());
    }

    @Test
    void readsTheForwardingSecretFromAFile(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("forwarding.secret"), "  file-based-secret\n");
        Path path = dir.resolve("relay.toml");
        Files.writeString(path, """
                bind = "0.0.0.0:25565"
                forwarding-mode = "modern"
                health.enabled = false
                forwarding-secret-file = "forwarding.secret"

                [servers]
                lobby = "127.0.0.1:25566"
                """);
        assertEquals("file-based-secret", new String(ConfigLoader.load(path).forwardingSecret()));
    }

    @Test
    void rejectsAnEmptyBackendList(@TempDir Path dir) throws IOException {
        Path path = dir.resolve("relay.toml");
        Files.writeString(path, """
                bind = "0.0.0.0:25565"
                forwarding-mode = "none"
                health.enabled = false
                """);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ConfigLoader.load(path));
        assertTrue(error.getMessage().contains("[servers]"), error.getMessage());
    }

    @Test
    void rejectsFallbackAndForcedHostsNamingUnknownBackends(@TempDir Path dir) throws IOException {
        Path tryPath = dir.resolve("try.toml");
        Files.writeString(tryPath, """
                bind = "0.0.0.0:25565"
                forwarding-mode = "none"
                health.enabled = false
                try = ["nope"]

                [servers]
                lobby = "127.0.0.1:25566"
                """);
        // A "try" entry naming a backend that does not exist would otherwise strand
        // joining players at the first hop with no diagnosis.
        assertTrue(assertThrows(IllegalArgumentException.class, () -> ConfigLoader.load(tryPath))
                .getMessage().contains("nope"));

        Path hostPath = dir.resolve("host.toml");
        Files.writeString(hostPath, """
                bind = "0.0.0.0:25565"
                forwarding-mode = "none"
                health.enabled = false

                [servers]
                lobby = "127.0.0.1:25566"

                [forced-hosts]
                "a.example.com" = ["missing"]
                """);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> ConfigLoader.load(hostPath))
                .getMessage().contains("missing"));
    }

    @Test
    void parsesGroupsAndLetsThemBeUsedAsDestinations(@TempDir Path dir) throws IOException {
        Path path = dir.resolve("relay.toml");
        Files.writeString(path, """
                bind = "0.0.0.0:25565"
                forwarding-mode = "none"
                health.enabled = false
                balance = "round-robin"
                try = ["survival"]

                [servers]
                lobby = "127.0.0.1:25566"
                survival-01 = "127.0.0.1:25567"
                survival-02 = "127.0.0.1:25568"

                [groups]
                survival = ["survival-01", "survival-02"]

                [forced-hosts]
                "pvp.example.com" = ["survival"]
                """);

        RelayConfig config = ConfigLoader.load(path);
        assertEquals(List.of("survival-01", "survival-02"), config.groups().get("survival"));
        assertEquals(BalanceStrategy.ROUND_ROBIN, config.balance());
        // Both places that name a destination must accept a group, or a group is only
        // half a destination: reachable by command but not by the routing that puts
        // players somewhere in the first place.
        assertEquals(List.of("survival"), config.tryOrder());
        assertEquals(List.of("survival"), config.initialCandidates("pvp.example.com"));
    }

    /**
     * Backends keep the order they were written in.
     *
     * <p>Three things read that order — the {@code /server} listing, the startup log, and
     * the implicit fallback when no {@code try} list is given — and the last of those
     * decides where players land. The TOML parser's default map is a {@code HashMap},
     * which loses it silently: nothing fails, players simply arrive somewhere nobody
     * chose.
     *
     * <p>Enough names here that hash order cannot match declaration order by luck. With
     * the default parser this list comes back starting at {@code zeta}.
     */
    @Test
    void keepsBackendsInTheOrderTheyWereDeclared(@TempDir Path dir) throws IOException {
        Path path = dir.resolve("relay.toml");
        Files.writeString(path, """
                bind = "0.0.0.0:25565"
                forwarding-mode = "none"
                health.enabled = false

                [servers]
                lobby = "127.0.0.1:25566"
                survival-01 = "127.0.0.1:25567"
                survival-02 = "127.0.0.1:25568"
                pvp_1 = "127.0.0.1:25569"
                pvp_2 = "127.0.0.1:25570"
                zeta = "127.0.0.1:25571"
                """);

        RelayConfig config = ConfigLoader.load(path);
        assertEquals(List.of("lobby", "survival-01", "survival-02", "pvp_1", "pvp_2", "zeta"),
                List.copyOf(config.servers().keySet()));

        // With no "try" list, the first declared backend is where players go.
        assertEquals(List.of("lobby"), config.tryOrder());
    }

    /**
     * Numbered backends group themselves, with no {@code [groups]} block at all.
     *
     * <p>Numbering copies of a server is what people already do, so the common case
     * should cost no configuration. The alternative — listing every backend a second
     * time under {@code [groups]} — is a list that silently goes stale the next time
     * someone adds a server and forgets.
     */
    @Test
    void numberedBackendsGroupThemselves(@TempDir Path dir) throws IOException {
        Path path = dir.resolve("relay.toml");
        Files.writeString(path, """
                bind = "0.0.0.0:25565"
                forwarding-mode = "none"
                health.enabled = false

                [servers]
                lobby = "127.0.0.1:25566"
                survival-01 = "127.0.0.1:25567"
                survival-02 = "127.0.0.1:25568"
                pvp_1 = "127.0.0.1:25569"
                pvp_2 = "127.0.0.1:25570"
                """);

        RelayConfig config = ConfigLoader.load(path);
        assertEquals(List.of("survival-01", "survival-02"), config.groups().get("survival"));
        assertEquals(List.of("pvp_1", "pvp_2"), config.groups().get("pvp"),
                "an underscore separator is as much a numbering convention as a dash");
        assertFalse(config.groups().containsKey("lobby"), "an unnumbered backend is not a group");
    }

    /**
     * The rule stays narrow, because a group nobody asked for is worse than none.
     *
     * <p>Three ways it must not fire: a name that merely contains a separator, a name
     * whose base is already a real backend, and a base that an explicit group has
     * claimed. In every case something was stated outright and inference must not
     * override it.
     */
    @Test
    void derivedGroupsNeverOverrideSomethingStatedOutright(@TempDir Path dir) throws IOException {
        Path path = dir.resolve("relay.toml");
        Files.writeString(path, """
                bind = "0.0.0.0:25565"
                forwarding-mode = "none"
                health.enabled = false

                [servers]
                pvp-arena = "127.0.0.1:25566"
                survival = "127.0.0.1:25567"
                survival-01 = "127.0.0.1:25568"
                creative-01 = "127.0.0.1:25569"
                creative-02 = "127.0.0.1:25570"

                [groups]
                creative = ["creative-01"]
                """);

        RelayConfig config = ConfigLoader.load(path);

        // A dash is not a number. "pvp-arena" is one server with a hyphenated name.
        assertFalse(config.groups().containsKey("pvp"),
                "a separator alone should not make a group, or every hyphenated name becomes one");

        // A backend already owns the name "survival", and a group cannot shadow it --
        // that is the very collision the explicit path rejects outright.
        assertFalse(config.groups().containsKey("survival"));

        // Written down beats inferred: the operator deliberately left creative-02 out.
        assertEquals(List.of("creative-01"), config.groups().get("creative"));
    }

    @Test
    void rejectsGroupsThatWouldMakeANameAmbiguousOrEmpty(@TempDir Path dir) throws IOException {
        // A group sharing a backend's name: /server lobby could mean either, and which
        // one it meant would depend on lookup order rather than on anything written down.
        Path shadow = dir.resolve("shadow.toml");
        Files.writeString(shadow, """
                bind = "0.0.0.0:25565"
                forwarding-mode = "none"
                health.enabled = false

                [servers]
                lobby = "127.0.0.1:25566"

                [groups]
                lobby = ["lobby"]
                """);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> ConfigLoader.load(shadow))
                .getMessage().contains("same name"));

        // A member that does not exist would send players nowhere, but only once the
        // rotation reached it -- so it has to fail at startup, not at that moment.
        Path missing = dir.resolve("missing.toml");
        Files.writeString(missing, """
                bind = "0.0.0.0:25565"
                forwarding-mode = "none"
                health.enabled = false

                [servers]
                lobby = "127.0.0.1:25566"

                [groups]
                survival = ["survival-01"]
                """);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> ConfigLoader.load(missing))
                .getMessage().contains("survival-01"));

        Path empty = dir.resolve("empty.toml");
        Files.writeString(empty, """
                bind = "0.0.0.0:25565"
                forwarding-mode = "none"
                health.enabled = false

                [servers]
                lobby = "127.0.0.1:25566"

                [groups]
                survival = []
                """);
        assertThrows(IllegalArgumentException.class, () -> ConfigLoader.load(empty));
    }

    @Test
    void rejectsAnOutOfRangeCompressionLevel(@TempDir Path dir) throws IOException {
        Path path = dir.resolve("relay.toml");
        Files.writeString(path, """
                bind = "0.0.0.0:25565"
                forwarding-mode = "none"
                health.enabled = false
                compression-level = 12

                [servers]
                lobby = "127.0.0.1:25566"
                """);
        assertThrows(IllegalArgumentException.class, () -> ConfigLoader.load(path));
    }

    @Test
    void rejectsAMalformedProtocolOverrideKey(@TempDir Path dir) throws IOException {
        Path path = dir.resolve("relay.toml");
        Files.writeString(path, """
                bind = "0.0.0.0:25565"
                forwarding-mode = "none"
                health.enabled = false

                [servers]
                lobby = "127.0.0.1:25566"

                [protocol.overrides]
                "play.clientbound.start_configuration" = 113
                """);
        assertThrows(IllegalArgumentException.class, () -> ConfigLoader.load(path));
    }

    @Test
    void forwardingModeAcceptsFriendlyAliases() {
        assertEquals(ForwardingMode.MODERN, ForwardingMode.parse("velocity"));
        assertEquals(ForwardingMode.LEGACY, ForwardingMode.parse("BungeeCord"));
        assertEquals(ForwardingMode.NONE, ForwardingMode.parse("NONE"));
        assertThrows(IllegalArgumentException.class, () -> ForwardingMode.parse("bungie"));
    }
}
