package io.github.opencubicchunks.cubicchunks.lab;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.opencubicchunks.cubicchunks.core.server.TickDiagnostics;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.Mixin;
import static org.junit.Assert.*;

/** Runs production wrapper bytecode without starting Minecraft or the LaunchWrapper transformer. */
public class TestLabTickWrapperOrder {
    private static final String CORE = "io/github/opencubicchunks/cubicchunks/core/asm/mixin/core/common/MixinServerTickCosts";
    private static final String LAB = "io/github/opencubicchunks/cubicchunks/lab/mixin/MixinCompleteServerTick";
    private static final String CALLBACKS = "io/github/opencubicchunks/cubicchunks/lab/CubicLab";

    @Test public void composedProductionWrappersKeepFirstAndFinalRowsBeforeFreezing() throws Exception {
        Capture capture = run(null, false);
        assertComplete(capture, 3);
        assertEquals(3L, capture.frozen.get("installedCubes"));
        assertEquals(1L, capture.frozen.get("emptyCubes"));
    }

    @Test public void previousDefaultLabPriorityLosesTheFinalRowAndReversesEnclosure() throws Exception {
        Capture capture = run(defaultPriority(), false);
        assertEquals(3, capture.complete.size());
        assertEquals(2, capture.samples.size());
        assertEquals(2L, capture.frozen.get("ticks"));
        assertTrue("Wrong outer core timer must include lab completion work",
                capture.samples.get(0).elapsedNanos() > capture.complete.get(0));
    }

    @Test public void exceptionalFinalTickStillRecordsItsCoreRowBeforeFailureCompletion() throws Exception {
        Capture capture = run(null, true);
        assertComplete(capture, 3);
        assertFalse(capture.normalReturn);
    }

    private static void assertComplete(Capture capture, int count) {
        assertEquals("Every complete tick needs its same-tick core row", count, capture.samples.size());
        assertEquals(count, capture.complete.size());
        assertEquals((long) count, capture.frozen.get("ticks"));
        assertEquals(capture.frozen.get("tickTotalNanos"), capture.frozen.get("phaseTotalNanos"));
        for (int i = 0; i < count; i++) {
            TickDiagnostics.Sample sample = capture.samples.get(i);
            assertEquals("First and final tick serials must survive", i + 1L, sample.tick());
            assertEquals(sample.elapsedNanos(), Arrays.stream(sample.phaseNanos()).sum());
            assertTrue("Core tick must be contained in the independently timed lab tick " + sample.tick(),
                    sample.elapsedNanos() <= capture.complete.get(i));
        }
    }

    private static Capture run(Integer labPriority, boolean failLast) throws Exception {
        Wrapper core = wrapper(CORE, "core"), lab = wrapper(LAB, "lab");
        if (labPriority != null) lab.priority = labPriority;
        Object receiver = receiver(core, lab);
        List<Wrapper> applied = new ArrayList<>(List.of(core, lab));
        // CleanMix 0.7.4 MixinInfo.compareTo sorts ascending; MixinExtras 0.5.5 offerWrapper
        // encloses the previous stage, so the later, higher-priority wrapper becomes outermost.
        applied.sort(Comparator.comparingInt(value -> value.priority));
        Capture capture = new Capture();
        TickDiagnostics.reset();
        TickDiagnostics.Observer previous = TickDiagnostics.setObserver(sample -> {
            if (Fixture.active != null) {
                Fixture.active.samples.add(sample);
                Fixture.active.costs.record(sample);
            }
        });
        try {
            Fixture.active = null;
            for (int tick = 1; tick <= 3; tick++) {
                final int ordinal = tick;
                Operation<Void> operation = ignored -> {
                    if (ordinal == 1) Fixture.active = capture; // Capture starts inside its first tick.
                    TickDiagnostics.enter(TickDiagnostics.Phase.CUBE_CONSTRUCT);
                    try { TickDiagnostics.installedCube(ordinal == 1); } finally { TickDiagnostics.exit(); }
                    if (ordinal == 3) {
                        capture.done = true;
                        if (failLast) throw new IllegalStateException("final tick failed");
                    }
                    return null;
                };
                for (Wrapper wrapper : applied) {
                    Method method = receiver.getClass().getMethod(wrapper.name, Operation.class);
                    Operation<Void> inner = operation;
                    operation = ignored -> invoke(method, receiver, inner);
                }
                try { operation.call(); }
                catch (IllegalStateException error) {
                    if (!failLast || ordinal != 3 || !"final tick failed".equals(error.getMessage())) throw error;
                }
            }
            assertNull("Completed capture must be detached", Fixture.active);
            assertNotNull("Final completion must freeze a summary", capture.frozen);
            return capture;
        } finally {
            Fixture.active = null;
            TickDiagnostics.setObserver(previous);
            TickDiagnostics.reset();
        }
    }

