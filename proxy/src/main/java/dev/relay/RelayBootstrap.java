package dev.relay;

import dev.relay.command.CommandSource;
import dev.relay.config.ConfigLoader;
import dev.relay.config.RelayConfig;
import dev.relay.proxy.RelayProxy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/** Entry point: load the config, start the proxy, then read commands from the console. */
public final class RelayBootstrap {

    private static final Logger LOG = LoggerFactory.getLogger(RelayBootstrap.class);

    private static final String DEFAULT_CONFIG = "relay.toml";

    private RelayBootstrap() {
    }

    public static void main(String[] args) {
        Path configPath = Path.of(configPathFrom(args));

        RelayConfig config;
        try {
            config = ConfigLoader.load(configPath);
        } catch (IOException e) {
            LOG.error("Could not read {}", configPath.toAbsolutePath(), e);
            System.exit(1);
            return;
        } catch (RuntimeException e) {
            // A config error is almost always a typo, and the message names the key.
            // A stack trace would bury it.
            LOG.error("Configuration error in {}: {}", configPath.toAbsolutePath(), e.getMessage());
            System.exit(1);
            return;
        }

        RelayProxy proxy = new RelayProxy(config);
        Runtime.getRuntime().addShutdownHook(new Thread(proxy::shutdown, "relay-shutdown"));

        try {
            proxy.start();
        } catch (Exception e) {
            LOG.error("Could not bind {}:{}", config.bind().getHostString(), config.bind().getPort(), e);
            System.exit(1);
            return;
        }

        readConsole(proxy);
    }

    private static String configPathFrom(String[] args) {
        for (int i = 0; i < args.length; i++) {
            if ((args[i].equals("--config") || args[i].equals("-c")) && i + 1 < args.length) {
                return args[i + 1];
            }
        }
        return DEFAULT_CONFIG;
    }

    /**
     * Reads commands from stdin on the main thread, and keeps the JVM alive.
     *
     * <p>Every Netty thread is a daemon, so this method returning ends the process. That
     * makes the end-of-stream case matter: under {@code docker run -d} stdin is closed at
     * startup, and treating EOF as "stop" would kill the proxy the instant it launched.
     * EOF means there is no console, not that anyone asked it to exit.
     */
    private static void readConsole(RelayProxy proxy) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String command = line.strip();
                if (command.isEmpty()) {
                    continue;
                }
                if (command.equals("stop") || command.equals("end")) {
                    proxy.shutdown();
                    return;
                }
                if (!proxy.commands().dispatch(CommandSource.console(), command)) {
                    LOG.info("Unknown command '{}'. Try: server, glist, find, send, stop", command);
                }
            }
            LOG.info("No console attached; running until terminated");
        } catch (IOException e) {
            LOG.debug("Console closed", e);
        }

        try {
            proxy.awaitShutdown();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            proxy.shutdown();
        }
    }
}
