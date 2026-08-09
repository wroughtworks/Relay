package dev.relay.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;

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

                [servers]
                lobby = "127.0.0.1:25566"

                [forced-hosts]
                "a.example.com" = ["missing"]
                """);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> ConfigLoader.load(hostPath))
                .getMessage().contains("missing"));
    }

    @Test
    void rejectsAnOutOfRangeCompressionLevel(@TempDir Path dir) throws IOException {
        Path path = dir.resolve("relay.toml");
        Files.writeString(path, """
                bind = "0.0.0.0:25565"
                forwarding-mode = "none"
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
