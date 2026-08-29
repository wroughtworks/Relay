package dev.relay.health;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.relay.protocol.ProtocolUtils;
import dev.relay.protocol.ProtocolVersion;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;

/**
 * One server-list ping to a backend, spoken by hand over a plain socket.
 *
 * <p>A status ping rather than a bare TCP connect, because the extra two packets buy a
 * great deal: round-trip latency, the backend's own player count and cap, and its version
 * string. A socket that opens proves a port is bound; a status response proves Minecraft
 * is running behind it and has finished starting up, which is the thing an operator
 * actually wants to know.
 *
 * <p>Blocking, and deliberately not on a Netty pipeline. Health checks run every few
 * seconds against a handful of addresses, so a thread that sleeps on a socket read costs
 * nothing worth optimising &mdash; and keeping it off the event loops means a backend that
 * accepts a connection and then says nothing can never stall traffic for players who are
 * connected and fine.
 *
 * <p>The handshake declares next-state 1 (status), so this never reaches login and never
 * needs the forwarding secret. To a backend it is indistinguishable from a server list
 * refreshing, which is also why it is safe to run against a server full of players.
 */
final class StatusProbe {

    /** Beyond this, a response is not worth waiting for and the backend is in trouble. */
    private static final int MAX_RESPONSE_BYTES = 1 << 20;

    private StatusProbe() {
    }

    /** What a backend said about itself, or why it did not. */
    record Result(boolean reachable, long latencyMillis, int players, int max, String version,
                  String detail) {

        static Result failed(String detail) {
            return new Result(false, -1, -1, -1, null, detail);
        }
    }

    static Result ping(InetSocketAddress address, int timeoutMillis) {
        long started = System.nanoTime();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(address.getHostString(), address.getPort()), timeoutMillis);
            socket.setSoTimeout(timeoutMillis);

            OutputStream out = socket.getOutputStream();
            writeFrame(out, handshake(address));
            writeFrame(out, Unpooled.buffer().writeByte(0x00));   // status request
            out.flush();

            ByteBuf response = readFrame(socket.getInputStream());
            long latency = (System.nanoTime() - started) / 1_000_000;

            ProtocolUtils.readVarInt(response);                   // packet id
            String json = ProtocolUtils.readString(response);
            return parse(json, latency);
        } catch (SocketTimeoutException e) {
            return Result.failed("no status response within " + timeoutMillis + "ms");
        } catch (IOException e) {
            // The message alone: a stack trace per failed check, every few seconds, for a
            // backend that is simply switched off would bury everything else.
            return Result.failed(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        } catch (RuntimeException e) {
            return Result.failed("unreadable status response: " + e);
        }
    }

    /**
     * Reads what Relay cares about, and tolerates the rest.
     *
     * <p>A status response is whatever the backend and its plugins decided to put there.
     * Fields get renamed, omitted, and wrapped by MOTD plugins, so nothing here is
     * required: a response that parses at all already proves the server is answering,
     * which is the point of the check.
     */
    private static Result parse(String json, long latency) {
        int players = -1;
        int max = -1;
        String version = null;
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            if (root.has("players") && root.get("players").isJsonObject()) {
                JsonObject counts = root.getAsJsonObject("players");
                if (counts.has("online")) {
                    players = counts.get("online").getAsInt();
                }
                if (counts.has("max")) {
                    max = counts.get("max").getAsInt();
                }
            }
            if (root.has("version") && root.get("version").isJsonObject()) {
                JsonObject reported = root.getAsJsonObject("version");
                if (reported.has("name")) {
                    version = reported.get("name").getAsString();
                }
            }
        } catch (RuntimeException ignored) {
            // Answering with something unparseable still means answering.
        }
        return new Result(true, latency, players, max, version, null);
    }

    private static ByteBuf handshake(InetSocketAddress address) {
        ByteBuf buf = Unpooled.buffer();
        buf.writeByte(0x00);
        // The oldest supported version, so a backend running anything in the range
        // answers. A status ping is version-agnostic; claiming the newest would risk an
        // "outdated client" response from an older backend.
        ProtocolUtils.writeVarInt(buf, ProtocolVersion.oldest().id());
        ProtocolUtils.writeString(buf, address.getHostString());
        buf.writeShort(address.getPort());
        ProtocolUtils.writeVarInt(buf, 1);   // next state: status, never login
        return buf;
    }

    private static void writeFrame(OutputStream out, ByteBuf payload) throws IOException {
        try {
            ByteBuf framed = Unpooled.buffer();
            try {
                ProtocolUtils.writeVarInt(framed, payload.readableBytes());
                framed.writeBytes(payload);
                byte[] bytes = new byte[framed.readableBytes()];
                framed.readBytes(bytes);
                out.write(bytes);
            } finally {
                framed.release();
            }
        } finally {
            payload.release();
        }
    }

    private static ByteBuf readFrame(InputStream in) throws IOException {
        DataInputStream data = new DataInputStream(in);
        int length = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            byte b = data.readByte();
            length |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                break;
            }
        }
        if (length < 0 || length > MAX_RESPONSE_BYTES) {
            throw new IOException("status response claims " + length + " bytes");
        }
        byte[] payload = new byte[length];
        data.readFully(payload);
        return Unpooled.wrappedBuffer(payload);
    }
}
