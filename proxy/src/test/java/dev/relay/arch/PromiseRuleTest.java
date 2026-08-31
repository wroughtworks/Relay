package dev.relay.arch;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An architecture rule, enforced against the bytecode rather than the source.
 *
 * <p>Nobody may attach a listener to a {@code ChannelPromise} they were handed without
 * asking whether it is void. See {@link PromiseUse} for the incident that motivates it:
 * the Paper plugin did exactly that and disconnected every player on every 1.21 backend.
 *
 * <h2>Why bytecode and not a grep</h2>
 * A text search over source would be shorter and would work today. It would also miss a
 * call written as {@code p.addListener(...)} where {@code p} came from a field, a helper,
 * or a superclass; it would trip over the word appearing in a comment, which it does in
 * three places in this project already; and it could not see a module it was not pointed
 * at. Reading the class files asks what the code <em>does</em>, which is the only version
 * of the question that stays true.
 *
 * <h2>Both modules</h2>
 * The proxy and the Paper plugin are built separately and the bug was in the plugin, so a
 * rule that only covered the proxy would have been a rule that could not have caught it.
 * {@code proxy/build.gradle.kts} makes this test depend on the plugin's classes so they
 * are always there to read.
 */
class PromiseRuleTest {

    /** Where each module's compiled classes land, relative to the repository root. */
    private static final List<String> MODULES = List.of("proxy", "paper-plugin");

    @TestFactory
    Stream<DynamicTest> nobodyAddsAListenerToAPromiseWithoutCheckingItIsNotVoid() {
        return MODULES.stream().map(module -> DynamicTest.dynamicTest(module, () -> {
            PromiseUse.Scan scan = PromiseUse.scan(classesOf(module));

            assertTrue(scan.classesRead() > 0,
                    "no classes were read from " + module + "; a rule that passes because "
                            + "it found nothing to check is not a rule");

            assertEquals(List.of(), scan.unguarded(), () -> """
                    %s attaches a listener to a ChannelPromise without asking isVoid().

                    Netty hands out *void promises*, which cannot carry a listener --
                    addListener on one throws IllegalStateException("void future"). Paper
                    sends play packets that way from 1.21, so this exact shape once threw
                    on the first packet after a join, reached exceptionCaught, and had
                    Paper disconnect every player on every 1.21 backend. It could not
                    happen on 1.20.2, where those promises are real, which is why it went
                    unnoticed.

                    Either guard it:

                        if (!promise.isVoid()) {
                            promise.addListener(...);
                        }

                    or do not attach one. A void promise reports its failure through
                    exceptionCaught instead, so a handler that already overrides that
                    loses nothing by skipping the listener.
                    """.formatted(scan.unguarded()));
        }));
    }

    /**
     * The rule has something to say about this codebase.
     *
     * <p>Guards the guard. If a refactor renamed {@code write} or moved the handlers, the
     * scan above would find no methods taking a promise and pass in perfect silence.
     */
    @Test
    void theScanActuallyFindsTheHandlersItIsMeantToCover() throws IOException {
        PromiseUse.Scan proxy = PromiseUse.scan(classesOf("proxy"));
        PromiseUse.Scan plugin = PromiseUse.scan(classesOf("paper-plugin"));

        assertTrue(proxy.methodsTakingPromise() >= 2,
                "expected at least CloseTracer and TrafficCounter to take a promise, found "
                        + proxy.methodsTakingPromise());
        assertTrue(plugin.methodsTakingPromise() >= 1,
                "expected ConnectionWatcher to take a promise, found "
                        + plugin.methodsTakingPromise());
    }

    /**
     * The checker reports the shape it is looking for, rather than never reporting.
     *
     * <p>Without this, every assertion above passes just as happily if the visitor is
     * broken and finds nothing at all. {@link Offender} is the bug compiled, so the rule
     * is proved against real bytecode rather than trusted.
     */
    @Test
    void theRuleWouldCatchTheBugItWasWrittenFor() throws IOException {
        Path fixture = testClasses().resolve("dev/relay/arch");
        assertTrue(Files.isDirectory(fixture), "the fixture was not compiled: " + fixture);

        PromiseUse.Scan scan = PromiseUse.scan(fixture);
        assertFalse(scan.unguarded().isEmpty(),
                "the checker found nothing wrong with a method that adds a listener to a "
                        + "promise it was handed and never asks isVoid; it is not checking");
        assertTrue(scan.unguarded().stream().anyMatch(f -> f.className().endsWith("Offender")),
                "expected Offender to be reported, got " + scan.unguarded());
    }

    /**
     * The repository root, whether Gradle ran this from a module or somebody ran it from
     * the top. Found by looking for the settings file rather than by counting parents.
     */
    private static Path repositoryRoot() {
        Path here = Path.of("").toAbsolutePath();
        return Files.exists(here.resolve("settings.gradle.kts")) ? here : here.getParent();
    }

    private static Path classesOf(String module) {
        return repositoryRoot().resolve(module).resolve("build/classes/java/main");
    }

    private static Path testClasses() {
        return repositoryRoot().resolve("proxy").resolve("build/classes/java/test");
    }
}
