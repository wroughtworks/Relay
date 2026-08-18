package dev.relay.proxy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The few moments in a session that anything outside the proxy cares about.
 *
 * <p>Deliberately not the plugin event bus spec &sect;5.5 calls for. That one is phase 3
 * and has to carry cancellation, ordering and priorities so a plugin can change what
 * happens. Nothing here can change anything: these fire after the fact, tell watchers
 * what already happened, and are swallowed if a listener throws.
 *
 * <p>That difference is the whole reason this exists as its own small thing. A dashboard
 * showing live state needs to know when a player arrives, leaves or moves, and building
 * the full bus first &mdash; or letting the dashboard reach into session handlers &mdash;
 * would trade a week of work, or a tangle, for three method calls.
 *
 * <p>Listeners are called on a Netty event loop. Anything slow must hand off; blocking
 * here stalls the connection that triggered it.
 */
public final class ProxyEvents {

    private static final Logger LOG = LoggerFactory.getLogger(ProxyEvents.class);

    /** What happened. Named for the reader of a dashboard, not the writer of a packet. */
    public enum Kind {
        PLAYER_CONNECTED,
        PLAYER_DISCONNECTED,
        PLAYER_SWITCHED_SERVER
    }

    /**
     * @param from the server left behind, or {@code null} on a first join
     * @param to   the server arrived at, or {@code null} on a disconnect
     */
    public record PlayerEvent(Kind kind, ConnectedPlayer player, RegisteredServer from, RegisteredServer to) {
    }

    @FunctionalInterface
    public interface Listener {
        void onPlayerEvent(PlayerEvent event);
    }

    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public void playerConnected(ConnectedPlayer player, RegisteredServer to) {
        fire(new PlayerEvent(Kind.PLAYER_CONNECTED, player, null, to));
    }

    public void playerDisconnected(ConnectedPlayer player, RegisteredServer from) {
        fire(new PlayerEvent(Kind.PLAYER_DISCONNECTED, player, from, null));
    }

    public void playerSwitchedServer(ConnectedPlayer player, RegisteredServer from, RegisteredServer to) {
        fire(new PlayerEvent(Kind.PLAYER_SWITCHED_SERVER, player, from, to));
    }

    /**
     * Notifies every listener, and lets none of them break the session.
     *
     * <p>An observer that throws is a bug in the observer. Letting it propagate would
     * put the exception on a Netty pipeline belonging to a player who did nothing wrong,
     * and drop them because something was watching.
     */
    private void fire(PlayerEvent event) {
        if (listeners.isEmpty()) {
            return;
        }
        for (Listener listener : listeners) {
            try {
                listener.onPlayerEvent(event);
            } catch (RuntimeException e) {
                LOG.warn("A {} listener threw; the session is unaffected", event.kind(), e);
            }
        }
    }
}
