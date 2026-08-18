package dev.relay.command;

import dev.relay.config.ConfigLoader;
import dev.relay.proxy.RelayProxy;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Telling "no such command" apart from "not for you".
 *
 * <p>Both are the same answer to a caller deciding whether to pass a line on to a
 * backend, and chat-typed commands deliberately keep them indistinguishable so nobody
 * can map out what exists by watching which refusals differ.
 *
 * <p>A command a backend plugin registered is a different situation. It reached the proxy
 * because it is a real server command — already in the player's command tree, already
 * tab-completing — so its existence is not a secret, and calling it unknown sends the
 * player hunting for a typo that is not there.
 */
class CommandOutcomeTest {

    @Test
    void separatesAMissingCommandFromAForbiddenOne(@TempDir Path dir) throws IOException {
        RelayProxy proxy = proxyGranting(dir, "relay.command.glist");
        CommandManager commands = proxy.commands();

        Recorder allowed = new Recorder("allowed", List.of("relay.command.glist"));
        assertEquals(CommandManager.Outcome.HANDLED, commands.run(allowed, "glist"));
        assertFalse(allowed.messages.isEmpty(), "a handled command should have said something");

        // Registered, but not for this player.
        assertEquals(CommandManager.Outcome.NO_PERMISSION, commands.run(allowed, "send someone lobby"));

        // Not a proxy command at all.
        assertEquals(CommandManager.Outcome.NO_SUCH_COMMAND, commands.run(allowed, "definitelynotacommand"));
        assertEquals(CommandManager.Outcome.NO_SUCH_COMMAND, commands.run(allowed, "   "));

        proxy.shutdown();
    }

    /**
     * The collapsed form still collapses.
     *
     * <p>{@code dispatch} decides whether a line reaches the backend, and both failures
     * must let it through: a player without {@code /find} should get their backend's
     * {@code /find}, not a proxy refusal that swallows the command.
     */
    @Test
    void dispatchStillTreatsBothFailuresAsUnclaimed(@TempDir Path dir) throws IOException {
        RelayProxy proxy = proxyGranting(dir, "relay.command.glist");
        CommandManager commands = proxy.commands();
        Recorder source = new Recorder("someone", List.of("relay.command.glist"));

        assertTrue(commands.dispatch(source, "glist"));
        assertFalse(commands.dispatch(source, "send someone lobby"), "forbidden must fall through");
        assertFalse(commands.dispatch(source, "nosuchthing"), "unknown must fall through");

        proxy.shutdown();
    }

    /**
     * A proxy that is built but never started, since nothing here touches a socket.
     *
     * <p>The bind port therefore only has to parse. Zero would have been the honest way
     * to say "unused", but the config rejects it as out of range before the proxy is
     * ever constructed.
     */
    private static RelayProxy proxyGranting(Path dir, String node) throws IOException {
        Path path = dir.resolve("relay.toml");
        Files.writeString(path, """
                bind = "127.0.0.1:25599"
                forwarding-mode = "none"
                health.enabled = false

                [servers]
                lobby = "127.0.0.1:25566"

                [permissions]
                default = ["%s"]
                """.formatted(node));
        return new RelayProxy(ConfigLoader.load(path));
    }

    /** A source that records what it was told, standing in for a player. */
    private static final class Recorder implements CommandSource {

        private final String name;
        private final List<String> granted;
        private final List<Component> messages = new ArrayList<>();

        Recorder(String name, List<String> granted) {
            this.name = name;
            this.granted = granted;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public void sendMessage(Component message) {
            messages.add(message);
        }

        @Override
        public boolean hasPermission(String node) {
            return granted.contains(node);
        }
    }
}
