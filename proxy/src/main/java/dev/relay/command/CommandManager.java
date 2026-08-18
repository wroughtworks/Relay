package dev.relay.command;

import dev.relay.command.commands.FindCommand;
import dev.relay.command.commands.GlistCommand;
import dev.relay.command.commands.SendCommand;
import dev.relay.command.commands.ServerCommand;
import dev.relay.proxy.ConnectedPlayer;
import dev.relay.proxy.RelayProxy;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Registry and dispatcher for proxy-side commands. */
public final class CommandManager {

    private static final Logger LOG = LoggerFactory.getLogger(CommandManager.class);

    private final Map<String, Command> byName = new LinkedHashMap<>();
    private final Permissions permissions;

    public CommandManager(RelayProxy proxy) {
        this.permissions = new Permissions(proxy.config().permissions());
        register(new ServerCommand(proxy));
        register(new GlistCommand(proxy));
        register(new FindCommand(proxy));
        register(new SendCommand(proxy));
    }

    public void register(Command command) {
        byName.put(command.name().toLowerCase(Locale.ROOT), command);
        for (String alias : command.aliases()) {
            byName.put(alias.toLowerCase(Locale.ROOT), command);
        }
    }

    public Permissions permissions() {
        return permissions;
    }

    /** The distinct registered commands, with aliases collapsed. */
    public List<Command> commands() {
        return byName.values().stream().distinct().toList();
    }

    /** What became of a command line, for callers that need to tell the cases apart. */
    public enum Outcome {
        /** Relay ran it. */
        HANDLED,
        /** No proxy command by that name. */
        NO_SUCH_COMMAND,
        /** A proxy command, but not for this player. */
        NO_PERMISSION
    }

    /**
     * Runs {@code commandLine} if it names a proxy command.
     *
     * @param commandLine the command without its leading slash
     * @return {@code true} if Relay claimed it; {@code false} to let it reach the backend
     */
    public boolean dispatch(ConnectedPlayer player, String commandLine) {
        return run(player, commandLine) == Outcome.HANDLED;
    }

    public boolean dispatch(CommandSource source, String commandLine) {
        return run(source, commandLine) == Outcome.HANDLED;
    }

    public Outcome run(ConnectedPlayer player, String commandLine) {
        return run(new PlayerSource(player, permissions), commandLine);
    }

    /**
     * As {@link #dispatch}, but saying which of the two failures happened.
     *
     * <p>Both mean "not claimed" to a caller deciding whether to pass the line on to a
     * backend, which is why {@code dispatch} collapses them. A caller that has to explain
     * itself to the player needs them apart: told "unknown command" for something they
     * merely lack permission for, they will go looking for a typo that is not there.
     */
    public Outcome run(CommandSource source, String commandLine) {
        String trimmed = commandLine.strip();
        if (trimmed.isEmpty()) {
            return Outcome.NO_SUCH_COMMAND;
        }

        String[] parts = trimmed.split("\\s+");
        Command command = byName.get(parts[0].toLowerCase(Locale.ROOT));
        if (command == null) {
            return Outcome.NO_SUCH_COMMAND;
        }

        if (!source.hasPermission(command.permission())) {
            return Outcome.NO_PERMISSION;
        }

        List<String> args = List.of(parts).subList(1, parts.length);
        try {
            command.execute(source, args);
        } catch (RuntimeException e) {
            LOG.error("Command '/{}' from {} failed", trimmed, source.name(), e);
            source.sendMessage(Component.text("That command failed. Check the proxy log.",
                    NamedTextColor.RED));
        }
        return Outcome.HANDLED;
    }
}
