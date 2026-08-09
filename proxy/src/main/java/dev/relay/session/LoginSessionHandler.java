package dev.relay.session;

import dev.relay.auth.GameProfile;
import dev.relay.auth.SessionAuthenticator.AuthenticationException;
import dev.relay.net.Encryption;
import dev.relay.net.MinecraftConnection;
import dev.relay.net.SessionHandler;
import dev.relay.protocol.ProtocolState;
import dev.relay.protocol.packet.HandshakePacket;
import dev.relay.protocol.packet.login.EncryptionRequestPacket;
import dev.relay.protocol.packet.login.EncryptionResponsePacket;
import dev.relay.protocol.packet.login.LoginAcknowledgedPacket;
import dev.relay.protocol.packet.login.LoginDisconnectPacket;
import dev.relay.protocol.packet.login.LoginStartPacket;
import dev.relay.protocol.packet.login.LoginSuccessPacket;
import dev.relay.protocol.packet.login.SetCompressionPacket;
import dev.relay.proxy.ConnectedPlayer;
import dev.relay.proxy.RelayProxy;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.regex.Pattern;

/**
 * Authenticates a player and hands them to a backend.
 *
 * <p>Online mode runs the full vanilla exchange: Relay presents an RSA key, the client
 * proves ownership of its account against Mojang, and only then does a
 * {@link GameProfile} exist. Relay authenticates the player itself rather than letting
 * a backend do it &mdash; that is the whole point of forwarding, and it means backends
 * can run in offline mode behind the proxy.
 */
