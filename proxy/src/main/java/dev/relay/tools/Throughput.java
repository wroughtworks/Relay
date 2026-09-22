package dev.relay.tools;

import dev.relay.config.ConfigLoader;
import dev.relay.protocol.ProtocolUtils;
import dev.relay.protocol.ProtocolVersion;
import dev.relay.proxy.RelayProxy;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

/**
 * Measures how fast Relay forwards, so a change to the hot path can be judged rather than
 * believed.
 *
 * <p>A synthetic backend blasts play frames as fast as the socket will take them; synthetic
 * clients read them through a real proxy and count bytes. Everything is in one process on
 * loopback, which is not a network and is not meant to be: the point is a repeatable number
 * on one machine, before and after a change, not a figure to quote.
 *
 * <h2>Why not JMH</h2>
 * The costs worth finding here are not in a method. They are flushes, syscalls, allocation
 * and garbage, and they only appear when real sockets and a real event loop are involved.
 * A microbenchmark of {@code VarintFrameDecoder} would measure the one part of this that
 * was already fast.
 *
 * <h2>Reading the result</h2>
 * The harness shares a process with the proxy, so the CPU figure covers both. Compare runs
 * against each other, never against another proxy. Throughput is the honest number: with
 * loopback able to move gigabytes a second, whatever this reports is the proxy.
 *
 * <pre>
 * py relay.py bench                    # 4 clients, 256-byte frames, 15s
 * py relay.py bench --frame 4096       # chunk-sized frames
 * </pre>
 */
public final class Throughput {

    private static final ProtocolVersion VERSION = ProtocolVersion.MINECRAFT_1_20_2;

    // The 1.20.2 ids, from the table FakePlayers documents and verifies.
    private static final int SB_LOGIN_ACK = 0x03;
    private static final int SB_CONFIG_CLIENT_INFO = 0x00;
    private static final int SB_CONFIG_FINISH_ACK = 0x02;
    private static final int CB_LOGIN_SUCCESS = 0x02;
    private static final int CB_CONFIG_REGISTRY = 0x05;
    private static final int CB_CONFIG_FINISH = 0x02;
    /** Any id Relay has no definition for, so the frame takes the opaque relay path. */
    private static final int CB_PLAY_OPAQUE = 0x24;

    private Throughput() {
    }

