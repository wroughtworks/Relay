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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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

    // --- serverbound, unchanged across every version this tool speaks ---
    private static final int SB_HANDSHAKE = 0x00;
    private static final int SB_LOGIN_START = 0x00;
    private static final int SB_LOGIN_ACK = 0x03;

    // --- clientbound login, likewise fixed ---
    private static final int CB_LOGIN_DISCONNECT = 0x00;
    private static final int CB_ENCRYPTION_REQUEST = 0x01;
    private static final int CB_LOGIN_SUCCESS = 0x02;
    private static final int CB_SET_COMPRESSION = 0x03;

    /**
     * The configuration- and play-state packet ids for one protocol version.
     *
     * <p>Every id here shifts when Mojang inserts a packet ahead of it, and a wrong one is
     * not a degraded test but a dead one: a server treats an unsolicited keep-alive
     * response as a timeout, and a client that never confirms its join teleport is never
     * sent a keep-alive at all. Both failures look identical from the outside &mdash; a
     * player kicked seconds after joining &mdash; which is why they cost a day to find.
     *
     * <p>Kept as this tool's own table rather than read out of {@link
     * dev.relay.protocol.StateRegistry}. A fake client that derives its ids from the proxy
     * under test agrees with the proxy by construction: were Relay's table wrong for a
     * version, the tool would speak the same wrong ids and every player would sail
     * through. These are the numbers a real client sends, and disagreeing with Relay is
     * the entire signal.
     */
    record Ids(String version,
                       int configClientInfo, int configFinishAck,
                       int configDisconnect, int configFinish,
                       int playConfigAck, int playKeepAlive, int playChatCommand,
                       int playAcceptTeleport,
                       int startConfiguration, int position, int keepAlive,
                       /**
                        * Whether Client Information carries a trailing particle status.
                        *
                        * <p>The one thing in this record that is not an id. Ids can be read
                        * out of Mojang's packet report; field layouts cannot, and this
                        * field was added in 1.21.2 with nothing in the report to say so.
                        *
                        * <p>Getting it wrong is at least loud: the server answers
                        * {@code DecoderException: Failed to decode packet
                        * 'serverbound/minecraft:client_information'} and drops the player
                        * during configuration, which is how this was found.
                        */
                       boolean clientInfoHasParticleStatus,
                       /**
                        * Known Packs, or {@code -1} at versions without it.
                        *
                        * <p>Added in 1.20.5 and easy to miss, because nothing about it
                        * fails: the server sends its pack list and waits for the client's
                        * before continuing, so a client that ignores it simply never
                        * finishes configuring. Both sides sit there until something times
                        * out, and the server's log says only "lost connection".
                        */
                       int knownPacksClientbound, int knownPacksServerbound) {
    }

    /**
     * Only versions whose ids come from the server itself.
     *
     * <p>Nothing is inferred into this table. From 1.21 the values are read out of
     * Mojang's own packet report, which the server jar generates on demand:
     *
     * <pre>java -DbundlerMainClass=net.minecraft.data.Main -jar &lt;server&gt;.jar --reports</pre>
     *
     * <p>1.20.2 predates that report provider and was read off a live Paper debug log
     * instead. 1.20.3&ndash;1.20.6 have neither and are therefore absent: an unsupported
     * version is refused with an explanation, because a plausible guess that is wrong
     * produces a tool which reports a proxy bug that does not exist, and there is no worse
     * outcome for a test tool than that.
     *
     * <p>Guessing was not a hypothetical risk here. Relay's own table was inferred the same
     * way and was wrong in 26 places once it was finally checked against these reports.
     */
    static final Map<Integer, Ids> IDS = Map.ofEntries(
            // Read off a live Paper 1.20.2 debug packet log, e.g.
            // OUT: [play:62] PacketPlayOutPosition, IN: [play:20] keep-alive.
            Map.entry(ProtocolVersion.MINECRAFT_1_20_2.id(), new Ids("1.20.2",
                    0x00, 0x02, 0x01, 0x02,
                    0x0B, 0x14, 0x04, 0x00,
                    0x65, 0x3E, 0x24, false, -1, -1)),
            // The rest from packets.json, one report per protocol version. The release
            // named is the build the ids were read from, not the only one they cover.
            Map.entry(ProtocolVersion.MINECRAFT_1_21.id(), new Ids("1.21.1",
                    0x00, 0x03, 0x02, 0x03,
                    0x0C, 0x18, 0x04, 0x00,
                    0x69, 0x40, 0x26, false, 0x0E, 0x07)),
            Map.entry(ProtocolVersion.MINECRAFT_1_21_2.id(), new Ids("1.21.3",
                    0x00, 0x03, 0x02, 0x03,
                    0x0E, 0x1A, 0x05, 0x00,
                    0x70, 0x42, 0x27, true, 0x0E, 0x07)),
            Map.entry(ProtocolVersion.MINECRAFT_1_21_4.id(), new Ids("1.21.4",
                    0x00, 0x03, 0x02, 0x03,
                    0x0E, 0x1A, 0x05, 0x00,
                    0x70, 0x42, 0x27, true, 0x0E, 0x07)),
            Map.entry(ProtocolVersion.MINECRAFT_1_21_5.id(), new Ids("1.21.5",
                    0x00, 0x03, 0x02, 0x03,
                    0x0E, 0x1A, 0x05, 0x00,
                    0x6F, 0x41, 0x26, true, 0x0E, 0x07)),
            Map.entry(ProtocolVersion.MINECRAFT_1_21_6.id(), new Ids("1.21.6",
                    0x00, 0x03, 0x02, 0x03,
                    0x0F, 0x1B, 0x06, 0x00,
                    0x6F, 0x41, 0x26, true, 0x0E, 0x07)),
            Map.entry(ProtocolVersion.MINECRAFT_1_21_7.id(), new Ids("1.21.8",
                    0x00, 0x03, 0x02, 0x03,
                    0x0F, 0x1B, 0x06, 0x00,
                    0x6F, 0x41, 0x26, true, 0x0E, 0x07)));

    /** Position, three doubles and two floats, then flags, then the teleport id. */
    private static final int POSITION_PREFIX_BYTES = 8 * 3 + 4 * 2 + 1;

    /** A keep-alive carries a bare long, which is necessary but nowhere near sufficient. */
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
        System.out.printf("Speaking %s (protocol %d)%s.%n", options.ids.version(), options.protocol,
                options.virtualHostOrNull == null ? "" : ", claiming host " + options.virtualHostOrNull);
        System.out.println("They speak the real protocol and then sit still: this measures "
                + "routing, not load.");

        List<FakePlayer> players = new ArrayList<>(options.count);
        AtomicInteger joined = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        CountDownLatch settled = new CountDownLatch(options.count);

        // A backstop for Ctrl+C, not the normal path. When --for runs its course the
        // players have already left by the time the JVM exits, and close() is idempotent
        // -- but announcing it twice reads as a bug in the tool.
        AtomicBoolean disconnected = new AtomicBoolean();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (disconnected.compareAndSet(false, true)) {
                System.out.println("\nDisconnecting.");
                players.forEach(FakePlayer::close);
            }
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
        if (options.holdSeconds > 0) {
            System.out.printf("Holding them online for %ds.%n", options.holdSeconds);
            Thread.sleep(options.holdSeconds * 1000);
            // Leaving under our own power, rather than waiting to be killed. A signal on
            // Windows is TerminateProcess, which skips shutdown hooks entirely -- so the
            // clean disconnect below is the only version that ever actually runs.
            disconnected.set(true);
            System.out.println("Disconnecting.");
            players.forEach(FakePlayer::close);
            System.out.printf("Received %.1f MB in %d frames.%n",
                    WIRE_BYTES.sum() / 1e6, WIRE_FRAMES.sum());
            return;
        }

        System.out.println("Holding them online. Check the dashboard or /glist, then Ctrl+C.");

        // Nothing else to do: the player threads keep the sockets alive, and the point of
        // the tool is that they stay put while somebody looks at the distribution.
        Thread.currentThread().join();
    }

    /** One connection, driven to play state and then held there. */
    private static final class FakePlayer {

        /** How long a leaving player waits for the proxy's side to finish and close. */
        private static final int DRAIN_TIMEOUT_MILLIS = 500;

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
        /** Learned once, then the only id ever answered. -1 until the first one arrives. */
        private int keepAliveId = -1;

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
            ProtocolUtils.writeVarInt(buf, options.protocol);
            // The address the client believes it connected to, which is not always where
            // the socket went. Relay routes on this (forced hosts), so a tool that always
            // sends the dialled IP cannot exercise that path at all.
            ProtocolUtils.writeString(buf, options.virtualHost());
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
            info.writeByte(options.ids.configClientInfo());
            ProtocolUtils.writeString(info, "en_gb");
            info.writeByte(8);                        // view distance
            ProtocolUtils.writeVarInt(info, 0);       // chat mode: enabled
            info.writeBoolean(true);                  // chat colours
            info.writeByte(0x7F);                     // every skin layer
            ProtocolUtils.writeVarInt(info, 1);       // main hand: right
            info.writeBoolean(false);                 // text filtering
            info.writeBoolean(true);                  // visible in server listings
            if (options.ids.clientInfoHasParticleStatus()) {
                ProtocolUtils.writeVarInt(info, 0);   // particle status: all
            }
            stream.write(info);

            while (true) {
                ByteBuf frame = stream.read();
                int id = ProtocolUtils.readVarInt(frame);
                if (id == options.ids.configDisconnect()) {
                    throw new Failure("refused while configuring: " + readReason(frame));
                }
                if (id == options.ids.knownPacksClientbound()) {
                    // The server offers its data packs and waits for the client's answer
                    // before sending registry data at all. An empty list is a legitimate
                    // reply -- it means "I know none of these, send everything" -- and it
                    // keeps this tool out of the business of parsing pack lists.
                    ByteBuf known = Unpooled.buffer();
                    known.writeByte(options.ids.knownPacksServerbound());
                    ProtocolUtils.writeVarInt(known, 0);
                    stream.write(known);
                    continue;
                }
                if (id == options.ids.configFinish()) {
                    stream.write(Unpooled.buffer().writeByte(options.ids.configFinishAck()));
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
            if (options.switchEvery > 0) {
                startSwitching(stream);
            }

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
                if (id == options.ids.startConfiguration()) {
                    stream.write(Unpooled.buffer().writeByte(options.ids.playConfigAck()));
                    configure(stream);
                    switches++;
                    if (traced) {
                        System.out.printf("  %6.1fs  moved to another backend (switch %d)%n",
                                elapsed / 1000.0, switches);
                    }
                    continue;
                }

                // The join teleport. Confirming it is not optional: until it is answered
                // the server holds the player unspawned, sends no keep-alives, and
                // eventually gives up on them.
                if (id == options.ids.position() && body > POSITION_PREFIX_BYTES) {
                    frame.skipBytes(POSITION_PREFIX_BYTES);
                    int teleportId = ProtocolUtils.readVarInt(frame);
                    ByteBuf confirm = Unpooled.buffer();
                    confirm.writeByte(options.ids.playAcceptTeleport());
                    ProtocolUtils.writeVarInt(confirm, teleportId);
                    synchronized (this) {
                        stream.write(confirm);
                    }
                    if (traced) {
                        System.out.printf("  %6.1fs  confirmed teleport %d%n",
                                elapsed / 1000.0, teleportId);
                    }
                    continue;
                }

                // Answering an unsolicited keep-alive is not a harmless mistake. Paper's
                // handler treats a response it did not ask for as a timeout and kicks --
                // which is what fake players were doing to themselves, answering entity
                // metadata that happened to be eight bytes, within a second of joining.
                boolean plausible = body == KEEP_ALIVE_BYTES
                        && options.keepAliveId >= 0
                        && id == options.keepAliveId;
                if (plausible) {
                    if (keepAliveId < 0) {
                        keepAliveId = id;
                        note("keep-alive is clientbound 0x" + Integer.toHexString(id)
                                + "; answering with serverbound 0x"
                                + Integer.toHexString(options.ids.playKeepAlive()));
                    }
                    ByteBuf pong = Unpooled.buffer();
                    pong.writeByte(options.ids.playKeepAlive());
                    pong.writeLong(frame.readLong());
                    stream.write(pong);
                }
            }
        }

        /**
         * Periodically asks to be moved, to keep the switch path under load.
         *
         * <p>Switching is where this proxy's hardest bug lived, and it only appeared when
         * the old backend was still sending play packets as the client changed state. A
         * crowd that joins and sits still never reproduces that; a crowd that keeps moving
         * does, on every one of them at once.
         *
         * <p>Each player picks its own interval around the requested one. Forty clients
         * switching in lockstep is a thundering herd, which is a different test and not
         * the one being asked for.
         */
        private void startSwitching(Stream stream) {
            Thread thread = new Thread(() -> {
                java.util.Random random = new java.util.Random();
                while (!closed) {
                    try {
                        long jitter = options.switchEvery / 2;
                        Thread.sleep(options.switchEvery + random.nextInt((int) Math.max(1, jitter)));
                        if (closed) {
                            return;
                        }
                        String destination = options.destinations.get(
                                random.nextInt(options.destinations.size()));
                        ByteBuf command = Unpooled.buffer();
                        command.writeByte(options.ids.playChatCommand());
                        ProtocolUtils.writeString(command, "server " + destination);
                        command.writeLong(System.currentTimeMillis());
                        command.writeLong(0L);            // salt
                        ProtocolUtils.writeVarInt(command, 0);   // no argument signatures
                        ProtocolUtils.writeVarInt(command, 0);   // no acknowledged messages
                        command.writeBytes(new byte[3]);         // acknowledged bitset
                        synchronized (this) {
                            stream.write(command);
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    } catch (IOException gone) {
                        return;
                    }
                }
            }, "switch-" + username);
            thread.setDaemon(true);
            thread.start();
        }

        /** One line per distinct observation, whatever the size of the crowd. */
        private void note(String message) {
            synchronized (FakePlayers.class) {
                if (REPORTED.add(message)) {
                    System.out.println("  " + message);
                }
            }
        }

        /**
         * Leaves the way a client should: shut down the sending half, then read to the end.
         *
         * <p>A plain {@code close()} on a socket that still has unread data waiting makes
         * TCP answer with a reset, and one of these always does &mdash; the proxy is
         * streaming world updates until the moment it learns the player is gone. That
         * reset surfaces on the proxy as {@code SocketException: Connection reset} logged
         * at ERROR, twenty at a time, which is how a run of this tool ends up burying the
         * errors somebody actually needs to see.
         *
         * <p>The same mistake in Relay's own detach path is what made backends log
         * {@code Connection reset by peer} on every {@code /server}. A tool that provokes
         * the bug it exists to detect is worse than no tool.
         */
        void close() {
            closed = true;
            Socket current = socket;
            if (current == null) {
                return;
            }
            try {
                current.shutdownOutput();
                // Drain what is still in flight so nothing is left unread. Bounded,
                // because a peer that never closes must not hold up the exit.
                current.setSoTimeout(DRAIN_TIMEOUT_MILLIS);
                byte[] discard = new byte[8192];
                long deadline = System.currentTimeMillis() + DRAIN_TIMEOUT_MILLIS;
                while (System.currentTimeMillis() < deadline
                        && current.getInputStream().read(discard) >= 0) {
                    // Reading it is the point; the content no longer matters.
                }
            } catch (IOException | RuntimeException ignored) {
                // Going away regardless, and every failure here means already gone.
            }
            try {
                current.close();
            } catch (IOException ignored) {
                // Going away regardless.
            }
        }
    }

    /** Bytes and frames every fake player has received, for benchmarking. */
    private static final java.util.concurrent.atomic.LongAdder WIRE_BYTES =
            new java.util.concurrent.atomic.LongAdder();
    private static final java.util.concurrent.atomic.LongAdder WIRE_FRAMES =
            new java.util.concurrent.atomic.LongAdder();

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
            // Counted so a run can say how much the proxy actually moved. Without a
            // denominator, "the proxy used 40 seconds of CPU" is not a measurement of
            // anything -- Paper decides how much traffic there is, and it varies.
            WIRE_BYTES.add(length);
            WIRE_FRAMES.increment();
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
                           boolean trace, long switchEvery, List<String> destinations,
                           int keepAliveId, long holdSeconds, int protocol, Ids ids,
                           String virtualHostOrNull) {

        /** What to put in the handshake: the forced host if given, else the dialled one. */
        String virtualHost() {
            return virtualHostOrNull == null ? host : virtualHostOrNull;
        }

        static Options parse(String[] args) {
            String host = "127.0.0.1";
            int port = 25565;
            int count = 20;
            long stagger = 150;
            String prefix = "Fake";
            boolean trace = false;
            long switchEvery = 0;
            List<String> destinations = new ArrayList<>();
            long holdSeconds = 0;
            int protocol = ProtocolVersion.MINECRAFT_1_20_2.id();
            // -1 means "whatever the table says for the chosen version"; an explicit
            // --keepalive overrides it, which is how a new version gets tried before its
            // row exists.
            int keepAliveId = -1;
            String virtualHost = null;

            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--host" -> host = args[++i];
                    case "--port" -> port = Integer.parseInt(args[++i]);
                    case "--count" -> count = Integer.parseInt(args[++i]);
                    case "--stagger" -> stagger = Long.parseLong(args[++i]);
                    case "--prefix" -> prefix = args[++i];
                    case "--trace" -> trace = true;
                    case "--switch" -> switchEvery = Long.parseLong(args[++i]);
                    case "--to" -> destinations.add(args[++i]);
                    case "--keepalive" -> keepAliveId = Integer.decode(args[++i]);
                    case "--for" -> holdSeconds = Long.parseLong(args[++i]);
                    case "--protocol" -> protocol = Integer.parseInt(args[++i]);
                    case "--virtual-host" -> virtualHost = args[++i];
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
                                  --switch <ms>      keep moving, roughly this often
                                  --to <name>        somewhere --switch may send them;
                                                     repeatable
                                  --protocol <n>     protocol version to speak, default
                                                     764 (1.20.2)
                                  --virtual-host <h> hostname to claim in the handshake,
                                                     for testing forced hosts. Defaults to
                                                     --host
                                  --keepalive <id>   clientbound keep-alive id, overriding
                                                     the table. -1 answers none
                                  --for <seconds>    leave cleanly after this long,
                                                     instead of holding until killed

                                The proxy must have online-mode = false: these cannot
                                authenticate with Mojang.""");
                        return null;
                    }
                }
            }
            if (switchEvery > 0 && destinations.isEmpty()) {
                System.out.println("--switch needs at least one --to <server|group>");
                return null;
            }

            Ids ids = IDS.get(protocol);
            if (ids == null) {
                ProtocolVersion version = ProtocolVersion.byId(protocol);
                System.out.printf("No verified packet ids for protocol %d%s.%n", protocol,
                        version == null ? "" : " (" + version.displayName() + ")");
                System.out.println("""
                          Relay may well speak this version; this tool has not been taught
                          to. The ids it needs -- keep-alive, start configuration, the join
                          teleport and its confirmation -- move whenever Mojang inserts a
                          packet, and guessing one produces a fake player that is kicked
                          within a second for reasons that look like a proxy bug.

                          Normally a version is added from Mojang's own packet report:

                            java -DbundlerMainClass=net.minecraft.data.Main \\
                                 -jar <server>.jar --reports

                          1.20.3 - 1.20.6 are the exception: Mojang added that report in
                          1.21, so those two need a live backend under
                          `py relay.py paper-debug <name>`, joined once with a real client
                          of that version, and the ids read out of its packet log.

                          Either way, add the row to IDS in this file. See
                          docs/protocol-ids.md.""");
                System.out.println("  verified so far: " + IDS.values().stream()
                        .map(Ids::version).sorted().toList());
                return null;
            }
            if (keepAliveId == -1) {
                keepAliveId = ids.keepAlive();
            }

            return new Options(host, port, count, stagger, prefix, trace, switchEvery,
                    List.copyOf(destinations), keepAliveId, holdSeconds, protocol, ids,
                    virtualHost);
        }
    }
}
