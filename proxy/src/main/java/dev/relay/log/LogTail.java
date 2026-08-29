package dev.relay.log;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The last few hundred log lines, and anyone waiting for the next one.
 *
 * <p>Spec &sect;9.1 asks the dashboard to stream Relay's logs. A companion cannot read the
 * log file &mdash; it may be on another machine, and tailing a rolling file to reconstruct
 * something the process already has in memory is work done twice and wrong at the seams.
 * So lines are captured where they are emitted and pushed down the control channel with
 * everything else.
 *
 * <h2>Why this is static</h2>
 * Logback constructs its own appenders from {@code logback.xml}, before anything in this
 * project runs and with no way to hand them a reference. The buffer therefore has to be
 * reachable without one. {@link dev.relay.companion.CompanionColour} is static for the
 * same reason and sets the precedent.
 *
 * <p>The cost is that two proxies in one JVM would share a buffer. Tests start several,
 * and it does not matter: a shared tail of log lines is at worst confusing to read, and
 * nothing depends on it being correct.
 *
 * <h2>Recursion</h2>
 * A listener that logs would produce a line, which would notify the listener, which would
 * log. The guard below is a thread-local flag rather than a clever data structure because
 * the failure it prevents is a stack overflow inside logging, which is close to
 * undiagnosable: the log is where you would look, and it is the thing that is broken.
 */
public final class LogTail {

    /**
     * How many lines are kept for a client that arrives late.
     *
     * <p>Enough that a dashboard opened after something went wrong still shows what
     * happened, small enough to send in one message. Longer history belongs in the log
     * file, which already exists and already rotates.
     *
     * <p>At DEBUG under real traffic this is seconds rather than minutes. That is the
     * intended trade: the console is for watching, the file is for reading back.
     */
    private static final int CAPACITY = 1000;

    /** Trimmed hard: a stack trace belongs in the file, not in a page's memory. */
    private static final int MAX_MESSAGE = 2000;

    private static final Deque<Line> LINES = new ArrayDeque<>(CAPACITY);
    private static final List<Consumer<Line>> LISTENERS = new CopyOnWriteArrayList<>();
    private static final ThreadLocal<Boolean> PUBLISHING = ThreadLocal.withInitial(() -> false);

    private LogTail() {
    }

    /**
     * One line.
     *
     * @param at      epoch millis
     * @param level   ERROR, WARN, INFO, DEBUG or TRACE
     * @param logger  the short logger name, already abbreviated for display
     * @param thread  which thread emitted it, which is how a Netty problem is told from a
     *                scheduler one
     * @param message the formatted message, with any exception's own line appended
     */
    public record Line(long at, String level, String logger, String thread, String message) {
    }

    /** Called by the appender, on whichever thread logged. */
    public static void add(Line line) {
        synchronized (LINES) {
            if (LINES.size() == CAPACITY) {
                LINES.removeFirst();
            }
            LINES.addLast(line);
        }
        if (PUBLISHING.get()) {
            // Something inside a listener logged. Keep the line -- it is still worth
            // reading -- but do not deliver it, because delivering is what is logging.
            return;
        }
        PUBLISHING.set(true);
        try {
            for (Consumer<Line> listener : LISTENERS) {
                try {
                    listener.accept(line);
                } catch (RuntimeException ignored) {
                    // A broken listener must not stop the line reaching the others, and
                    // must certainly not propagate into the code that was only logging.
                }
            }
        } finally {
            PUBLISHING.set(false);
        }
    }

    public static void addListener(Consumer<Line> listener) {
        LISTENERS.add(listener);
    }

    public static void removeListener(Consumer<Line> listener) {
        LISTENERS.remove(listener);
    }

    /** @return everything held, oldest first */
    public static List<Line> recent() {
        synchronized (LINES) {
            return List.copyOf(LINES);
        }
    }

    /** Truncates a message to something a page can hold without ceremony. */
    public static String trim(String message) {
        if (message == null) {
            return "";
        }
        return message.length() <= MAX_MESSAGE
                ? message
                : message.substring(0, MAX_MESSAGE) + "… (" + message.length() + " chars)";
    }

    /** Test seam: drops history and listeners so one test cannot colour the next. */
    public static void reset() {
        synchronized (LINES) {
            LINES.clear();
        }
        LISTENERS.clear();
    }
}
