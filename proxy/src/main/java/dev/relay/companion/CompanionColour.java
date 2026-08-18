package dev.relay.companion;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/**
 * Colours a supervised process's output so it is distinguishable at a glance.
 *
 * <p>Every companion's log lands in the proxy's own, interleaved with Relay's. Without a
 * visual difference, reading a startup sequence means checking the prefix on every line to
 * work out who is talking &mdash; and the lines that matter, a companion failing to start
 * or a backend going offline, are exactly the ones that get lost in that.
 *
 * <p>The colour comes from a companion's position in the config, not from its name.
 * Hashing the name was the first attempt and it was wrong in a way only a test caught:
 * {@code dashboard} and {@code discord} landed on the same slot, which is precisely the
 * pair this feature exists to tell apart. Position guarantees distinct colours for as many
 * companions as there are colours, and stays stable for as long as the config does.
 *
 * <p>Nothing here decides <em>whether</em> to colour: the escape is emitted only when the
 * MDC key is set, and only the console appender uses this converter, so the log file stays
 * free of escape codes.
 */
public abstract class CompanionColour extends ClassicConverter {

    /**
     * The MDC key {@link CompanionSupervisor} sets around a companion's output.
     *
     * <p>Holds the palette slot as a decimal string rather than the companion's name.
     * The name is already in the message; what the converter needs is the one thing the
     * message cannot carry.
     */
    static final String MDC_KEY = "companionColour";

    /**
     * The escape character, built from its code point.
     *
     * <p>Never written as a literal control byte. One in a source file is invisible in
     * review and survives right up until an editor, a merge tool or a copy-paste
     * helpfully removes it -- at which point the logs quietly print {@code [36m} at the
     * start of every line and nothing reports an error. This file had exactly that
     * happen to it once already.
     */
    private static final String ESC = String.valueOf((char) 27);

    /**
     * Foreground colours that stay readable on both dark and light terminals.
     *
     * <p>Deliberately excludes red and plain white: red reads as an error when it only
     * means "this came from the second companion", and white is indistinguishable from
     * the proxy's own uncoloured output.
     */
    private static final String[] PALETTE = {
            ESC + "[36m",   // cyan
            ESC + "[35m",   // magenta
            ESC + "[32m",   // green
            ESC + "[33m",   // yellow
            ESC + "[34m",   // blue
            ESC + "[96m",   // bright cyan
    };

    private static final String RESET = ESC + "[0m";

    /** Opens the colour for a companion line, and emits nothing for the proxy's own. */
    public static final class Start extends CompanionColour {
        @Override
        public String convert(ILoggingEvent event) {
            String slot = event.getMDCPropertyMap().get(MDC_KEY);
            if (slot == null) {
                return "";
            }
            try {
                return PALETTE[Integer.parseInt(slot) % PALETTE.length];
            } catch (NumberFormatException notASlot) {
                return "";
            }
        }
    }

    /** Closes it again, so the next line is not left tinted. */
    public static final class End extends CompanionColour {
        @Override
        public String convert(ILoggingEvent event) {
            return event.getMDCPropertyMap().containsKey(MDC_KEY) ? RESET : "";
        }
    }

    /** How many companions can be told apart before two share a colour. */
    static int paletteSize() {
        return PALETTE.length;
    }
}
