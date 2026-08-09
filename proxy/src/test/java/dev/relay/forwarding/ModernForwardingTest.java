package dev.relay.forwarding;

import dev.relay.auth.GameProfile;
import dev.relay.protocol.ProtocolUtils;
import dev.relay.protocol.ProtocolUtils.GameProfileProperty;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModernForwardingTest {

    private static final byte[] SECRET = "a-shared-forwarding-secret".getBytes(StandardCharsets.UTF_8);

    private static final GameProfile PROFILE = new GameProfile(
            UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5"),
            "Notch",
            List.of(new GameProfileProperty("textures", "base64-encoded-skin", "a-signature")));

    @Test
    void signedResponseVerifies() {
        byte[] response = ModernForwarding.createResponse(SECRET, "203.0.113.7", PROFILE, 1);
        assertTrue(ModernForwarding.verify(SECRET, response));
    }

    /** The whole point of modern forwarding: a tampered profile must not verify. */
    @Test
    void tamperedPayloadFailsVerification() {
        byte[] response = ModernForwarding.createResponse(SECRET, "203.0.113.7", PROFILE, 1);
        response[response.length - 1] ^= 0x01;
        assertFalse(ModernForwarding.verify(SECRET, response));
    }

    @Test
    void wrongSecretFailsVerification() {
        byte[] response = ModernForwarding.createResponse(SECRET, "203.0.113.7", PROFILE, 1);
        assertFalse(ModernForwarding.verify("a-different-secret".getBytes(StandardCharsets.UTF_8), response));
    }

    @Test
    void truncatedResponseFailsVerificationRatherThanThrowing() {
        assertFalse(ModernForwarding.verify(SECRET, new byte[8]));
    }

    /** The payload layout is what the backend parses, so it is asserted field by field. */
    @Test
    void payloadCarriesTheProfileInProtocolOrder() {
        byte[] response = ModernForwarding.createResponse(SECRET, "203.0.113.7", PROFILE, 1);
        var buf = Unpooled.wrappedBuffer(response);
        try {
            buf.skipBytes(32); // HMAC-SHA256 signature

            assertEquals(1, ProtocolUtils.readVarInt(buf), "forwarding version");
            assertEquals("203.0.113.7", ProtocolUtils.readString(buf));
            assertEquals(PROFILE.uuid(), ProtocolUtils.readUuid(buf));
            assertEquals("Notch", ProtocolUtils.readString(buf));

            var properties = ProtocolUtils.readProperties(buf);
            assertEquals(1, properties.size());
            assertEquals("textures", properties.get(0).name());
            assertEquals("a-signature", properties.get(0).signature());
            assertFalse(buf.isReadable(), "no trailing bytes");
        } finally {
            buf.release();
        }
    }

    /**
     * Every version Relay supports is past the point where the client sends a public key
     * at login, so a backend asking for a modern version gets lazy-session rather than a
     * key-bearing reply it would then fail to parse.
     */
    @Test
    void negotiatesLazySessionWhenTheBackendSupportsIt() {
        assertEquals(4, versionOf(ModernForwarding.createResponse(SECRET, "1.2.3.4", PROFILE, 4)));
        assertEquals(1, versionOf(ModernForwarding.createResponse(SECRET, "1.2.3.4", PROFILE, 1)));
        // An older backend must not be handed a newer format than it asked for.
        assertEquals(1, versionOf(ModernForwarding.createResponse(SECRET, "1.2.3.4", PROFILE, 2)));
    }

    @Test
    void anEmptyRequestMeansVersionOne() {
        assertEquals(1, ModernForwarding.requestedVersion(new byte[0]));
        assertEquals(4, ModernForwarding.requestedVersion(new byte[]{4}));
    }

    /**
     * Verifies the response the way Paper does, rather than by calling Relay's own
     * verifier.
     *
     * <p>{@link ModernForwarding#verify} shares its signing code with
     * {@code createResponse}, so it would agree with itself even if both were wrong
     * about the layout. This reimplements Paper's {@code VelocityProxy.checkIntegrity}
     * from its documented behaviour: read a 32-byte signature, HMAC-SHA256 everything
     * after it, compare. A mismatch here is what a backend reports as "Unable to verify
     * player details".
     */
    @Test
    void signatureMatchesAnIndependentPaperStyleCheck() throws Exception {
        byte[] response = ModernForwarding.createResponse(SECRET, "203.0.113.7", PROFILE, 4);

        byte[] signature = Arrays.copyOfRange(response, 0, 32);
        byte[] data = Arrays.copyOfRange(response, 32, response.length);

        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET, "HmacSHA256"));
        byte[] expected = mac.doFinal(data);

        assertTrue(MessageDigest.isEqual(signature, expected),
                "the signature must cover exactly the bytes following it, keyed on the raw secret");
    }

    /** The secret is used as raw UTF-8 key material, which is how Paper reads its copy. */
    @Test
    void secretIsUsedAsRawUtf8Bytes() throws Exception {
        String secret = "sh4red-s3cret";
        byte[] response = ModernForwarding.createResponse(
                secret.getBytes(StandardCharsets.UTF_8), "1.2.3.4", PROFILE, 4);

        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));

        assertTrue(MessageDigest.isEqual(
                Arrays.copyOfRange(response, 0, 32),
                mac.doFinal(Arrays.copyOfRange(response, 32, response.length))));
    }

    /** A trailing newline on either side is a different key entirely. */
    @Test
    void whitespaceInTheSecretChangesTheSignature() {
        byte[] clean = ModernForwarding.createResponse(SECRET, "1.2.3.4", PROFILE, 4);
        byte[] padded = "a-shared-forwarding-secret\n".getBytes(StandardCharsets.UTF_8);
        assertFalse(ModernForwarding.verify(padded, clean),
                "a secret differing only by whitespace must not verify");
    }

    private static int versionOf(byte[] response) {
        var buf = Unpooled.wrappedBuffer(response);
        try {
            buf.skipBytes(32);
            return ProtocolUtils.readVarInt(buf);
        } finally {
            buf.release();
        }
    }
}