public final class LoginSessionHandler implements SessionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(LoginSessionHandler.class);

    /** Mojang's own constraint. Enforced here so an invalid name never reaches a backend. */
    private static final Pattern VALID_USERNAME = Pattern.compile("^[A-Za-z0-9_]{1,16}$");

    private static final int VERIFY_TOKEN_LENGTH = 4;

    private final RelayProxy proxy;
    private final MinecraftConnection connection;
    private final HandshakePacket handshake;
    private final SecureRandom random = new SecureRandom();

    private String username;
    private byte[] verifyToken;
    private GameProfile profile;
    private State state = State.AWAITING_LOGIN_START;

    private enum State {
        AWAITING_LOGIN_START,
        AWAITING_ENCRYPTION_RESPONSE,
        AUTHENTICATING,
        AWAITING_ACK
    }

    public LoginSessionHandler(RelayProxy proxy, MinecraftConnection connection, HandshakePacket handshake) {
        this.proxy = proxy;
        this.connection = connection;
        this.handshake = handshake;
    }

    @Override
    public boolean handle(LoginStartPacket packet) {
        if (state != State.AWAITING_LOGIN_START) {
            connection.close();
            return true;
        }
        this.username = packet.username();

        if (!VALID_USERNAME.matcher(username).matches()) {
            kick(Component.text("Invalid username", NamedTextColor.RED));
            return true;
        }
        if (proxy.players().count() >= proxy.config().maxPlayers()) {
            kick(Component.text("The proxy is full", NamedTextColor.RED));
            return true;
        }

        if (proxy.config().onlineMode()) {
            this.verifyToken = new byte[VERIFY_TOKEN_LENGTH];
            random.nextBytes(verifyToken);
            state = State.AWAITING_ENCRYPTION_RESPONSE;
            connection.write(new EncryptionRequestPacket(
                    "", proxy.keyPair().getPublic().getEncoded(), verifyToken, true));
        } else {
            completeLogin(GameProfile.offline(username));
        }
        return true;
    }

    @Override
    public boolean handle(EncryptionResponsePacket packet) {
        if (state != State.AWAITING_ENCRYPTION_RESPONSE) {
            connection.close();
            return true;
        }
        state = State.AUTHENTICATING;

        byte[] sharedSecret;
        try {
            byte[] decryptedToken = Encryption.decryptRsa(proxy.keyPair().getPrivate(), packet.verifyToken());
            if (!MessageDigest.isEqual(verifyToken, decryptedToken)) {
                // The client could not decrypt Relay's nonce, so it is not talking to
                // the key Relay published. Treat as hostile, not as a bad password.
                LOG.warn("Verify token mismatch for {} from {}", username, connection.remoteAddress());
                kick(Component.text("Encryption handshake failed", NamedTextColor.RED));
                return true;
            }
            sharedSecret = Encryption.decryptRsa(proxy.keyPair().getPrivate(), packet.sharedSecret());
        } catch (GeneralSecurityException e) {
            LOG.warn("Could not decrypt the encryption response from {}", connection.remoteAddress(), e);
            kick(Component.text("Encryption handshake failed", NamedTextColor.RED));
            return true;
        }

        String serverHash = Encryption.serverHash("", sharedSecret, proxy.keyPair().getPublic());
        connection.enableEncryption(sharedSecret);

        proxy.authenticator().hasJoined(username, serverHash).whenComplete((authenticated, error) ->
                connection.channel().eventLoop().execute(() -> onAuthenticated(authenticated, error)));
        return true;
    }

    private void onAuthenticated(GameProfile authenticated, Throwable error) {
        if (!connection.isActive()) {
            return;
        }
        if (error != null) {
            // Distinguishing these matters: a session server outage is not the player's
            // fault, and telling them "unverified account" would send them chasing a
            // problem that is not theirs.
            if (AuthenticationException.isTransport(error.getCause() == null ? error : error.getCause())) {
                LOG.error("Session server unreachable while authenticating {}", username, error);
                kick(Component.text("Could not reach the authentication servers, try again shortly",
                        NamedTextColor.RED));
            } else {
                LOG.warn("Authentication failed for {}", username, error);
                kick(Component.text("Authentication failed", NamedTextColor.RED));
            }
            return;
        }
        if (authenticated == null) {
            kick(Component.text("Unverified account — restart your launcher and try again",
                    NamedTextColor.RED));
            return;
        }
        completeLogin(authenticated);
    }

    private void completeLogin(GameProfile profile) {
        this.profile = profile;

        int threshold = proxy.config().compressionThreshold();
        if (threshold >= 0) {
            // Order matters: the threshold applies from the next frame onward, so the
            // packet announcing it must be encoded before the codec is installed.
            connection.write(new SetCompressionPacket(threshold));
            connection.enableCompression(threshold, proxy.config().compressionLevel());
        }

        state = State.AWAITING_ACK;
        connection.write(new LoginSuccessPacket(profile.uuid(), profile.name(), profile.properties()));
    }

    @Override
    public boolean handle(LoginAcknowledgedPacket packet) {
        if (state != State.AWAITING_ACK) {
            connection.close();
            return true;
        }
        ConnectedPlayer player = new ConnectedPlayer(
                connection, profile, connection.version(), handshake.cleanServerAddress());

        // Checked before the state moves on: kick() writes a login-state disconnect, and
        // that packet has no id once the connection is in configuration state.
        if (!proxy.players().add(player)) {
            // Refuse the newcomer rather than displace the incumbent: the session
            // already playing is far more likely to be the legitimate one.
            kick(Component.text("You are already connected to this proxy", NamedTextColor.RED));
            return true;
        }

        connection.setState(ProtocolState.CONFIGURATION);
        LOG.info("{} connected from {} on {}", player.username(), player.remoteIp(), player.version());

        ClientConfigSessionHandler configHandler = new ClientConfigSessionHandler(proxy, player);
        connection.setSessionHandler(configHandler);
        new BackendConnector(proxy, player).connectToInitialServer();
        return true;
    }

    @Override
    public void disconnected() {
        if (username != null && state != State.AWAITING_ACK) {
            LOG.debug("{} disconnected during login ({})", username, state);
        }
    }

    private void kick(Component reason) {
        connection.closeWith(LoginDisconnectPacket.of(reason));
    }
}
