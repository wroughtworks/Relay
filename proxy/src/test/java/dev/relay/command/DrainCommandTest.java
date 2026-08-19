package dev.relay.command;

import dev.relay.command.commands.DrainCommand;
import dev.relay.config.BalanceStrategy;
import dev.relay.config.ConfigLoader;
import dev.relay.health.BackendHealth;
import dev.relay.auth.GameProfile;
import dev.relay.net.MinecraftConnection;
import dev.relay.protocol.PacketDirection;
import dev.relay.protocol.ProtocolVersion;
import dev.relay.proxy.ConnectedPlayer;
import dev.relay.proxy.RegisteredServer;
import dev.relay.proxy.RelayProxy;
import dev.relay.proxy.ServerGroup;
import io.netty.channel.embedded.EmbeddedChannel;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Taking a backend out of rotation before restarting it (spec 6.5).
 *
 * <p>The state and the routing that honours it already existed; what was missing was any
 * way to say so. These cover the two halves that matter: that saying so actually stops new
 * players arriving, and that it does not disturb the ones already there — a restart that
 * begins by ejecting everyone it was trying to protect has achieved nothing.
 */
class DrainCommandTest {

    private RelayProxy proxy;

    @AfterEach
    void stop() {
        if (proxy != null) {
            proxy.shutdown();
        }
    }

    @Test
    void drainingTakesABackendOutOfRotationWithoutMovingAnyone(@TempDir Path dir) throws Exception {
        proxy = start(dir);
        RegisteredServer target = proxy.server("survival-01").orElseThrow();
        Recorder source = new Recorder();

        assertTrue(target.acceptsNewPlayers(), "a fresh backend should be usable");
        proxy.commands().run(source, "drain survival-01");

        assertEquals(BackendHealth.State.DRAINING, target.health().state());
        assertFalse(target.acceptsNewPlayers(), "a draining backend must stop taking new players");
        assertTrue(source.saidSomethingContaining("draining"), source.said());

        // The group still resolves, but the drained member is last -- there is somewhere
        // to send people, which is the entire point of draining one server rather than
        // switching the whole group off.
        ServerGroup group = proxy.group("survival").orElseThrow();
        assertEquals("survival-02", group.ordered(BalanceStrategy.FIRST_AVAILABLE).get(0).name());
    }

    @Test
    void drainingCanBeLiftedAgain(@TempDir Path dir) throws Exception {
        proxy = start(dir);
        RegisteredServer target = proxy.server("survival-01").orElseThrow();
        Recorder source = new Recorder();

        proxy.commands().run(source, "drain survival-01");
        proxy.commands().run(source, "drain survival-01 off");

        // UNKNOWN rather than HEALTHY: the next check decides. Claiming health Relay has
        // not confirmed would be inventing it.
        assertEquals(BackendHealth.State.UNKNOWN, target.health().state());
        assertTrue(target.acceptsNewPlayers());
    }

    /**
     * A whole group cannot be drained in one command.
     *
     * <p>Draining every member at once leaves the network with nowhere to put anybody,
     * which is the opposite of what draining is for. Refusing with an explanation beats
     * quietly doing something catastrophic that reads like it worked.
     */
    @Test
    void aGroupCannotBeDrainedWholesale(@TempDir Path dir) throws Exception {
        proxy = start(dir);
        Recorder source = new Recorder();

        proxy.commands().run(source, "drain survival");

        assertTrue(proxy.server("survival-01").orElseThrow().acceptsNewPlayers());
        assertTrue(proxy.server("survival-02").orElseThrow().acceptsNewPlayers());
        assertTrue(source.saidSomethingContaining("individually"), source.said());
    }

    /** Someone who typed the name is owed the reason, not a silent redirect. */
    @Test
    void serverRefusesADrainingBackendByName(@TempDir Path dir) throws Exception {
        proxy = start(dir);
        RegisteredServer target = proxy.server("survival-01").orElseThrow();
        target.setHealth(target.health().withState(BackendHealth.State.DRAINING));

        Recorder source = new Recorder();
        proxy.commands().run(source, "server survival-01");

        assertTrue(source.saidSomethingContaining("not accepting players"), source.said());
        assertTrue(source.saidSomethingContaining("draining"), source.said());
    }

    /** The announcement is the moment an operator is waiting for, so it must not throw. */
    @Test
    void announcingADrainedBackendToleratesEveryState(@TempDir Path dir) throws Exception {
        proxy = start(dir);
        RegisteredServer target = proxy.server("survival-01").orElseThrow();

        DrainCommand.announceIfDrained(null);
        DrainCommand.announceIfDrained(target);
        target.setHealth(target.health().withState(BackendHealth.State.DRAINING));
        DrainCommand.announceIfDrained(target);
    }

    // ------------------------------------------------------------ helpers

    private RelayProxy start(Path dir) throws Exception {
        Files.writeString(dir.resolve("relay.toml"), """
                bind = "127.0.0.1:%d"
                forwarding-mode = "none"
                health.enabled = false
                control.enabled = false
                balance = "first-available"

                [servers]
                lobby = "127.0.0.1:25566"
                survival-01 = "127.0.0.1:25567"
                survival-02 = "127.0.0.1:25568"

                [permissions]
                default = ["relay.*"]
                """.formatted(freePort()));
        RelayProxy started = new RelayProxy(ConfigLoader.load(dir.resolve("relay.toml")));
        started.start();
        return started;
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress("127.0.0.1", 0));
            return socket.getLocalPort();
        }
    }

    /**
     * Captures what an operator would have been told, flattened to plain text.
     *
     * <p>Carries a player as well, because {@code /server} refuses a console outright --
     * switching servers only means anything for someone who is on one. Messages still
     * land here rather than on the player's connection, which is what makes them
     * readable: an EmbeddedChannel in handshake state silently drops chat.
     */
    private static final class Recorder implements CommandSource {

        private final List<String> messages = new ArrayList<>();
        private final ConnectedPlayer player = new ConnectedPlayer(
                new MinecraftConnection(new EmbeddedChannel(), PacketDirection.SERVERBOUND, null, null),
                new GameProfile(UUID.randomUUID(), "tester", List.of()),
                ProtocolVersion.MINECRAFT_1_20_2, null);

        @Override
        public String name() {
            return "tester";
        }

        @Override
        public ConnectedPlayer asPlayer() {
            return player;
        }

        @Override
        public void sendMessage(Component message) {
            // The legacy serializer, since that is what is on the classpath; the colour
            // codes it inserts are stripped so assertions match on words, not styling.
            messages.add(LegacyComponentSerializer.legacySection().serialize(message)
                    .replaceAll("§.", ""));
        }

        @Override
        public boolean hasPermission(String node) {
            return true;
        }

        boolean saidSomethingContaining(String text) {
            return messages.stream().anyMatch(line -> line.toLowerCase().contains(text.toLowerCase()));
        }

        String said() {
            return String.join(" | ", messages);
        }
    }
}
