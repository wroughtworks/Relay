package dev.relay.net.pipeline;

import io.netty.buffer.ByteBuf;

import javax.crypto.Cipher;
import javax.crypto.ShortBufferException;

/**
 * Runs a {@link ByteBuf} through an AES/CFB8 cipher.
 *
 * <p>CFB8 is a stream mode with one-byte granularity, so it does not care how the TCP
 * stream happens to be chunked &mdash; each buffer can be processed on arrival as long
 * as ordering holds, which Netty guarantees per channel. That also means ciphertext and
 * plaintext are the same length, so the work can be done in place.
 */
final class PacketCipher {

    private final Cipher cipher;
    private byte[] scratch = new byte[0];

    PacketCipher(Cipher cipher) {
        this.cipher = cipher;
    }

    /** Transforms {@code buf}'s readable bytes in place, leaving indexes untouched. */
    void process(ByteBuf buf) throws ShortBufferException {
        int length = buf.readableBytes();
        if (length == 0) {
            return;
        }
        if (scratch.length < length) {
            scratch = new byte[length];
        }
        buf.getBytes(buf.readerIndex(), scratch, 0, length);
        int produced = cipher.update(scratch, 0, length, scratch, 0);
        buf.setBytes(buf.readerIndex(), scratch, 0, produced);
    }
}
