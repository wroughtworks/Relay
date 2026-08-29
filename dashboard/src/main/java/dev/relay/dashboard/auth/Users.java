package dev.relay.dashboard.auth;

import java.io.Console;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;

/**
 * Manages dashboard accounts from a terminal.
 *
 * <pre>
 * java -cp relay-dashboard.jar dev.relay.dashboard.auth.Users list
 * java -cp relay-dashboard.jar dev.relay.dashboard.auth.Users add carson admin
 * java -cp relay-dashboard.jar dev.relay.dashboard.auth.Users remove carson
 * </pre>
 *
 * <p>The password is read from the console, never from an argument. A password on a
 * command line is in the shell history, in the process list, and in whatever collects
 * either &mdash; and it would be in this project's own logs, since {@code relay.py} prints
 * the commands it runs.
 *
 * <p>{@code relay.py dashboard-user} wraps this, so nobody has to remember the classpath.
 */
public final class Users {

    private Users() {
    }

    public static void main(String[] args) throws IOException {
        Path file = Path.of(System.getenv().getOrDefault(
                "RELAY_DASHBOARD_USERS", "dashboard-users.json"));
        UserStore store = new UserStore(file);
        store.load();

        String command = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "list";
        switch (command) {
            case "list" -> list(store);
            case "add" -> add(store, args);
            case "remove", "rm", "delete" -> remove(store, args);
            default -> usage();
        }
    }

    private static void list(UserStore store) {
        if (store.isEmpty()) {
            System.out.println("No accounts in " + store.file());
            System.out.println();
            System.out.println("With none, the dashboard runs open and refuses to bind to");
            System.out.println("anything but loopback. Add one to serve it anywhere else.");
            return;
        }
        System.out.println(store.all().size() + " account(s) in " + store.file());
        for (UserStore.User user : store.all()) {
            System.out.printf("  %-20s %-10s %s%n", user.username(), user.role(),
                    PasswordHash.needsRehash(user.password())
                            ? "(hashed with older settings; re-add to strengthen)" : "");
        }
    }

    private static void add(UserStore store, String[] args) throws IOException {
        if (args.length < 2) {
            System.out.println("usage: add <username> [role]   roles: "
                    + String.join(", ", UserStore.ROLES.keySet()));
            return;
        }
        String username = args[1];
        String role = args.length > 2 ? args[2] : "admin";
        if (!UserStore.ROLES.containsKey(role.toLowerCase(Locale.ROOT))) {
            System.out.println("Unknown role '" + role + "'. Roles: "
                    + String.join(", ", UserStore.ROLES.keySet()));
            return;
        }

        Console console = System.console();
        if (console == null) {
            // Without a console the password would have to be read from a stream, which
            // means it can be piped, which means it ends up somewhere. Refusing is the
            // whole reason this reads from a terminal.
            System.out.println("No terminal available, and a password will not be read from a pipe.");
            System.out.println("Run this from an interactive shell.");
            return;
        }

        char[] password = console.readPassword("Password for %s: ", username);
        char[] again = console.readPassword("Again: ");
        try {
            if (password == null || password.length == 0) {
                System.out.println("Nothing entered; no change made.");
                return;
            }
            if (!Arrays.equals(password, again)) {
                System.out.println("Those do not match; no change made.");
                return;
            }
            if (password.length < 8) {
                // Advice, not a policy engine. A length floor catches the genuinely
                // careless; anything more elaborate mostly teaches people to append "1!".
                System.out.println("That is under 8 characters. Use a longer one.");
                return;
            }
            boolean replacing = store.find(username).isPresent();
            store.put(username, password, role);
            store.save();
            System.out.println((replacing ? "Updated " : "Added ") + PasswordHash.normalise(username)
                    + " as " + role + " in " + store.file());
            if (replacing) {
                System.out.println("Existing sessions for this account stay valid until the "
                        + "dashboard restarts.");
            }
        } finally {
            if (password != null) {
                Arrays.fill(password, '\0');
            }
            if (again != null) {
                Arrays.fill(again, '\0');
            }
        }
    }

    private static void remove(UserStore store, String[] args) throws IOException {
        if (args.length < 2) {
            System.out.println("usage: remove <username>");
            return;
        }
        if (store.remove(args[1])) {
            store.save();
            System.out.println("Removed " + PasswordHash.normalise(args[1]));
            if (store.isEmpty()) {
                System.out.println();
                System.out.println("That was the last account. The dashboard will now run open");
                System.out.println("on loopback, and refuse to bind anywhere else.");
            }
        } else {
            System.out.println("No account called " + args[1]);
        }
    }

    private static void usage() {
        System.out.println("""
                Dashboard accounts.

                  list                     show configured accounts
                  add <username> [role]    add or replace one, prompting for a password
                  remove <username>        delete one

                Roles: viewer (read), moderator (read, move players), admin (everything).
                The file is chosen by RELAY_DASHBOARD_USERS, default dashboard-users.json.""");
    }
}
