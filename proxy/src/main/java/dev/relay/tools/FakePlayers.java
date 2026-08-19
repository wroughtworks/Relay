package dev.relay.tools;

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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Connects a crowd of fake players, to watch where a proxy puts them.
 *
 * <p>Balancing is the one feature that cannot be judged from a single connection. Whether
 * {@code least-players} actually spreads people, whether a drained backend really stops
 * receiving them, whether a group empties evenly when a member goes down &mdash; all of it
 * needs a population, and forty real clients is not a thing anyone has to hand.
 *
 * <p>These are real connections speaking the real protocol, not a mock: a proxy cannot
 * tell them from a client, which is the point. They complete login and configuration,
 * enter play, and then sit still.
 *
 * <h2>What this does not measure</h2>
 * <b>Routing, not load.</b> A fake player never moves, never loads a chunk and never sends
 * anything the server has to think about, so a backend hosting a hundred of them is barely
 * working. Numbers from here say where players were <em>sent</em>; they say nothing about
 * whether a server could cope with them.
 *
 * <h2>Offline mode is required</h2>
 * Nothing here can authenticate with Mojang, so a proxy in online mode will ask for
 * encryption and be told the truth: this is a fake player, and it is going away. That
 * refusal is reported clearly rather than as a protocol error, because "set
 * online-mode = false to load test" is the only useful thing to say at that point.
 */
public final class FakePlayers {

    private static final int PROTOCOL = ProtocolVersion.MINECRAFT_1_20_2.id();

    // --- serverbound ---
    private static final int SB_HANDSHAKE = 0x00;
    private static final int SB_LOGIN_START = 0x00;
    private static final int SB_LOGIN_ACK = 0x03;
    private static final int SB_CONFIG_CLIENT_INFO = 0x00;
    private static final int SB_CONFIG_FINISH_ACK = 0x02;
    /** Agreeing to leave play state, which is how a switch begins. */
    private static final int SB_PLAY_CONFIG_ACK = 0x0B;
    /** Confirmed against a live Paper 1.20.2 log: {@code IN: [play:20]}. */
    private static final int SB_PLAY_KEEP_ALIVE = 0x14;

    // --- clientbound ---
    private static final int CB_LOGIN_DISCONNECT = 0x00;
    private static final int CB_ENCRYPTION_REQUEST = 0x01;
    private static final int CB_LOGIN_SUCCESS = 0x02;
    private static final int CB_SET_COMPRESSION = 0x03;
    private static final int CB_CONFIG_DISCONNECT = 0x01;
    private static final int CB_CONFIG_FINISH = 0x02;
    /** The proxy asking the client back into configuration, to move it elsewhere. */
    private static final int CB_PLAY_START_CONFIGURATION = 0x65;

    /** A keep-alive is a bare long, and nothing else clientbound is exactly this size. */
    private static final int KEEP_ALIVE_BYTES = 8;

    private FakePlayers() {
    }

