package dev.relay.dashboard;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * This process's one link to the proxy.
 *
 * <p>Newline-delimited JSON over a loopback socket, authenticated with a token the proxy
 * put in this process's environment when it started it. There is no HTTP here on purpose:
 * the reason the dashboard is a separate process at all is to keep Jetty out of the proxy,
 * and a channel back that needed its own web server would have undone that.
 *
 * <h2>Reconnecting</h2>
 * The proxy can restart underneath this process. Rather than exiting, the client retries
 * with a backoff, so a proxy restart shows as a dashboard that is briefly stale rather
 * than one an operator has to go and start again. The exception is {@code goodbye}, which
 * says the proxy is stopping on purpose &mdash; that is a reason to exit, not to retry.
 */
final class ControlClient implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(ControlClient.class);

    private static final long MIN_BACKOFF_MILLIS = 500;
    private static final long MAX_BACKOFF_MILLIS = 10_000;

    /** A query the proxy has not answered in this long is not going to be. */
    private static final long QUERY_TIMEOUT_MILLIS = 5000;

    private final String host;
    private final int port;
    private final String token;
    private final Consumer<JsonObject> onEvent;
    private final Runnable onGoodbye;

    private final AtomicInteger nextId = new AtomicInteger(1);
    private final Map<Integer, CompletableFuture<JsonElement>> pending = new ConcurrentHashMap<>();

    private volatile Socket socket;
    private volatile Writer out;
    private volatile boolean closed;
    private volatile boolean connected;

    ControlClient(String host, int port, String token, Consumer<JsonObject> onEvent, Runnable onGoodbye) {
        this.host = host;
        this.port = port;
        this.token = token;
        this.onEvent = onEvent;
        this.onGoodbye = onGoodbye;
    }

    boolean isConnected() {
        return connected;
    }

    /** Starts the connect-and-read loop on its own thread. */
    void start() {
        Thread thread = new Thread(this::run, "relay-control");
        thread.setDaemon(true);
        thread.start();
    }

    private void run() {
        long backoff = MIN_BACKOFF_MILLIS;
        while (!closed) {
            try {
                session();
                backoff = MIN_BACKOFF_MILLIS;
            } catch (IOException e) {
                if (closed) {
                    return;
                }
                LOG.warn("Control connection to {}:{} failed ({}); retrying in {}ms",
                        host, port, e.getMessage(), backoff);
            } finally {
                connected = false;
                // Anything still waiting will never be answered by a socket that has gone.
                pending.values().forEach(future ->
                        future.completeExceptionally(new IOException("control connection lost")));
                pending.clear();
            }
            try {
                Thread.sleep(backoff);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
            backoff = Math.min(backoff * 2, MAX_BACKOFF_MILLIS);
        }
    }

    private void session() throws IOException {
        try (Socket connection = new Socket()) {
            connection.connect(new InetSocketAddress(host, port), 5000);
            socket = connection;
            out = new OutputStreamWriter(connection.getOutputStream(), StandardCharsets.UTF_8);

            JsonObject hello = new JsonObject();
            hello.addProperty("type", "hello");
            hello.addProperty("token", token);
            send(hello);

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                handle(line);
            }
            // A clean end of stream without goodbye means the proxy went away abruptly.
            throw new IOException("the proxy closed the control connection");
        }
    }

    private void handle(String line) {
        JsonObject message;
        try {
            message = JsonParser.parseString(line).getAsJsonObject();
        } catch (RuntimeException notJson) {
            LOG.warn("Ignoring a control line that is not JSON");
            return;
        }

        switch (message.has("type") ? message.get("type").getAsString() : "") {
            case "welcome" -> {
                connected = true;
                LOG.info("Connected to Relay {} (control protocol {})",
                        message.get("relay").getAsString(), message.get("protocol").getAsInt());
            }
            case "result" -> {
                CompletableFuture<JsonElement> future = message.has("id")
                        ? pending.remove(message.get("id").getAsInt())
                        : null;
                if (future != null) {
                    future.complete(message.get("data"));
                }
            }
            case "event" -> onEvent.accept(message);
            case "goodbye" -> {
                LOG.info("Relay is shutting down; following it");
                onGoodbye.run();
            }
            default -> {
                // A newer proxy may push things this build has never heard of. Ignoring
                // them costs a feature; treating them as errors would cost the session.
            }
        }
    }

    /**
     * Asks the proxy for one of its collections.
     *
     * @return the answer, or a JSON null if the proxy is unreachable. Callers render a
     *         dashboard: an empty panel with a disconnected banner is a better answer
     *         than an exception page
     */
    JsonElement query(String what) {
        if (!connected) {
            return com.google.gson.JsonNull.INSTANCE;
        }
        int id = nextId.getAndIncrement();
        CompletableFuture<JsonElement> future = new CompletableFuture<>();
        pending.put(id, future);

        JsonObject request = new JsonObject();
        request.addProperty("type", "query");
        request.addProperty("id", id);
        request.addProperty("what", what);
        try {
            send(request);
            return future.get(QUERY_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (IOException | InterruptedException | ExecutionException | TimeoutException e) {
            pending.remove(id);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOG.debug("Query '{}' failed", what, e);
            return com.google.gson.JsonNull.INSTANCE;
        }
    }

    private synchronized void send(JsonObject message) throws IOException {
        Writer writer = out;
        if (writer == null) {
            throw new IOException("not connected");
        }
        writer.write(message.toString());
        writer.write('\n');
        writer.flush();
    }

    @Override
    public void close() {
        closed = true;
        Socket current = socket;
        if (current != null) {
            try {
                current.close();
            } catch (IOException ignored) {
                // Closing a socket that is already gone is not a problem worth reporting.
            }
        }
    }
}
