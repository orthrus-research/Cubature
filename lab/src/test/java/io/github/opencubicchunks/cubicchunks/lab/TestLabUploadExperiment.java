package io.github.opencubicchunks.cubicchunks.lab;

import org.junit.Test;
import org.lwjgl.opengl.GL15;
import java.nio.ByteBuffer;
import static org.junit.Assert.*;

public class TestLabUploadExperiment {
    @Test public void orphanAndSubdataUploadEveryByteInTheOriginalWindowWithoutChangingIt() {
        ByteBuffer data = ByteBuffer.allocateDirect(10);
        for (int i = 0; i < data.capacity(); i++) data.put((byte) (i * 7));
        data.position(2).limit(8);
        Fake backend = new Fake();
        assertTrue(LabUploadExperiment.upload(backend, GL15.GL_ARRAY_BUFFER, data, GL15.GL_STATIC_DRAW));
        assertEquals(6, backend.allocated); assertEquals(GL15.GL_STATIC_DRAW, backend.usage);
        assertEquals(GL15.GL_ARRAY_BUFFER, backend.target);
        assertArrayEquals(new byte[] {14, 21, 28, 35, 42, 49}, backend.bytes);
        assertEquals(2, data.position()); assertEquals(8, data.limit());
    }

    @Test public void unsupportedCapabilityOrTargetLeavesTheOriginalUploadToTheCaller() {
        Fake backend = new Fake(); backend.supported = false;
        assertFalse(LabUploadExperiment.upload(backend, GL15.GL_ARRAY_BUFFER, ByteBuffer.allocateDirect(4), GL15.GL_STATIC_DRAW));
        backend.supported = true;
        assertFalse(LabUploadExperiment.upload(backend, -1, ByteBuffer.allocateDirect(4), GL15.GL_STATIC_DRAW));
        assertNull(backend.bytes); assertEquals(0, backend.calls);
    }

    private static final class Fake implements LabUploadExperiment.Backend {
        boolean supported = true;
        int target, usage, allocated, calls;
        byte[] bytes;
        public boolean supported() { return supported; }
        public void allocate(int target, int size, int usage) { this.target = target; this.usage = usage; allocated = size; calls++; }
        public void write(int target, ByteBuffer data) {
            assertEquals(this.target, target); assertEquals(1, calls);
            bytes = new byte[data.remaining()]; data.duplicate().get(bytes); calls++;
        }
    }
}
