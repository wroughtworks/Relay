package dev.relay.companion;

import dev.relay.config.RelayConfig.CompanionEntry;
import dev.relay.control.ControlServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Runs the processes that are not the proxy.
 *
 * <p>The dashboard and, later, a Discord bot are separate programs with their own
 * dependency trees. Supervising them here is what keeps that from being a burden on the
 * operator: one command still starts everything, their output still lands in one log, and
 * a companion that dies comes back without anyone noticing.
 *
 * <h2>Stopping a child cleanly</h2>
 * This is the part worth being careful about, and this project has already been bitten by
 * it once: Windows has no signal to ask a process to stop, so {@code Process.destroy()}
 * there is a hard kill that skips shutdown hooks entirely. That is why {@code relay.py}
 * has to stop backends over RCON rather than by signalling them.
 *
 * <p>So the polite request does not go through the operating system at all. The proxy
 * says goodbye over the control channel, which every companion is already listening to,
 * and waits. Only a companion that ignores it gets destroyed, and only one that survives
 * that gets destroyed forcibly. The hard kill becomes the third option rather than the
 * only one.
 */
public final class CompanionSupervisor {

    private static final Logger LOG = LoggerFactory.getLogger(CompanionSupervisor.class);

    /** How long a companion gets to exit after being told the proxy is going. */
    private static final long GRACE_MILLIS = 5000;

    /** Restart backoff, so a companion that cannot start does not spin. */
    private static final long MIN_BACKOFF_MILLIS = 1000;
    private static final long MAX_BACKOFF_MILLIS = 60_000;

    private final List<CompanionEntry> configured;
    private final ControlServer control;
    private final Path workingDirectory;
    private final List<Companion> running = new ArrayList<>();

    private volatile boolean stopping;

    public CompanionSupervisor(List<CompanionEntry> configured, ControlServer control, Path workingDirectory) {
        this.configured = configured;
        this.control = control;
        this.workingDirectory = workingDirectory;
    }

    public void start() {
        for (CompanionEntry entry : configured) {
            if (!entry.enabled()) {
                LOG.debug("Companion '{}' is disabled", entry.name());
                continue;
            }
            Companion companion = new Companion(entry, running.size());
            running.add(companion);
            companion.launch();
        }
    }

    public void stop() {
        stopping = true;
        // Goodbye has already gone out over the control channel by the time this runs,
        // so the wait below is usually already satisfied.
        for (Companion companion : running) {
            companion.stop();
        }
        running.clear();
    }

    /** One supervised process, and the thread reading its output. */
    private final class Companion {

        private final CompanionEntry entry;
        /** Its slot in the console palette, so two companions never share a colour. */
        private final String colourSlot;
        private long backoff = MIN_BACKOFF_MILLIS;

        private volatile Process process;

        Companion(CompanionEntry entry, int index) {
            this.entry = entry;
            this.colourSlot = Integer.toString(index);
        }

        void launch() {
            ProcessBuilder builder = new ProcessBuilder(entry.command())
                    .directory(workingDirectory.toFile())
                    // Merged, because a companion's stack trace on stderr and the line
                    // that preceded it on stdout are the same story, and interleaving
                    // them in one log is the whole point of supervising.
                    .redirectErrorStream(true);

            Map<String, String> environment = builder.environment();
            environment.put("RELAY_CONTROL_HOST", "127.0.0.1");
            environment.put("RELAY_CONTROL_PORT", Integer.toString(control.port()));
            // Through the environment rather than the command line: arguments are visible
            // to every other process on the machine, and this token is what stands between
            // a local process and the player list.
            environment.put("RELAY_CONTROL_TOKEN", control.token());
            environment.put("RELAY_COMPANION_NAME", entry.name());
            environment.putAll(entry.environment());

            try {
                process = builder.start();
                LOG.info("Companion '{}' started (pid {})", entry.name(), process.pid());
            } catch (IOException e) {
                LOG.error("Companion '{}' could not be started: {}. The proxy continues without it.",
                        entry.name(), e.getMessage());
                return;
            }

            pipeOutput();
            watchForExit();
        }

        /** Reads the child's output onto the proxy log, prefixed so it is attributable. */
        private void pipeOutput() {
            Process current = process;
            Thread thread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(current.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        // Through the MDC rather than by colouring the message, so the
                        // console can tint the line while the log file records it clean.
                        // Escape codes written into the message would end up in the file
                        // too, where they are noise nothing strips.
                        MDC.put(CompanionColour.MDC_KEY, colourSlot);
                        try {
                            LOG.info("[{}] {}", entry.name(), line);
                        } finally {
                            MDC.remove(CompanionColour.MDC_KEY);
                        }
                    }
                } catch (IOException closed) {
                    // The process ended; the exit watcher handles it.
                }
            }, "relay-companion-" + entry.name());
            thread.setDaemon(true);
            thread.start();
        }

        private void watchForExit() {
            Process current = process;
            Thread thread = new Thread(() -> {
                try {
                    int code = current.waitFor();
                    if (stopping) {
                        LOG.debug("Companion '{}' exited with {} during shutdown", entry.name(), code);
                        return;
                    }
                    LOG.warn("Companion '{}' exited with code {}", entry.name(), code);
                    if (!entry.restart()) {
                        return;
                    }
                    // Backoff doubles per failure so a companion that cannot start --
                    // a missing jar, a port already taken -- does not fill the log at
                    // the speed the JVM can launch.
                    LOG.info("Restarting companion '{}' in {}ms", entry.name(), backoff);
                    Thread.sleep(backoff);
                    backoff = Math.min(backoff * 2, MAX_BACKOFF_MILLIS);
                    if (!stopping) {
                        launch();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "relay-companion-watch-" + entry.name());
            thread.setDaemon(true);
            thread.start();
        }

        void stop() {
            Process current = process;
            if (current == null || !current.isAlive()) {
                return;
            }
            try {
                // It has already been told over the control channel. This is the wait.
                if (current.waitFor(GRACE_MILLIS, TimeUnit.MILLISECONDS)) {
                    LOG.debug("Companion '{}' exited on its own", entry.name());
                    return;
                }
                LOG.warn("Companion '{}' did not exit within {}ms of being told to; ending it",
                        entry.name(), GRACE_MILLIS);
                current.destroy();
                if (!current.waitFor(2, TimeUnit.SECONDS)) {
                    LOG.warn("Companion '{}' ignored that too; killing it", entry.name());
                    current.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                current.destroyForcibly();
            }
        }
    }
}
