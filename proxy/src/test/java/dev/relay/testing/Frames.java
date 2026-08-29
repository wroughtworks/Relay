package dev.relay.testing;

import dev.relay.protocol.ProtocolUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;

/**
 * Minecraft framing, spoken by hand, for tests that drive real sockets.
 *
 * <p>Thirteen test classes had grown their own copy of this. That is not merely
 * duplication: it is why writing the next socket-level test feels expensive, and a test
 * that feels expensive does not get written. The framing is also exactly the wrong thing
 * to reimplement per file, since a subtly different copy would pass while proving nothing.
 *
 * <p>Deliberately not built on {@code StateRegistry} or the codec pipeline. A test that
 * frames packets using the same code it is testing agrees with itself by construction; a
 * symmetric encode/decode bug would sail straight through. These write bytes the protocol
 * requires, not bytes Relay believes in.
 */
public final class Frames {

    private Frames() {
    }

    /** Length-prefixed, uncompressed: what the protocol looks like before compression. */
    public static void write(OutputStream out, ByteBuf payload) throws IOException {
        try {
            ByteBuf framed = Unpooled.buffer();
            try {
                ProtocolUtils.writeVarInt(framed, payload.readableBytes());
                framed.writeBytes(payload);
                byte[] bytes = new byte[framed.readableBytes()];
                framed.readBytes(bytes);
                out.write(bytes);
                out.flush();
            } finally {
                framed.release();
            }
        } finally {
            payload.release();
        }
    }

    public static ByteBuf read(InputStream in) throws IOException {
        DataInputStream data = new DataInputStream(in);
        int length = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            byte b = data.readByte();
            length |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                break;
            }
        }
        byte[] payload = new byte[length];
        data.readFully(payload);
        return Unpooled.wrappedBuffer(payload);
    }

    /**
     * Reads frames until one carries {@code id}, discarding the rest.
     *
     * @return the body with the id already consumed, or {@code null} if it never came
     */
    public static ByteBuf readUntil(InputStream in, int id) throws IOException {
        for (int i = 0; i < 500; i++) {
            ByteBuf frame = read(in);
            try {
                if (ProtocolUtils.readVarInt(frame.duplicate()) == id) {
                    ByteBuf body = frame.copy();
                    ProtocolUtils.readVarInt(body);
                    return body;
                }
            } finally {
                frame.release();
            }
        }
        return null;
    }

    /**
     * Reads frames until {@code wanted} arrives, failing if {@code forbidden} shows up.
     *
     * <p>For the ordering rules that only a live client enforces. "This packet must not
     * arrive after that one" is invisible to a test that merely skips what it is not
     * looking for, and invisible is how a real corruption bug survived a passing suite.
     *
     * @return the id that ended the search: {@code wanted} on success, {@code forbidden}
     *         if that came first
     */
    public static int readUntilEither(InputStream in, int wanted, int forbidden) throws IOException {
        for (int i = 0; i < 500; i++) {
            ByteBuf frame = read(in);
            try {
                int id = ProtocolUtils.readVarInt(frame.duplicate());
                if (id == wanted || id == forbidden) {
                    return id;
                }
            } finally {
                frame.release();
            }
        }
        return -1;
    }

    public static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress("127.0.0.1", 0));
            return socket.getLocalPort();
        }
    }
}
