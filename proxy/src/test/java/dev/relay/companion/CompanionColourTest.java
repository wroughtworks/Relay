package dev.relay.companion;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.classic.spi.ILoggingEvent;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tinting supervised output, without tinting anything else.
 *
 * <p>Worth testing because both failure modes are quiet. A converter that always emits
 * would colour Relay's own lines and write escape codes into the log file; one that never
 * emits would simply look like a feature nobody built. Neither shows up as an error.
 */
class CompanionColourTest {

    /** Built from the code point, so no invisible byte can go missing from this file. */
    private static final String ESC = String.valueOf((char) 27);

    @Test
    void emitsNothingForTheProxysOwnLines() {
        MDC.clear();
        assertEquals("", new CompanionColour.Start().convert(event()),
                "an uncoloured line must stay uncoloured, or the log file fills with escapes");
        assertEquals("", new CompanionColour.End().convert(event()));
    }

    @Test
    void wrapsACompanionLineInAnEscapeAndAReset() {
        MDC.put(CompanionColour.MDC_KEY, "0");
        try {
            String start = new CompanionColour.Start().convert(event());
            String end = new CompanionColour.End().convert(event());

            assertTrue(start.startsWith(ESC + "["), "expected an ANSI escape, got: " + escape(start));
            assertEquals(ESC + "[0m", end, "an unreset colour tints every line that follows");
        } finally {
            MDC.clear();
        }
    }

    /**
     * Companions in different slots get different colours.
     *
     * <p>The first attempt hashed the companion's name, and this test is why it does not:
     * "dashboard" and "discord" landed on the same slot, which is exactly the pair the
     * feature exists to tell apart. Position cannot collide until there are more
     * companions than colours.
     */
    @Test
    void neighbouringCompanionsNeverShareAColour() {
        Set<String> colours = new HashSet<>();
        for (int slot = 0; slot < CompanionColour.paletteSize(); slot++) {
            MDC.put(CompanionColour.MDC_KEY, Integer.toString(slot));
            try {
                colours.add(new CompanionColour.Start().convert(event()));
            } finally {
                MDC.clear();
            }
        }
        assertEquals(CompanionColour.paletteSize(), colours.size(),
                "every slot in the palette should be a distinct colour");
    }

    /** A slot beyond the palette wraps rather than throwing. */
    @Test
    void moreCompanionsThanColoursWrapsRoundInsteadOfFailing() {
        MDC.put(CompanionColour.MDC_KEY, Integer.toString(CompanionColour.paletteSize() + 1));
        try {
            assertTrue(new CompanionColour.Start().convert(event()).startsWith(ESC),
                    "the seventh companion should reuse a colour, not crash the logger");
        } finally {
            MDC.clear();
        }
    }

    /** Anything that is not a slot is treated as no colour at all. */
    @Test
    void nonsenseInTheMdcIsIgnored() {
        MDC.put(CompanionColour.MDC_KEY, "not-a-number");
        try {
            assertEquals("", new CompanionColour.Start().convert(event()),
                    "a logger must never throw because of what it was asked to print");
        } finally {
            MDC.clear();
        }
    }

    private static ILoggingEvent event() {
        LoggingEvent event = new LoggingEvent();
        event.setLevel(Level.INFO);
        event.setMessage("a line");
        event.setMDCPropertyMap(MDC.getCopyOfContextMap() == null ? java.util.Map.of() : MDC.getCopyOfContextMap());
        return event;
    }

    private static String escape(String text) {
        return text.replace(ESC, "\\u001B");
    }
}