    public static void main(String[] args) throws Exception {
        int clients = intArg(args, "--clients", 4);
        int frameBytes = intArg(args, "--frame", 256);
        int warmupSeconds = intArg(args, "--warmup", 5);
        int measureSeconds = intArg(args, "--seconds", 15);
        // Connects the clients straight to the synthetic backend, with no proxy in the
        // middle. That is the harness's own ceiling, and the only way to know whether a
        // number measures Relay or measures this file.
        boolean direct = List.of(args).contains("--direct");
        // Virtual threads, so thousands of blocking clients cost what thousands of
        // connections cost rather than what thousands of OS threads cost. A proxy for a
        // large network carries thousands per node; sixteen says nothing about that.
        boolean virtual = List.of(args).contains("--virtual");
        boolean flushBatching = List.of(args).contains("--flush-batching");

        System.out.printf("Relay throughput: %d client(s), %d-byte frames, %ds warmup, %ds measured%n",
                clients, frameBytes, warmupSeconds, measureSeconds);
        System.out.println("Loopback, one process. Compare runs with each other, not with anything else.");
        System.out.println();

        Path dir = Files.createTempDirectory("relay-bench");
        int backendPort = freePort();
        int proxyPort = freePort();
        writeConfig(dir, proxyPort, backendPort, flushBatching);

        AtomicBoolean running = new AtomicBoolean(true);
        LongAdder bytesRead = new LongAdder();
        LongAdder framesRead = new LongAdder();

        Backend backend = new Backend(backendPort, frameBytes, running, virtual);
        backend.start();

        RelayProxy proxy = null;
        if (!direct) {
            proxy = new RelayProxy(ConfigLoader.load(dir.resolve("relay.toml")));
            proxy.start();
        } else {
            System.out.println("DIRECT: no proxy in the path. This is the harness ceiling.");
        }
        int connectPort = direct ? backendPort : proxyPort;

        List<Client> readers = new ArrayList<>();
        CountDownLatch ready = new CountDownLatch(clients);
        for (int i = 0; i < clients; i++) {
            Client client = new Client(connectPort, "Bench" + i, running, bytesRead, framesRead,
                    ready, virtual);
            client.start();
            readers.add(client);
            // A thousand simultaneous connects overruns any accept queue. Real players
            // do not arrive in the same microsecond either.
            if (clients > 64 && i % 32 == 31) {
                Thread.sleep(20);
            }
        }
        if (!ready.await(30, TimeUnit.SECONDS)) {
            System.out.println("Clients did not all reach play state; aborting.");
            running.set(false);
            if (proxy != null) {
                proxy.shutdown();
            }
            return;
        }

        System.out.printf("Warming up for %ds...%n", warmupSeconds);
        Thread.sleep(warmupSeconds * 1000L);

        // Counters are read, not reset: resetting races with the readers, and a
        // subtraction over a known interval is exact where a reset is approximate.
        long startBytes = bytesRead.sum();
        long startFrames = framesRead.sum();
        long startCpu = cpuNanos();
        long startAt = System.nanoTime();

        System.out.printf("Measuring for %ds...%n", measureSeconds);
        Thread.sleep(measureSeconds * 1000L);

        long elapsed = System.nanoTime() - startAt;
        long bytes = bytesRead.sum() - startBytes;
        long frames = framesRead.sum() - startFrames;
        long cpu = cpuNanos() - startCpu;

        running.set(false);
        for (Client client : readers) {
            client.close();
        }
        backend.close();
        if (proxy != null) {
            proxy.shutdown();
        }

        double seconds = elapsed / 1e9;
        double megabytes = bytes / 1e6;
        System.out.println();
        System.out.println("  throughput   " + round(megabytes / seconds) + " MB/s");
        System.out.println("  frames       " + round(frames / seconds / 1000) + "k/s");
        System.out.println("  per frame    " + round(cpu / (double) Math.max(1, frames)) + " ns of CPU");
        System.out.println("  per MB       " + round(cpu / 1e6 / Math.max(0.001, megabytes)) + " ms of CPU");
        System.out.println();
        System.out.println("CPU covers the harness as well as the proxy; the comparison is what counts.");
        System.exit(0);
    }

    private static long cpuNanos() {
        java.lang.management.OperatingSystemMXBean bean =
                java.lang.management.ManagementFactory.getOperatingSystemMXBean();
        return bean instanceof com.sun.management.OperatingSystemMXBean sun
                ? sun.getProcessCpuTime() : 0;
    }

    private static String round(double value) {
        return String.format("%.2f", value);
    }

    /**
     * The proxy under test, configured to do as little besides forwarding as possible.
     *
     * <p>Health checks, storage, the control channel and companions are all off. Each of
     * them is a thread doing periodic work, and the point of this is to measure the path a
     * packet takes, not the background.
     */
    private static void writeConfig(Path dir, int proxyPort, int backendPort,
                                    boolean flushBatching) throws IOException {
        Files.writeString(dir.resolve("relay.toml"), """
                bind = "127.0.0.1:%d"
                motd = "bench"
                online-mode = false
                forwarding-mode = "none"
                compression-threshold = -1
                max-players = 20000
                health.enabled = false
                control.enabled = false
                storage.enabled = false
                trace-closes = false
                flush-batching = %s
                try = ["bench"]

                [servers]
                bench = "127.0.0.1:%d"
                """.formatted(proxyPort, flushBatching, backendPort));
    }

    // ------------------------------------------------------------------- the backend

    /** Completes the handshake for each connection, then writes frames without pause. */
    private static final class Backend {

        private final ServerSocket socket;
        private final int frameBytes;
        private final AtomicBoolean running;
        private final boolean virtual;

