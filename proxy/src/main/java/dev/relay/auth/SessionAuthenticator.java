package dev.relay.auth;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.relay.protocol.ProtocolUtils.GameProfileProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Verifies an online-mode login against Mojang's session server. */
public final class SessionAuthenticator {

    private static final Logger LOG = LoggerFactory.getLogger(SessionAuthenticator.class);

    private static final String HAS_JOINED_URL =
            "https://sessionserver.mojang.com/session/minecraft/hasJoined?username=%s&serverId=%s";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    /**
     * Asks Mojang whether {@code username} really did just authenticate against
     * {@code serverHash}.
     *
     * <p>Resolves to {@code null} when Mojang answers 204, meaning the client failed to
     * prove it owns the account. Completes exceptionally on transport failure &mdash;
     * callers must distinguish those, because "Mojang is down" and "this player is
     * lying" warrant very different kick messages.
     */
    public CompletableFuture<GameProfile> hasJoined(String username, String serverHash) {
        String url = HAS_JOINED_URL.formatted(
                URLEncoder.encode(username, StandardCharsets.UTF_8),
                URLEncoder.encode(serverHash, StandardCharsets.UTF_8));

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json")
                .GET()
                .build();

        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() == 204) {
                        return null;
                    }
                    if (response.statusCode() != 200) {
                        // Include a snippet of the body: when something between here and
                        // Mojang intercepts the request -- a corporate proxy, an ISP
                        // filter, a CDN edge refusing traffic -- the reply is an HTML
                        // error page, and its text says far more than the status code.
                        throw new AuthenticationException("Session server returned HTTP "
                                + response.statusCode() + " for " + username + ". "
                                + describeUnexpectedBody(response.body()));
                    }
                    return parseProfile(response.body());
                });
    }

    private static GameProfile parseProfile(String body) {
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            UUID uuid = parseUndashedUuid(root.get("id").getAsString());
            String name = root.get("name").getAsString();

            List<GameProfileProperty> properties = new ArrayList<>();
            JsonArray array = root.getAsJsonArray("properties");
            if (array != null) {
                for (JsonElement element : array) {
                    JsonObject property = element.getAsJsonObject();
                    properties.add(new GameProfileProperty(
                            property.get("name").getAsString(),
                            property.get("value").getAsString(),
                            property.has("signature") ? property.get("signature").getAsString() : null));
                }
            }
            return new GameProfile(uuid, name, properties);
        } catch (RuntimeException e) {
            LOG.debug("Unparseable session server response: {}", body);
            throw new AuthenticationException("Session server returned an unparseable profile", e);
        }
    }

    /**
     * Summarises a response body that was not the expected JSON.
     *
     * <p>An HTML page here means the request never reached Mojang, so the message says
     * that rather than leaving an operator to wonder why authentication "failed".
     */
    private static String describeUnexpectedBody(String body) {
        if (body == null || body.isBlank()) {
            return "The response had no body.";
        }
        String trimmed = body.strip();
        String snippet = trimmed.length() > 300 ? trimmed.substring(0, 300) + "..." : trimmed;
        if (trimmed.startsWith("<")) {
            return "The response was an HTML page, not JSON, so something between this "
                    + "proxy and Mojang intercepted the request (a filtering proxy, a "
                    + "firewall, or a CDN edge refusing traffic). Body: " + snippet;
        }
        return "Body: " + snippet;
    }

    /** Mojang returns UUIDs without dashes, which {@link UUID#fromString} rejects. */
    private static UUID parseUndashedUuid(String value) {
        if (value.length() != 32) {
            throw new AuthenticationException("Expected a 32-character UUID, got '" + value + "'");
        }
        return new UUID(Long.parseUnsignedLong(value.substring(0, 16), 16),
                Long.parseUnsignedLong(value.substring(16), 16));
    }

    /** A session-server round trip that failed for reasons other than a rejected login. */
    public static final class AuthenticationException extends RuntimeException {

        public AuthenticationException(String message) {
            super(message);
        }

        public AuthenticationException(String message, Throwable cause) {
            super(message, cause);
        }

        /** True when the cause looks like a network problem rather than a bad login. */
        public static boolean isTransport(Throwable cause) {
            Throwable current = cause;
            while (current != null) {
                if (current instanceof IOException) {
                    return true;
                }
                current = current.getCause();
            }
            return false;
        }
    }
}
