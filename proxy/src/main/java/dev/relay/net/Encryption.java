package dev.relay.net;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;

/** The crypto primitives behind online-mode login. */
public final class Encryption {

    /**
     * Mojang's key size. Not a security choice Relay gets to make &mdash; the vanilla
     * client will not negotiate anything else.
     */
    private static final int RSA_KEY_SIZE = 1024;

    private static final String AES_TRANSFORM = "AES/CFB8/NoPadding";
    private static final String RSA_TRANSFORM = "RSA/ECB/PKCS1Padding";

    private Encryption() {
    }

    public static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(RSA_KEY_SIZE);
            return generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("JVM cannot generate an RSA key pair", e);
        }
    }

    /** AES/CFB8 with the shared secret doubling as the IV, per the Minecraft protocol. */
    public static Cipher cipher(int mode, byte[] sharedSecret) {
        try {
            Cipher cipher = Cipher.getInstance(AES_TRANSFORM);
            SecretKeySpec key = new SecretKeySpec(sharedSecret, "AES");
            cipher.init(mode, key, new IvParameterSpec(sharedSecret));
            return cipher;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot initialise " + AES_TRANSFORM, e);
        }
    }

    public static byte[] decryptRsa(PrivateKey privateKey, byte[] data) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance(RSA_TRANSFORM);
        cipher.init(Cipher.DECRYPT_MODE, privateKey);
        return cipher.doFinal(data);
    }

    /**
     * The session-server login hash: SHA-1 over the server id, shared secret and public
     * key, rendered as a <em>signed</em> two's-complement hex string.
     *
     * <p>That signed rendering is the part everyone gets wrong. It is not a conventional
     * hex digest &mdash; negative digests are written with a leading minus, which is why
     * this goes through {@link BigInteger} rather than formatting bytes.
     */
    public static String serverHash(String serverId, byte[] sharedSecret, PublicKey publicKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            digest.update(serverId.getBytes(StandardCharsets.US_ASCII));
            digest.update(sharedSecret);
            digest.update(publicKey.getEncoded());
            return new BigInteger(digest.digest()).toString(16);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("JVM has no SHA-1 implementation", e);
        }
    }
}