        Backend(int port, int frameBytes, AtomicBoolean running, boolean virtual) throws IOException {
            this.virtual = virtual;
            this.socket = new ServerSocket();
            // A thousand clients connect at once. The default backlog is 50, and the
            // rest are refused before anything is measured.
            this.socket.bind(new InetSocketAddress("127.0.0.1", port), 8192);
            this.frameBytes = frameBytes;
            this.running = running;
        }

        void start() {
            Thread accepting = new Thread(() -> {
                while (running.get()) {
                    try {
                        Socket connection = socket.accept();
                        connection.setTcpNoDelay(true);
                        if (virtual) {
                            Thread.ofVirtual().start(() -> serve(connection));
                        } else {
                            Thread serve = new Thread(() -> serve(connection), "bench-backend-serve");
                            serve.setDaemon(true);
                            serve.start();
                        }
                    } catch (IOException stopping) {
                        return;
                    }
                }
            }, "bench-backend");
            accepting.setDaemon(true);
            accepting.start();
        }

        private void serve(Socket connection) {
            try (connection) {
                InputStream in = connection.getInputStream();
                OutputStream out = connection.getOutputStream();

                read(in).release();                              // handshake
                ByteBuf loginStart = read(in);
                ProtocolUtils.readVarInt(loginStart);
                String username = ProtocolUtils.readString(loginStart);
                UUID uuid = ProtocolUtils.readUuid(loginStart);
                loginStart.release();

                ByteBuf success = Unpooled.buffer();
                success.writeByte(CB_LOGIN_SUCCESS);
                ProtocolUtils.writeUuid(success, uuid);
                ProtocolUtils.writeString(success, username);
                ProtocolUtils.writeVarInt(success, 0);
                write(out, success);

                read(in).release();                              // login acknowledged

                ByteBuf registry = Unpooled.buffer();
                registry.writeByte(CB_CONFIG_REGISTRY);
                registry.writeBytes(new byte[64]);
                write(out, registry);
                write(out, Unpooled.buffer().writeByte(CB_CONFIG_FINISH));

                read(in).release();                              // finish configuration ack

                // One frame, built once. Rebuilding it per write would measure this
                // harness allocating rather than the proxy forwarding.
                byte[] payload = new byte[frameBytes];
                ByteBuf frame = Unpooled.buffer();
                frame.writeByte(CB_PLAY_OPAQUE);
                frame.writeBytes(payload);
                byte[] framed = framed(frame);

                while (running.get()) {
                    out.write(framed);
                }
            } catch (IOException stopping) {
                // The client went away, or the run ended. Either way there is nothing to say.
            }
        }

        void close() throws IOException {
            socket.close();
        }
    }

    // -------------------------------------------------------------------- the client

    /** Reaches play state, then reads and counts for the rest of the run. */
    private static final class Client {

        private final int port;
        private final String username;
        private final AtomicBoolean running;
        private final LongAdder bytes;
        private final LongAdder frames;
        private final CountDownLatch ready;
        private final boolean virtual;
        private volatile Socket socket;

        Client(int port, String username, AtomicBoolean running,
               LongAdder bytes, LongAdder frames, CountDownLatch ready, boolean virtual) {
            this.port = port;
            this.username = username;
            this.running = running;
            this.bytes = bytes;
            this.frames = frames;
            this.ready = ready;
            this.virtual = virtual;
        }

        void start() {
            if (virtual) {
                Thread.ofVirtual().name("bench-client-" + username).start(this::run);
                return;
            }
            Thread thread = new Thread(this::run, "bench-client-" + username);
            thread.setDaemon(true);
            thread.start();
        }

