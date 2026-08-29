package dev.relay.paper;

import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The watcher must never be the reason a connection ends.
 *
 * <p>It exists to explain disconnects, and for a while it caused them. Paper sends play
 * packets with a <em>void</em> promise from 1.21 onwards; the watcher attached a listener
 * to every write, and {@code addListener} on a void promise throws
 * {@code IllegalStateException: void future}. That reached
 * {@code Connection.exceptionCaught} and Paper hung up. Every player, every join, on every
 * 1.21 backend.
 *
 * <p>It could not be seen on 1.20.2, where those promises are real, so a proxy that passed
 * every test and worked in front of a live 1.20.2 server was breaking one packet after
 * login on anything newer. Finding it needed a 1.21 server, a fake client that could speak
 * to it, and someone to look at the backend's log rather than the proxy's.
 *
 * <p>Which is the argument for this file existing at all. The plugin had no tests, on the
 * reasoning that it does nothing a server does not already do &mdash; and the one thing it
 * does that a server does not is exactly what broke.
 */
class ConnectionWatcherTest {

    /** Collects what the watcher would have logged. */
    private static final class Recorder implements Reporter {
        final List<String> warnings = new ArrayList<>();
        final List<Throwable> causes = new ArrayList<>();

        @Override
        public void report(String message) {
            warnings.add(message);
        }

        @Override
        public void report(String message, Throwable cause) {
            warnings.add(message);
            causes.add(cause);
        }

        @Override
        public void detail(String message) {
        }
    }

    @Test
    void aWriteOnAVoidPromiseDoesNotThrow() {
        Recorder recorder = new Recorder();
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.pipeline().addFirst(new ConnectionWatcher(recorder, "Tester", false));

        // Exactly what Paper does for play packets from 1.21: a promise that cannot carry
        // a listener, because its whole purpose is to avoid allocating one.
        channel.writeAndFlush(Unpooled.copiedBuffer(new byte[]{1, 2, 3}), channel.voidPromise());

        assertTrue(channel.isActive(),
                "the watcher killed the connection it was supposed to be observing");
        assertEquals(List.of(), recorder.causes,
                () -> "the watcher reported its own failure as the connection's: "
                        + recorder.warnings);
        assertFalse(channel.outboundMessages().isEmpty(), "the packet never reached the wire");
    }

    /** The ordinary case still works, and still watches. */
    @Test
    void aWriteOnARealPromiseIsStillWatched() {
        Recorder recorder = new Recorder();
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.pipeline().addFirst(new ConnectionWatcher(recorder, "Tester", false));

        channel.writeAndFlush(Unpooled.copiedBuffer(new byte[]{1, 2, 3}));

        assertTrue(channel.isActive());
        assertFalse(channel.outboundMessages().isEmpty(), "the packet never reached the wire");
    }

    /**
     * A close from inside the server is named, which is the watcher's actual job.
     *
     * <p>Here to keep the fix above honest: it would be easy to make the void-promise test
     * pass by removing the watching altogether.
     */
    @Test
    void aServerSideCloseIsReportedWithAStackTrace() {
        Recorder recorder = new Recorder();
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.pipeline().addFirst(new ConnectionWatcher(recorder, "Tester", false));

        channel.close();

        assertEquals(1, recorder.warnings.size(), () -> "expected one report, got " + recorder.warnings);
        String warning = recorder.warnings.get(0);
        assertTrue(warning.contains("Tester"), warning);
        assertTrue(warning.contains("the SERVER is closing this connection"), warning);
        assertTrue(warning.contains("close requested here"),
                "the stack trace is the diagnostic; without it the report says nothing");
    }
}
