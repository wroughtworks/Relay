package dev.relay.command;

import java.util.List;

/** A command Relay handles itself rather than passing to a backend. */
public interface Command {

    String name();

    default List<String> aliases() {
        return List.of();
    }

    /** The node a source needs to run this. Shared with dashboard RBAC &mdash; see {@link Permissions}. */
    String permission();

    /** Shown when the command is used incorrectly, e.g. {@code "/server <name>"}. */
    String usage();

    /**
     * @param args everything after the command name, already split on whitespace
     */
    void execute(CommandSource source, List<String> args);
}
