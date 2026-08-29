package dev.relay.paper;

/**
 * Where a connection watcher's findings go.
 *
 * <p>An interface rather than {@code RelayPlugin} directly, so the watcher can be driven
 * by a test without a running server. That mattered the moment it was written: the
 * watcher had a bug that disconnected every player on 1.21 backends, and it could not be
 * reproduced anywhere but a live 1.21 server because nothing else could construct one.
 */
interface Reporter {

    /** A problem worth a warning. */
    void report(String message);

    /** A problem worth a warning, with the exception that revealed it. */
    void report(String message, Throwable cause);

    /** Routine detail, logged only when packet logging is on. */
    void detail(String message);
}
