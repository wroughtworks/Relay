package dev.relay.arch;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Reads compiled classes and finds methods that add a listener to a promise they were
 * handed, without ever asking whether it is a void one.
 *
 * <h2>The bug this exists for</h2>
 * Netty has <em>void promises</em>: a promise that cannot carry a listener, used to avoid
 * allocating one for a write nobody is waiting on. {@code addListener} on one throws
 * {@code IllegalStateException("void future")}.
 *
 * <p>Paper sends play packets that way from 1.21 onwards. Relay's own Paper plugin
 * attached a listener to every write, so it threw on the first packet after a player
 * joined, the exception reached {@code Connection.exceptionCaught}, and Paper disconnected
 * them. Every player, every join, on every 1.21 backend &mdash; and invisible on 1.20.2,
 * where those promises are real. A diagnostic that caused the fault it existed to observe.
 *
 * <p>It was fixed by a guard and a unit test. The test covers one class. This covers every
 * class in the build, including ones nobody has written yet, which is the difference
 * between fixing a bug and closing the shape of it.
 *
 * <h2>The rule, and why it is this rule</h2>
 * <b>A method that receives a {@code ChannelPromise} and calls {@code addListener} must
 * also call {@code isVoid}.</b>
 *
 * <p>That is a heuristic, not a proof. It does not check that the {@code isVoid} guards
 * that particular {@code addListener}, or that it guards anything at all &mdash; proving
 * so means dataflow analysis over the method body, which is a great deal of machinery for
 * a rule whose real job is to make somebody stop and think. What it does guarantee is that
 * nobody attaches a listener to a promise in this codebase without the words "void
 * promise" having crossed their mind.
 *
 * <p>It is deliberately about the method's <em>parameters</em> rather than about handlers
 * or pipelines. The danger is not being a {@code ChannelDuplexHandler}; it is being handed
 * a promise somebody else made, whose voidness is not yours to know.
 */
final class PromiseUse {

    private static final String PROMISE = "io/netty/channel/ChannelPromise";

    private PromiseUse() {
    }

    /**
     * @param className  binary name, e.g. {@code dev.relay.net.pipeline.TrafficCounter}
     * @param method     name and descriptor, so two overloads are told apart
     */
    record Finding(String className, String method) {

        @Override
        public String toString() {
            return className + "#" + method;
        }
    }

    /** What a scan saw, so a test can assert it actually looked at something. */
    record Scan(List<Finding> unguarded, int classesRead, int methodsTakingPromise) {
    }

    /**
     * Walks every {@code .class} under {@code root}.
     *
     * @throws IOException if the tree cannot be read. Never silently empty: a rule that
     *                     passes because it found nothing to check is worse than no rule,
     *                     and this project has shipped one of those before
     */
    static Scan scan(Path root) throws IOException {
        List<Finding> unguarded = new ArrayList<>();
        int[] counts = new int[2];

        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".class")).toList()) {
                try (InputStream in = Files.newInputStream(file)) {
                    counts[0]++;
                    new ClassReader(in).accept(
                            new Visitor(unguarded, counts), ClassReader.SKIP_FRAMES);
                }
            }
        }
        return new Scan(List.copyOf(unguarded), counts[0], counts[1]);
    }

    private static final class Visitor extends ClassVisitor {

        private final List<Finding> unguarded;
        private final int[] counts;
        private String className;

        Visitor(List<Finding> unguarded, int[] counts) {
            super(Opcodes.ASM9);
            this.unguarded = unguarded;
            this.counts = counts;
        }

        @Override
        public void visit(int version, int access, String name, String signature,
                          String superName, String[] interfaces) {
            this.className = name.replace('/', '.');
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                         String signature, String[] exceptions) {
            if (!takesPromise(descriptor)) {
                return null;                       // nothing here can break the rule
            }
            counts[1]++;
            return new Body(className, name + descriptor, unguarded);
        }

        private static boolean takesPromise(String descriptor) {
            for (Type argument : Type.getArgumentTypes(descriptor)) {
                if (argument.getSort() == Type.OBJECT
                        && argument.getInternalName().equals(PROMISE)) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * One method body, watched for the two calls that matter.
     *
     * <p>Matched on method <em>name</em> rather than on owner and descriptor. The owner of
     * an {@code addListener} call varies with the static type at the call site &mdash;
     * {@code ChannelPromise}, {@code ChannelFuture}, {@code Future} &mdash; and pinning
     * the rule to one of them is how a check quietly stops covering the case it was
     * written for.
     */
    private static final class Body extends MethodVisitor {

        private final String className;
        private final String method;
        private final List<Finding> unguarded;

        private boolean addsListener;
        private boolean asksIfVoid;

        Body(String className, String method, List<Finding> unguarded) {
            super(Opcodes.ASM9);
            this.className = className;
            this.method = method;
            this.unguarded = unguarded;
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name,
                                    String descriptor, boolean isInterface) {
            if (name.equals("addListener") || name.equals("addListeners")) {
                addsListener = true;
            }
            if (name.equals("isVoid")) {
                asksIfVoid = true;
            }
        }

        @Override
        public void visitEnd() {
            if (addsListener && !asksIfVoid) {
                unguarded.add(new Finding(className, method));
            }
        }
    }
}
