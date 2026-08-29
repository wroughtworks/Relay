package dev.relay.log;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The log tail, and the appender that fills it.
 *
 * <p>The recursion test is the one that matters. A listener on this buffer runs on the
 * thread that logged, and if it logs in turn it produces a line, which notifies it, which
 * logs. Nothing about that failure is easy to diagnose from the outside: it is a stack
 * overflow inside logging, so the log -- the first place anyone would look -- is the
 * thing that has stopped working.
 */
class LogTailTest {

    @BeforeEach
    @AfterEach
    void clean() {
        LogTail.reset();
    }

    @Test
    void linesAreKeptInOrderAndHandedOut() {
        LogTail.add(new LogTail.Line(1, "INFO", "a.B", "main", "first"));
        LogTail.add(new LogTail.Line(2, "WARN", "a.B", "main", "second"));

        List<LogTail.Line> lines = LogTail.recent();
        assertEquals(2, lines.size());
        assertEquals("first", lines.get(0).message(), "oldest first, so a console reads downwards");
        assertEquals("second", lines.get(1).message());
    }

    @Test
    void listenersSeeEachLineAsItArrives() {
        List<String> seen = new ArrayList<>();
        LogTail.addListener(line -> seen.add(line.message()));

        LogTail.add(new LogTail.Line(1, "INFO", "a.B", "main", "hello"));

        assertEquals(List.of("hello"), seen);
    }

    @Test
    void aRemovedListenerStopsHearing() {
        List<String> seen = new ArrayList<>();
        java.util.function.Consumer<LogTail.Line> listener = line -> seen.add(line.message());
        LogTail.addListener(listener);
        LogTail.removeListener(listener);

        LogTail.add(new LogTail.Line(1, "INFO", "a.B", "main", "hello"));

        assertTrue(seen.isEmpty(),
                "the buffer outlives any one proxy, so a listener left behind by a stopped "
                        + "one would write to a closed channel for the life of the JVM");
    }

    /**
     * A listener that logs must not be re-entered.
     *
     * <p>Without the guard this recurses until the stack runs out. With it, the line is
     * still recorded -- it is worth reading -- but not delivered, because delivering it is
     * what produced it.
     */
    @Test
    void aListenerThatLogsDoesNotRecurse() {
        AtomicInteger deliveries = new AtomicInteger();
        LogTail.addListener(line -> {
            deliveries.incrementAndGet();
            LogTail.add(new LogTail.Line(2, "DEBUG", "x.Y", "main", "publishing " + line.message()));
        });

        LogTail.add(new LogTail.Line(1, "INFO", "a.B", "main", "trigger"));

        assertEquals(1, deliveries.get(), "the listener was re-entered by its own logging");
        assertEquals(2, LogTail.recent().size(), "the line the listener logged was still kept");
    }

    /** One broken listener must not cost the others their line, or break the logging call. */
    @Test
    void aThrowingListenerIsContained() {
        List<String> seen = new ArrayList<>();
        LogTail.addListener(line -> { throw new IllegalStateException("no"); });
        LogTail.addListener(line -> seen.add(line.message()));

        LogTail.add(new LogTail.Line(1, "INFO", "a.B", "main", "hello"));

        assertEquals(List.of("hello"), seen);
    }

    @Test
    void theOldestLineIsDroppedRatherThanGrowingForever() {
        for (int i = 0; i < 1200; i++) {
            LogTail.add(new LogTail.Line(i, "INFO", "a.B", "main", "line " + i));
        }
        List<LogTail.Line> lines = LogTail.recent();
        assertEquals(1000, lines.size());
        assertEquals("line 200", lines.get(0).message(), "the window should have slid, not reset");
        assertEquals("line 1199", lines.get(lines.size() - 1).message());
    }

    @Test
    void aVeryLongMessageIsTrimmedWithItsRealLengthShown() {
        String huge = "x".repeat(5000);
        String trimmed = LogTail.trim(huge);
        assertTrue(trimmed.length() < huge.length());
        assertTrue(trimmed.contains("5000"), "the reader should know what was cut: " + trimmed);
    }

    // ------------------------------------------------------------------ appender

    /** Driven through logback itself, so the wiring in logback.xml is what is tested. */
    @Test
    void theAppenderCapturesLevelLoggerAndMessage() {
        TailAppender appender = new TailAppender();
        appender.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
        appender.start();

        LoggingEvent event = new LoggingEvent();
        event.setLevel(Level.WARN);
        event.setLoggerName("dev.relay.session.BackendConnector");
        event.setThreadName("relay-worker-1");
        event.setMessage("could not reach survival-01");
        event.setTimeStamp(1234);
        appender.doAppend(event);

        List<LogTail.Line> lines = LogTail.recent();
        assertEquals(1, lines.size());
        LogTail.Line line = lines.get(0);
        assertEquals("WARN", line.level());
        assertEquals("relay-worker-1", line.thread());
        assertEquals("could not reach survival-01", line.message());
        assertEquals("session.BackendConnector", line.logger(),
                "the package prefix is noise in a column; the last two segments are the name");
        assertFalse(line.at() == 0);
    }
}