    public static void main(String[] args) throws Exception {
        Options options = Options.parse(args);
        if (options == null) {
            return;
        }

        System.out.printf("Connecting %d fake players to %s:%d as %s0..%s%d%n",
                options.count, options.host, options.port,
                options.prefix, options.prefix, options.count - 1);
        System.out.println("They speak the real protocol and then sit still: this measures "
                + "routing, not load.");

        List<FakePlayer> players = new ArrayList<>(options.count);
        AtomicInteger joined = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        CountDownLatch settled = new CountDownLatch(options.count);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\nDisconnecting.");
            players.forEach(FakePlayer::close);
        }));

        for (int i = 0; i < options.count; i++) {
            FakePlayer player = new FakePlayer(options, options.prefix + i, i == 0,
                    joined, failed, settled);
            players.add(player);
            player.start();
            // Staggered, because a proxy that receives forty simultaneous logins is being
            // asked a different question than one receiving forty over a few seconds --
            // and least-players in particular needs each choice to see the last one.
            Thread.sleep(options.staggerMillis);
        }

        settled.await(30, TimeUnit.SECONDS);
        System.out.printf("%d connected, %d failed.%n", joined.get(), failed.get());
        if (joined.get() == 0) {
            System.out.println("Nobody got in. The reason above is the one that matters.");
            return;
        }
        System.out.println("Holding them online. Check the dashboard or /glist, then Ctrl+C.");

        // Nothing else to do: the player threads keep the sockets alive, and the point of
        // the tool is that they stay put while somebody looks at the distribution.
        Thread.currentThread().join();
    }

    /** One connection, driven to play state and then held there. */
    private static final class FakePlayer {

        private final Options options;
        private final String username;
        private final AtomicInteger joined;
        private final AtomicInteger failed;
        private final CountDownLatch settled;

        private volatile Socket socket;
        private volatile boolean closed;
        /** Only the first player traces: forty timelines interleaved say nothing. */
        private final boolean traced;
        /** Counted so a flapping backend shows up as a player being passed around. */
        private int switches;

        FakePlayer(Options options, String username, boolean traced, AtomicInteger joined,
                   AtomicInteger failed, CountDownLatch settled) {
            this.options = options;
            this.username = username;
            this.traced = traced;
            this.joined = joined;
            this.failed = failed;
            this.settled = settled;
        }

        void start() {
            Thread thread = new Thread(this::run, "fake-" + username);
            thread.setDaemon(true);
            thread.start();
        }

        private void run() {
            boolean counted = false;
            try (Socket connection = new Socket()) {
                socket = connection;
                connection.connect(new InetSocketAddress(options.host, options.port), 10_000);
                connection.setSoTimeout(60_000);
                Stream stream = new Stream(connection.getInputStream(), connection.getOutputStream());

                handshake(stream);
                login(stream);
                configure(stream);

                joined.incrementAndGet();
                counted = true;
                settled.countDown();

                play(stream);
            } catch (Failure e) {
                report(e.getMessage());
                if (!counted) {
                    failed.incrementAndGet();
                    settled.countDown();
                }
            } catch (IOException e) {
                if (!closed) {
                    report(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                }
                if (!counted) {
                    failed.incrementAndGet();
                    settled.countDown();
                }
            }
        }

        /** One line per distinct reason: forty identical failures say nothing extra. */
        private void report(String reason) {
            synchronized (FakePlayers.class) {
                if (REPORTED.add(reason)) {
                    System.out.println("  " + username + ": " + reason);
                }
            }
        }

        private void handshake(Stream stream) throws IOException {
            ByteBuf buf = Unpooled.buffer();
            buf.writeByte(SB_HANDSHAKE);
            ProtocolUtils.writeVarInt(buf, PROTOCOL);
            ProtocolUtils.writeString(buf, options.host);
            buf.writeShort(options.port);
            ProtocolUtils.writeVarInt(buf, 2);        // next state: login
            stream.write(buf);

            ByteBuf start = Unpooled.buffer();
            start.writeByte(SB_LOGIN_START);
            ProtocolUtils.writeString(start, username);
            // The offline UUID Mojang's own scheme produces, so a backend in offline mode
            // sees the same identity it would for a real player of this name.
            ProtocolUtils.writeUuid(start, offlineUuid(username));
            stream.write(start);
        }

        private void login(Stream stream) throws IOException {
            while (true) {
                ByteBuf frame = stream.read();
                int id = ProtocolUtils.readVarInt(frame);
                switch (id) {
                    case CB_SET_COMPRESSION -> stream.setCompression(ProtocolUtils.readVarInt(frame));
                    case CB_ENCRYPTION_REQUEST -> throw new Failure(
                            "the proxy is in online mode and asked for encryption. Fake players cannot "
                                    + "authenticate with Mojang; set online-mode = false in relay.toml to "
                                    + "load test, and back afterwards.");
                    case CB_LOGIN_DISCONNECT -> throw new Failure(
                            "refused during login: " + readReason(frame));
                    case CB_LOGIN_SUCCESS -> {
                        stream.write(Unpooled.buffer().writeByte(SB_LOGIN_ACK));
                        return;
                    }
                    default -> {
                        // Login plugin requests and anything else: ignored. Relay answers
                        // the ones that matter before this point in the exchange.
                    }
                }
            }
        }

        private void configure(Stream stream) throws IOException {
            // Client Information first, unprompted, exactly as a real client does. A
            // backend that never receives it has a player it knows nothing about -- no
            // locale, no view distance -- and Paper stops waiting quickly. The first
            // version of this tool skipped it and every fake player was kicked within a
            // second of joining, reported as a timeout.
            ByteBuf info = Unpooled.buffer();
            info.writeByte(SB_CONFIG_CLIENT_INFO);
            ProtocolUtils.writeString(info, "en_gb");
            info.writeByte(8);                        // view distance
            ProtocolUtils.writeVarInt(info, 0);       // chat mode: enabled
            info.writeBoolean(true);                  // chat colours
            info.writeByte(0x7F);                     // every skin layer
            ProtocolUtils.writeVarInt(info, 1);       // main hand: right
            info.writeBoolean(false);                 // text filtering
            info.writeBoolean(true);                  // visible in server listings
            stream.write(info);

            while (true) {
                ByteBuf frame = stream.read();
                int id = ProtocolUtils.readVarInt(frame);
                if (id == CB_CONFIG_DISCONNECT) {
                    throw new Failure("refused while configuring: " + readReason(frame));
                }
                if (id == CB_CONFIG_FINISH) {
                    stream.write(Unpooled.buffer().writeByte(SB_CONFIG_FINISH_ACK));
                    return;
                }
                // Registries, tags, feature flags: accepted without being understood,
                // exactly as a client that trusts its server does.
            }
        }

        /**
         * Sits in play, answering keep-alives so the backend does not time them out.
         *
         * <p>The keep-alive's clientbound id is learned rather than assumed: it is the
         * only packet that arrives periodically carrying exactly a long, so a frame with
         * an eight-byte body is one. Hard-coding the id would have meant this tool
         * breaking on the next version for a reason unrelated to what it tests.
         */
        private void play(Stream stream) throws IOException {
            long enteredPlay = System.currentTimeMillis();

            while (!closed) {
                ByteBuf frame = stream.read();
                int id = ProtocolUtils.readVarInt(frame);
                int body = frame.readableBytes();
                long elapsed = System.currentTimeMillis() - enteredPlay;

                // Tracing is the point of the flag: a keep-alive cannot be picked out of a
                // join by size alone, because the first seconds are full of packets that
                // happen to be eight bytes. What identifies it is arriving repeatedly,
                // long after the join has gone quiet -- which only a timeline shows.
                if (options.trace && traced) {
                    System.out.printf("  %6.1fs  id=0x%-4s %4dB%n", elapsed / 1000.0,
                            Integer.toHexString(id), body);
                }

                // Being moved. A fake player that ignores this sits in play state while
                // the proxy waits for an acknowledgement that never comes -- connected to
                // the proxy, on no backend at all, and counted in neither place. That is
                // exactly how a first run reported twelve players online with every
                // backend showing zero.
                if (id == CB_PLAY_START_CONFIGURATION) {
                    stream.write(Unpooled.buffer().writeByte(SB_PLAY_CONFIG_ACK));
                    configure(stream);
                    switches++;
                    if (traced) {
                        System.out.printf("  %6.1fs  moved to another backend (switch %d)%n",
                                elapsed / 1000.0, switches);
                    }
                    continue;
                }

                if (body == KEEP_ALIVE_BYTES) {
                    ByteBuf pong = Unpooled.buffer();
                    pong.writeByte(SB_PLAY_KEEP_ALIVE);
                    pong.writeLong(frame.readLong());
                    stream.write(pong);
                }
            }
        }

        /** One line per distinct observation, whatever the size of the crowd. */
        private void note(String message) {
            synchronized (FakePlayers.class) {
                if (REPORTED.add(message)) {
                    System.out.println("  " + message);
                }
            }
        }

        void close() {
            closed = true;
            Socket current = socket;
            if (current != null) {
                try {
                    current.close();
                } catch (IOException ignored) {
                    // Going away regardless.
                }
            }
        }
    }

    /** Reasons already printed, so forty identical failures produce one line. */
    private static final java.util.Set<String> REPORTED = new java.util.HashSet<>();

    /** A disconnect reason, read loosely: it may be JSON or NBT depending on version. */
    private static String readReason(ByteBuf frame) {
        try {
            return ProtocolUtils.readString(frame);
        } catch (RuntimeException notAString) {
            return "(unreadable reason)";
        }
    }

    /** Mojang's scheme for a nameless account, so offline backends agree with us. */
    private static UUID offlineUuid(String username) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8));
    }

    /** Framing, with compression switched on mid-stream the way the protocol does it. */
    private static final class Stream {

        private final DataInputStream in;
        private final OutputStream out;

        /** -1 until the server says otherwise, which is the protocol's "off". */
        private int threshold = -1;

        Stream(InputStream in, OutputStream out) {
            this.in = new DataInputStream(in);
            this.out = out;
        }

        void setCompression(int threshold) {
            this.threshold = threshold;
        }

        void write(ByteBuf payload) throws IOException {
            byte[] body = new byte[payload.readableBytes()];
            payload.readBytes(body);

            ByteBuf framed = Unpooled.buffer();
            if (threshold < 0) {
                ProtocolUtils.writeVarInt(framed, body.length);
                framed.writeBytes(body);
            } else if (body.length < threshold) {
                // Under the threshold: a zero data-length means "not compressed".
                ByteBuf inner = Unpooled.buffer();
                ProtocolUtils.writeVarInt(inner, 0);
                inner.writeBytes(body);
                ProtocolUtils.writeVarInt(framed, inner.readableBytes());
                framed.writeBytes(inner);
            } else {
                byte[] compressed = deflate(body);
                ByteBuf inner = Unpooled.buffer();
                ProtocolUtils.writeVarInt(inner, body.length);
                inner.writeBytes(compressed);
                ProtocolUtils.writeVarInt(framed, inner.readableBytes());
                framed.writeBytes(inner);
            }

            byte[] bytes = new byte[framed.readableBytes()];
            framed.readBytes(bytes);
            out.write(bytes);
            out.flush();
        }

        ByteBuf read() throws IOException {
            int length = readVarInt();
            byte[] payload = new byte[length];
            in.readFully(payload);
            if (threshold < 0) {
                return Unpooled.wrappedBuffer(payload);
            }

            ByteBuf buf = Unpooled.wrappedBuffer(payload);
            int uncompressed = ProtocolUtils.readVarInt(buf);
            if (uncompressed == 0) {
                return buf;
            }
            byte[] rest = new byte[buf.readableBytes()];
            buf.readBytes(rest);
            return Unpooled.wrappedBuffer(inflate(rest, uncompressed));
        }

        private int readVarInt() throws IOException {
            int value = 0;
            for (int shift = 0; shift < 35; shift += 7) {
                byte b = in.readByte();
                value |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) {
                    return value;
                }
            }
            throw new IOException("a varint that never ended");
        }

        private static byte[] deflate(byte[] input) {
            Deflater deflater = new Deflater();
            try {
                deflater.setInput(input);
                deflater.finish();
                byte[] buffer = new byte[input.length + 64];
                int written = deflater.deflate(buffer);
                byte[] result = new byte[written];
                System.arraycopy(buffer, 0, result, 0, written);
                return result;
            } finally {
                deflater.end();
            }
        }

        private static byte[] inflate(byte[] input, int size) throws IOException {
            Inflater inflater = new Inflater();
            try {
                inflater.setInput(input);
                byte[] output = new byte[size];
                inflater.inflate(output);
                return output;
            } catch (java.util.zip.DataFormatException e) {
                throw new IOException("malformed compressed packet", e);
            } finally {
                inflater.end();
            }
        }
    }

    /** A refusal Relay or a backend gave us, with a reason worth printing. */
    private static final class Failure extends IOException {
        Failure(String message) {
            super(message);
        }
    }

    private record Options(String host, int port, int count, long staggerMillis, String prefix,
                           boolean trace) {

        static Options parse(String[] args) {
            String host = "127.0.0.1";
            int port = 25565;
            int count = 20;
            long stagger = 150;
            String prefix = "Fake";
            boolean trace = false;

            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--host" -> host = args[++i];
                    case "--port" -> port = Integer.parseInt(args[++i]);
                    case "--count" -> count = Integer.parseInt(args[++i]);
                    case "--stagger" -> stagger = Long.parseLong(args[++i]);
                    case "--prefix" -> prefix = args[++i];
                    case "--trace" -> trace = true;
                    default -> {
                        System.out.println("""
                                Connects fake players, to see where a proxy puts them.

                                  --host <host>      default 127.0.0.1
                                  --port <port>      default 25565
                                  --count <n>        default 20
                                  --stagger <ms>     between connections, default 150
                                  --prefix <name>    default Fake
                                  --trace            print every play packet the first
                                                     player receives, with timings

                                The proxy must have online-mode = false: these cannot
                                authenticate with Mojang.""");
                        return null;
                    }
                }
            }
            return new Options(host, port, count, stagger, prefix, trace);
        }
    }
}
