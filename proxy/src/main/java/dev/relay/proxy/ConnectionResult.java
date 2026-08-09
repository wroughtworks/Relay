package dev.relay.proxy;

import net.kyori.adventure.text.Component;

/**
 * How an attempt to reach a backend ended.
 *
 * <p>The three failure cases are kept apart because they call for different responses:
 * a refused connection means try the next backend in the fallback order, a backend kick
 * means show the player the backend's own reason, and an internal error means log it.
 */
public sealed interface ConnectionResult {

    RegisteredServer target();

    boolean successful();

    /** The player is now on {@link #target()}. */
    record Success(RegisteredServer target) implements ConnectionResult {
        @Override
        public boolean successful() {
            return true;
        }
    }

    /** The backend accepted the socket, then refused the login with a reason. */
    record Rejected(RegisteredServer target, Component reason) implements ConnectionResult {
        @Override
        public boolean successful() {
            return false;
        }
    }

    /** The backend could not be reached at all: refused, unreachable, or timed out. */
    record Unreachable(RegisteredServer target, Throwable cause) implements ConnectionResult {
        @Override
        public boolean successful() {
            return false;
        }
    }
}
