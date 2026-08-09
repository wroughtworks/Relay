package dev.relay.forwarding;

import dev.relay.auth.GameProfile;
import dev.relay.protocol.ProtocolUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;

/**
 * Velocity-compatible modern forwarding.
 *
 * <p>The backend asks for the player's identity over a login plugin channel; Relay
 * answers with the profile and an HMAC-SHA256 signature over it, keyed on a secret both
 * sides hold. A backend that cannot verify the signature drops the login, so a spoofed
 * direct connection to the backend port fails even if the firewall does not stop it.
 *
 * <p>Wire-compatible with Velocity on purpose: the spec targets an existing network
 * whose Paper backends are already configured for it, so cutover needs no backend change.
 */
public final class ModernForwarding {

    public static final String CHANNEL = "velocity:player_info";

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final int SIGNATURE_LENGTH = 32;

    /** Profile only, no player key. */
    private static final int VERSION_DEFAULT = 1;
    /**
     * Profile only, but signals that chat session keys are established later via the
     * player session packet. Correct for every version Relay supports, all of which are
     * past the point where the public key left the login packet.
     */
    private static final int VERSION_LAZY_SESSION = 4;

    private ModernForwarding() {
    }

    /**
     * A short, non-reversible fingerprint of a forwarding secret.
     *
     * <p>Exists so the two sides of a forwarding setup can be compared without either
     * being printed. A secret mismatch is the single most common cause of a backend
     * refusing a proxied login, and the only symptom is the backend's generic "unable to
     * verify player details" &mdash; which says nothing about which side is wrong.
     * Comparing eight hex characters settles it immediately.
     */
    public static String fingerprint(byte[] secret) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(secret);
            StringBuilder hex = new StringBuilder(8);
            for (int i = 0; i < 4; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("JVM has no SHA-256 implementation", e);
        }
    }

    /** Reads the forwarding version the backend asked for out of its request payload. */
    public static int requestedVersion(byte[] requestData) {
        return requestData.length == 0 ? VERSION_DEFAULT : requestData[0];
    }

    /**
     * Builds the response payload: a 32-byte signature followed by the signed profile.
     *
     * @param playerAddress the player's IP, without a port &mdash; this becomes the
     *                      address the backend reports for the player
     */
    public static byte[] createResponse(byte[] secret, String playerAddress, GameProfile profile,
                                        int requestedVersion) {
        int version = requestedVersion >= VERSION_LAZY_SESSION ? VERSION_LAZY_SESSION : VERSION_DEFAULT;

        ByteBuf data = Unpooled.buffer(256);
        try {
            ProtocolUtils.writeVarInt(data, version);
            ProtocolUtils.writeString(data, playerAddress);
            ProtocolUtils.writeUuid(data, profile.uuid());
            ProtocolUtils.writeString(data, profile.name());
            ProtocolUtils.writeProperties(data, profile.properties());

            byte[] payload = new byte[data.readableBytes()];
            data.getBytes(data.readerIndex(), payload);

            byte[] signature = sign(secret, payload);

            byte[] response = new byte[signature.length + payload.length];
            System.arraycopy(signature, 0, response, 0, signature.length);
            System.arraycopy(payload, 0, response, signature.length, payload.length);
            return response;
        } finally {
            data.release();
        }
    }

    private static byte[] sign(byte[] secret, byte[] payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
            return mac.doFinal(payload);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot compute " + HMAC_ALGORITHM + " signature", e);
        }
    }

    /**
     * Verifies a response built by {@link #createResponse}.
     *
     * <p>Relay never needs this in normal operation &mdash; it signs rather than
     * verifies &mdash; but it keeps the signing format honest under test, and the
     * constant-time comparison documents the property a backend implementation needs.
     */
    public static boolean verify(byte[] secret, byte[] response) {
        if (response.length < SIGNATURE_LENGTH) {
            return false;
        }
        byte[] signature = new byte[SIGNATURE_LENGTH];
        byte[] payload = new byte[response.length - SIGNATURE_LENGTH];
        System.arraycopy(response, 0, signature, 0, SIGNATURE_LENGTH);
        System.arraycopy(response, SIGNATURE_LENGTH, payload, 0, payload.length);
        return MessageDigest.isEqual(signature, sign(secret, payload));
    }
}
