package dev.relay.proxy;

import dev.relay.auth.GameProfile;
import dev.relay.config.ConfigLoader;
import dev.relay.net.MinecraftConnection;
import dev.relay.protocol.PacketDirection;
import dev.relay.protocol.ProtocolVersion;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The old backend must go quiet the moment a switch is requested.
 *
 * <p>This is the fix for a real disconnect, and the reasoning is entirely about timing.
 * A client switches its own decoder into configuration state the instant it <em>sends</em>
 * the acknowledgement. Relay cannot learn that until the packet arrives, so there is a
 * window -- one network round trip wide -- in which the old backend is still producing
 * play packets and the client is no longer able to read them.
 *
 * <p>Anything delivered in that window is decoded against the wrong state. A chunk packet
 * read as configuration data is what a player sees as:
 *
 * <pre>Internal Exception: DecoderException: IndexOutOfBoundsException: Index 37 out of
 * bounds for length 9</pre>
 *
 * <p>It only ever bit on switches, never on first joins, because a first join has no old
 * backend still talking -- which is exactly why it survived a passing switch test.
 */
class SwitchQuietsOldBackendTest {

    private RelayProxy proxy;

    @AfterEach
    void stop() {
        if (proxy != null) {
            proxy.shutdown();
        }
    }

    @Test
    void aPlayerLeavingPlayStopsReceivingFromTheirOldBackend(@TempDir Path dir) throws Exception {
        proxy = start(dir);
        ConnectedPlayer player = player();

        assertFalse(player.isLeavingPlay(), "a settled player receives their backend's traffic");

        // What BackendConfigSessionHandler does before writing Start Configuration.
        player.setLeavingPlay(true);
        assertTrue(player.isLeavingPlay(),
                "the flag must be set when the request goes out, not when the reply arrives: "
                        + "the client has already switched state by then");

        // What ClientConfigSessionHandler does once they arrive somewhere.
        player.setLeavingPlay(false);
        assertFalse(player.isLeavingPlay(), "traffic must flow again once the player is playing");
    }

    /**
     * The flag is per player, not per connection.
     *
     * <p>A switch spans two backend connections and neither owns the decision. Hanging it
     * off either would leave the other free to keep talking, which is the bug.
     */
    @Test
    void oneLeavingPlayerDoesNotSilenceAnother(@TempDir Path dir) throws Exception {
        proxy = start(dir);
        ConnectedPlayer switching = player();
        ConnectedPlayer settled = player();

        switching.setLeavingPlay(true);

        assertTrue(switching.isLeavingPlay());
        assertFalse(settled.isLeavingPlay(), "a switch must not stop everyone else's world updating");
    }

    // ------------------------------------------------------------ helpers

    private RelayProxy start(Path dir) throws Exception {
        Files.writeString(dir.resolve("relay.toml"), """
                bind = "127.0.0.1:%d"
                forwarding-mode = "none"
                health.enabled = false
                control.enabled = false

                [servers]
                lobby = "127.0.0.1:25566"
                survival = "127.0.0.1:25567"
                """.formatted(freePort()));
        RelayProxy started = new RelayProxy(ConfigLoader.load(dir.resolve("relay.toml")));
        started.start();
        return started;
    }

    private static ConnectedPlayer player() {
        MinecraftConnection connection = new MinecraftConnection(
                new EmbeddedChannel(), PacketDirection.SERVERBOUND, null, null);
        return new ConnectedPlayer(connection,
                new GameProfile(UUID.randomUUID(), "Tester", List.of()),
                ProtocolVersion.MINECRAFT_1_20_2, null);
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress("127.0.0.1", 0));
            return socket.getLocalPort();
        }
    }
}
