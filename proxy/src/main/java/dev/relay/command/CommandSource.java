package dev.relay.command;

import dev.relay.proxy.ConnectedPlayer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Whoever is running a command.
 *
 * <p>An interface rather than a concrete player so the same command implementations can
 * be driven from the console today and from the dashboard's REST API later, without
 * either growing its own copy of the logic.
 */
public interface CommandSource {

    String name();

    void sendMessage(Component message);

    boolean hasPermission(String node);

    /** The player behind this source, or {@code null} for the console. */
    default ConnectedPlayer asPlayer() {
        return null;
    }

    /** A source with every permission, for commands typed at the proxy console. */
    static CommandSource console() {
        return ConsoleSource.INSTANCE;
    }

    final class ConsoleSource implements CommandSource {

        private static final ConsoleSource INSTANCE = new ConsoleSource();
        private static final Logger LOG = LoggerFactory.getLogger("console");

        private ConsoleSource() {
        }

        @Override
        public String name() {
            return "CONSOLE";
        }

        @Override
        public void sendMessage(Component message) {
            LOG.info("{}", LegacyComponentSerializer.legacySection().serialize(message).replaceAll("§.", ""));
        }

        @Override
        public boolean hasPermission(String node) {
            return true;
        }
    }
}