    private static Void invoke(Method method, Object receiver, Operation<Void> operation) {
        try { method.invoke(receiver, operation); return null; }
        catch (InvocationTargetException error) {
            if (error.getCause() instanceof RuntimeException failure) throw failure;
            if (error.getCause() instanceof Error failure) throw failure;
            throw new AssertionError(error.getCause());
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private static int defaultPriority() throws Exception {
        return (Integer) Mixin.class.getMethod("priority").getDefaultValue();
    }

    private static Wrapper wrapper(String resource, String name) throws Exception {
        ClassNode type = new ClassNode();
        try (InputStream stream = TestLabTickWrapperOrder.class.getClassLoader().getResourceAsStream(resource + ".class")) {
            assertNotNull("Production wrapper must be present: " + resource, stream);
            new ClassReader(stream).accept(type, 0);
        }
        int priority = defaultPriority();
        List<AnnotationNode> annotations = new ArrayList<>();
        if (type.visibleAnnotations != null) annotations.addAll(type.visibleAnnotations);
        if (type.invisibleAnnotations != null) annotations.addAll(type.invisibleAnnotations);
        for (AnnotationNode annotation : annotations)
            if (annotation.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;"))
                for (int i = 0; i < annotation.values.size(); i += 2)
                    if (annotation.values.get(i).equals("priority")) priority = (Integer) annotation.values.get(i + 1);
        for (MethodNode method : type.methods)
            if (method.desc.equals("(Lcom/llamalad7/mixinextras/injector/wrapoperation/Operation;)V"))
                return new Wrapper(name, priority, method);
        throw new AssertionError("Production tick wrapper not found: " + resource);
    }

    private static Object receiver(Wrapper... wrappers) throws Exception {
        String name = "io/github/opencubicchunks/cubicchunks/lab/ComposedTickWrappers";
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode(); constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN); constructor.visitMaxs(0, 0); constructor.visitEnd();
        for (Wrapper wrapper : wrappers) {
            for (var instruction : wrapper.body.instructions)
                if (instruction instanceof MethodInsnNode call && call.owner.equals(CALLBACKS))
                    call.owner = Fixture.class.getName().replace('.', '/');
            wrapper.body.accept(writer.visitMethod(Opcodes.ACC_PUBLIC, wrapper.name, wrapper.body.desc, null, null));
        }
        writer.visitEnd();
        byte[] bytes = writer.toByteArray();
        class Loader extends ClassLoader {
            Loader() { super(TestLabTickWrapperOrder.class.getClassLoader()); }
            Class<?> define() { return defineClass(name.replace('/', '.'), bytes, 0, bytes.length); }
        }
        return new Loader().define().getConstructor().newInstance();
    }

    private static final class Wrapper {
        final String name; final MethodNode body; int priority;
        Wrapper(String name, int priority, MethodNode body) { this.name = name; this.priority = priority; this.body = body; }
    }

    private static final class Capture {
        final LabTickCosts costs = new LabTickCosts(4);
        final List<TickDiagnostics.Sample> samples = new ArrayList<>();
        final List<Long> complete = new ArrayList<>();
        Map<String, Object> frozen;
        boolean done, normalReturn;
    }

    /** Only world-owning callbacks are faked; wrapper bodies, priorities, recorder and rows are real. */
    public static final class Fixture {
        private static Capture active;
        public static Object serverTickObserver() { return active; }
        public static void serverTickCompleted(Object observer, long duration, boolean normalReturn) {
            if (active == null || (observer != null && observer != active)) return;
            Capture capture = active;
            capture.complete.add(duration);
            capture.normalReturn = normalReturn;
            if (capture.done || !normalReturn) { capture.frozen = capture.costs.summary(); active = null; }
            // Model serialization/finalization outside the lab timer; wrong nesting charges it to core.
            long until = System.nanoTime() + 100_000;
            while (System.nanoTime() < until) Thread.onSpinWait();
        }
    }
}