        private void run() {
            try (Socket connection = new Socket()) {
                socket = connection;
                connection.connect(new InetSocketAddress("127.0.0.1", port), 10_000);
                connection.setTcpNoDelay(true);
                InputStream in = connection.getInputStream();
                OutputStream out = connection.getOutputStream();

                ByteBuf handshake = Unpooled.buffer();
                handshake.writeByte(0x00);
                ProtocolUtils.writeVarInt(handshake, VERSION.id());
                ProtocolUtils.writeString(handshake, "127.0.0.1");
                handshake.writeShort(port);
                ProtocolUtils.writeVarInt(handshake, 2);
                write(out, handshake);

                ByteBuf loginStart = Unpooled.buffer();
                loginStart.writeByte(0x00);
                ProtocolUtils.writeString(loginStart, username);
                ProtocolUtils.writeUuid(loginStart, UUID.randomUUID());
                write(out, loginStart);

                readUntil(in, CB_LOGIN_SUCCESS);
                write(out, Unpooled.buffer().writeByte(SB_LOGIN_ACK));

                ByteBuf info = Unpooled.buffer();
                info.writeByte(SB_CONFIG_CLIENT_INFO);
                ProtocolUtils.writeString(info, "en_gb");
                info.writeByte(8);
                ProtocolUtils.writeVarInt(info, 0);
                info.writeBoolean(true);
                info.writeByte(0x7F);
                ProtocolUtils.writeVarInt(info, 1);
                info.writeBoolean(false);
                info.writeBoolean(true);
                write(out, info);

                readUntil(in, CB_CONFIG_FINISH);
                write(out, Unpooled.buffer().writeByte(SB_CONFIG_FINISH_ACK));
                ready.countDown();

                // Counting, not parsing. Reading the frame length and skipping the body is
                // what a client's decoder costs at minimum, and anything more would be
                // measuring this loop.
                DataInputStream data = new DataInputStream(in);
                byte[] scratch = new byte[64 * 1024];
                while (running.get()) {
                    int length = readVarInt(data);
                    if (length < 0) {
                        return;
                    }
                    int remaining = length;
                    while (remaining > 0) {
                        int read = data.read(scratch, 0, Math.min(remaining, scratch.length));
                        if (read < 0) {
                            return;
                        }
                        remaining -= read;
                    }
                    bytes.add(length);
                    frames.increment();
                }
            } catch (IOException stopping) {
                // Expected at the end of a run.
            } finally {
                ready.countDown();
            }
        }

        void close() throws IOException {
            Socket current = socket;
            if (current != null) {
                current.close();
            }
        }
    }

    // -------------------------------------------------------------------- framing

    private static byte[] framed(ByteBuf payload) {
        ByteBuf out = Unpooled.buffer();
        ProtocolUtils.writeVarInt(out, payload.readableBytes());
        out.writeBytes(payload);
        byte[] bytes = new byte[out.readableBytes()];
        out.readBytes(bytes);
        return bytes;
    }

    private static void write(OutputStream out, ByteBuf payload) throws IOException {
        out.write(framed(payload));
        out.flush();
    }

    private static ByteBuf read(InputStream in) throws IOException {
        DataInputStream data = new DataInputStream(in);
        int length = readVarInt(data);
        if (length < 0) {
            throw new IOException("stream ended");
        }
        byte[] payload = new byte[length];
        data.readFully(payload);
        return Unpooled.wrappedBuffer(payload);
    }

    private static void readUntil(InputStream in, int id) throws IOException {
        for (int i = 0; i < 500; i++) {
            ByteBuf frame = read(in);
            try {
                if (ProtocolUtils.readVarInt(frame.duplicate()) == id) {
                    return;
                }
            } finally {
                frame.release();
            }
        }
        throw new IOException("never saw packet 0x" + Integer.toHexString(id));
    }

    private static int readVarInt(DataInputStream in) throws IOException {
        int value = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            int b;
            try {
                b = in.readByte() & 0xFF;
            } catch (IOException ended) {
                return -1;
            }
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
        }
        throw new IOException("VarInt too long");
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress("127.0.0.1", 0));
            return socket.getLocalPort();
        }
    }

    private static int intArg(String[] args, String name, int fallback) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(name)) {
                return Integer.parseInt(args[i + 1]);
            }
        }
        return fallback;
    }
}
