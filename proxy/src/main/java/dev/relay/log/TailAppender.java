package dev.relay.log;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.AppenderBase;

/**
 * Feeds {@link LogTail} from logback, so the dashboard can show Relay's own log.
 *
 * <p>Referenced from {@code logback.xml} and constructed by logback itself, which is why
 * it takes no arguments and talks to a static buffer.
 *
 * <h2>What it deliberately does not do</h2>
 * No formatting, no encoder, no pattern. The fields are handed over separately and the
 * page decides how to show them &mdash; which is what makes filtering by level and
 * searching by logger possible at all. An appender that flattened this to a string would
 * force the browser to parse it back out again, badly.
 *
 * <p>It also does no I/O. Everything it does is an array append and a walk of a
 * copy-on-write list, on the thread that logged, because a logging call that can block is
 * a logging call that can deadlock the event loop it was called from.
 */
public final class TailAppender extends AppenderBase<ILoggingEvent> {

    /**
     * How much of a logger name to keep.
     *
     * <p>{@code dev.relay.session.BackendConnector} is the useful part of a name and the
     * package prefix is not, so the last two segments are kept. The file appender keeps
     * more; this one is read in a column on a page.
     */
    private static String shorten(String logger) {
        if (logger == null || logger.isEmpty()) {
            return "";
        }
        int last = logger.lastIndexOf('.');
        if (last < 0) {
            return logger;
        }
        int previous = logger.lastIndexOf('.', last - 1);
        return logger.substring(previous < 0 ? 0 : previous + 1);
    }

    @Override
    protected void append(ILoggingEvent event) {
        String message = event.getFormattedMessage();

        // An exception's own text is appended rather than dropped or sent whole: "failed
        // to connect" with no cause is a line that wastes the reader's time, and a
        // forty-frame trace is a line that buries the next one. The full trace is in the
        // file, which is where you go once this has told you to.
        IThrowableProxy thrown = event.getThrowableProxy();
        if (thrown != null) {
            String first = ThrowableProxyUtil.asString(thrown).lines().findFirst().orElse("");
            message = message + " — " + first;
        }

        LogTail.add(new LogTail.Line(
                event.getTimeStamp(),
                event.getLevel().toString(),
                shorten(event.getLoggerName()),
                event.getThreadName(),
                LogTail.trim(message)));
    }
}
