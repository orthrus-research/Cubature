/*
 * This file is part of Cubic Chunks Mod, licensed under the MIT License (MIT).
 * Copyright (c) 2015-2021 OpenCubicChunks and contributors.
 * See license.txt for the full license text.
 */
package io.github.opencubicchunks.cubicchunks.core.server.chunkio.region;

import cubicchunks.regionlib.MultiUnsupportedDataException;
import cubicchunks.regionlib.impl.EntryLocation2D;
import cubicchunks.regionlib.impl.save.SaveSection2D;
import cubicchunks.regionlib.lib.ExtRegion;
import cubicchunks.regionlib.lib.provider.SharedCachedRegionProvider;
import cubicchunks.regionlib.lib.provider.SimpleRegionProvider;
import cubicchunks.regionlib.util.CorruptedDataException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;
import static org.junit.Assert.*;

/** Exercises the actual region format and RegionLib fallback without a Minecraft world. */
public class TestShadowPagingRegion {
    private static final int SECTOR_SIZE = 512;
    private static final EntryLocation2D KEY = new EntryLocation2D(0, 0);

    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void partialReadsAdvanceTheFileOffsetAndRespectBufferBounds() throws IOException {
        byte[] source = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
        ByteBuffer target = ByteBuffer.allocate(9);
        Arrays.fill(target.array(), (byte) -1);
        target.position(2);
        target.limit(8);
        try (FileChannel channel = new ShortReadChannel(source, 2)) {
            ShadowPagingRegion.readFully(channel, target, 3);
        }
        assertEquals(8, target.position());
        assertArrayEquals(new byte[] {-1, -1, 3, 4, 5, 6, 7, 8, -1}, target.array());
    }

    @Test public void truncatedReadThrowsEofWithoutRetryingForever() throws IOException {
        try (FileChannel channel = new ShortReadChannel(new byte[] {1, 2}, 2)) {
            try {
                ShadowPagingRegion.readFully(channel, ByteBuffer.allocate(4), 0);
                fail("A truncated read must fail");
            } catch (EOFException expected) {
                assertTrue(expected.getMessage().contains("2 bytes remain"));
            }
        }
    }

    @Test public void readWithoutProgressFailsInsteadOfSpinning() throws IOException {
        try (FileChannel channel = new ShortReadChannel(new byte[] {1}, 0)) {
            try {
                ShadowPagingRegion.readFully(channel, ByteBuffer.allocate(1), 0);
                fail("A stalled read must fail");
            } catch (IOException expected) {
                assertTrue(expected.getMessage().contains("no progress"));
            }
        }
    }

    @Test public void emptyDestinationDoesNotReadTheChannel() throws IOException {
        ShortReadChannel channel = new ShortReadChannel(new byte[0], 0);
        try (FileChannel ignored = channel) {
            ShadowPagingRegion.readFully(channel, ByteBuffer.allocate(0), 0);
            assertEquals(0, channel.calls);
        }
    }

    @Test public void negativePayloadLengthIsReportedAsCorruption() throws IOException {
        assertInvalidLength(-1);
    }

    @Test public void payloadCannotOverlapTheNextSector() throws IOException {
        assertInvalidLength(SECTOR_SIZE - Integer.BYTES + 1);
    }

    @Test public void payloadExactlyFillingOneSectorRoundTrips() throws IOException {
        Path directory = temporary.newFolder().toPath();
        byte[] payload = new byte[SECTOR_SIZE - Integer.BYTES];
        Arrays.fill(payload, (byte) 73);
        try (ShadowPagingRegion<EntryLocation2D> region = open(directory)) {
            region.writeValues(Collections.singletonMap(KEY, ByteBuffer.wrap(payload)));
        }
        try (ShadowPagingRegion<EntryLocation2D> region = open(directory)) {
            assertArrayEquals(payload, region.readValue(KEY).get().array());
        }
    }

    @Test public void oversizedEntryKeepsItsOldValueAndCommitsSupportedEntries() throws IOException {
        Path directory = temporary.newFolder().toPath();
        EntryLocation2D supported = new EntryLocation2D(1, 0);
        byte[] previous = {4, 5, 6};
        byte[] normal = {7, 8, 9};
        ByteBuffer oversized = ByteBuffer.allocate(255 * SECTOR_SIZE);
        try (ShadowPagingRegion<EntryLocation2D> region = open(directory)) {
            region.writeValues(Collections.singletonMap(KEY, ByteBuffer.wrap(previous)));
            Map<EntryLocation2D, ByteBuffer> batch = new LinkedHashMap<>();
            batch.put(KEY, oversized);
            batch.put(supported, ByteBuffer.wrap(normal));
            try {
                region.writeValues(batch);
                fail("Oversized entries must be returned to the fallback provider");
            } catch (MultiUnsupportedDataException expected) {
                assertEquals(Collections.singleton(KEY), expected.getChildren().keySet());
            }
            assertEquals(0, oversized.position());
            assertArrayEquals(previous, region.readValue(KEY).get().array());
            assertArrayEquals(normal, region.readValue(supported).get().array());
        }
        try (ShadowPagingRegion<EntryLocation2D> region = open(directory)) {
            assertArrayEquals(previous, region.readValue(KEY).get().array());
            assertArrayEquals(normal, region.readValue(supported).get().array());
        }
    }

    @Test public void regionLibWritesOversizedEntriesToExternalStorageAndReloadsThem() throws IOException {
        Path directory = temporary.newFolder().toPath();
        byte[] payload = new byte[255 * SECTOR_SIZE];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) i;
        EntryLocation2D normalKey = new EntryLocation2D(1, 0);
        byte[] normal = {10, 11, 12};
        try (SaveSection2D section = openSection(directory)) {
            Map<EntryLocation2D, ByteBuffer> batch = new LinkedHashMap<>();
            batch.put(KEY, ByteBuffer.wrap(payload));
            batch.put(normalKey, ByteBuffer.wrap(normal));
            section.save(batch);
        }
        assertTrue(Files.exists(directory.resolve(KEY.getRegionKey().getName() + ".ext")));
        try (SaveSection2D section = openSection(directory)) {
            assertArrayEquals(payload, section.load(KEY, true).get().array());
            assertArrayEquals(normal, section.load(normalKey, true).get().array());
        }
    }

    private void assertInvalidLength(int length) throws IOException {
        Path directory = temporary.newFolder().toPath();
        try (ShadowPagingRegion<EntryLocation2D> region = open(directory)) {
            region.writeValues(Collections.singletonMap(KEY, ByteBuffer.wrap(new byte[] {1})));
        }
        Path regionFile = directory.resolve(KEY.getRegionKey().getName());
        try (FileChannel file = FileChannel.open(regionFile, READ, WRITE)) {
            ByteBuffer header = ByteBuffer.allocate(Integer.BYTES);
            ShadowPagingRegion.readFully(file, header, (long) KEY.getId() * Integer.BYTES);
            long payloadOffset = (long) (header.getInt(0) >>> 8) * SECTOR_SIZE;
            ByteBuffer prefix = ByteBuffer.allocate(Integer.BYTES).putInt(length);
            prefix.flip();
            while (prefix.hasRemaining()) payloadOffset += file.write(prefix, payloadOffset);
        }
        try (ShadowPagingRegion<EntryLocation2D> region = open(directory)) {
            try {
                region.readValue(KEY);
                fail("Invalid payload length must be rejected before allocation or reading");
            } catch (CorruptedDataException expected) {
                assertTrue(expected.getMessage().contains("found " + length));
            }
        }
    }

    @Test public void reservationExceptionReleasesBothLocks() throws Exception {
        try (ShadowPagingRegion<EntryLocation2D> region = open(temporary.newFolder().toPath())) {
            java.util.Map<EntryLocation2D, ByteBuffer> broken = new java.util.AbstractMap<EntryLocation2D, ByteBuffer>() {
                @Override public int size() { return 1; }
                @Override public java.util.Set<Entry<EntryLocation2D, ByteBuffer>> entrySet() { throw new IllegalArgumentException("reservation failed"); }
            };
            try { region.writeValues(broken); org.junit.Assert.fail(); } catch (IllegalArgumentException expected) { }
            java.util.concurrent.FutureTask<Void> flush = new java.util.concurrent.FutureTask<>(() -> { region.flush(); return null; });
            Thread thread = new Thread(flush); thread.setDaemon(true); thread.start();
            flush.get(3, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    private static ShadowPagingRegion<EntryLocation2D> open(Path directory) throws IOException {
        return ShadowPagingRegion.<EntryLocation2D>builder().setDirectory(directory)
                .setRegionKey(KEY.getRegionKey()).setKeyProvider(new EntryLocation2D.Provider())
                .setSectorSize(SECTOR_SIZE).build();
    }

    @SuppressWarnings("unchecked")
    private static SaveSection2D openSection(Path directory) {
        return new SaveSection2D(
                new SharedCachedRegionProvider<>(new SimpleRegionProvider<>(new EntryLocation2D.Provider(), directory,
                        (provider, key) -> ShadowPagingRegion.<EntryLocation2D>builder().setDirectory(directory)
                                .setRegionKey(key).setKeyProvider(provider).setSectorSize(SECTOR_SIZE).build(),
                        (dir, key) -> Files.exists(dir.resolve(key.getRegionKey().getName())))),
                new SharedCachedRegionProvider<>(new SimpleRegionProvider<>(new EntryLocation2D.Provider(), directory,
                        (provider, key) -> new ExtRegion<>(directory, Collections.emptyList(), provider, key),
                        (dir, key) -> Files.exists(dir.resolve(key.getRegionKey().getName() + ".ext")))));
    }

    /** Bounds retries so regressions fail deterministically instead of leaving a spinning test thread. */
    private static final class ShortReadChannel extends FileChannel {
        private final byte[] bytes;
        private final int maxRead;
        private int calls;

        private ShortReadChannel(byte[] bytes, int maxRead) {
            this.bytes = bytes;
            this.maxRead = maxRead;
        }

        @Override public int read(ByteBuffer dst, long position) {
            if (++calls > 16) throw new AssertionError("Read loop failed to terminate");
            if (position >= bytes.length) return -1;
            int count = Math.min(Math.min(dst.remaining(), maxRead), bytes.length - (int) position);
            dst.put(bytes, (int) position, count);
            return count;
        }

        @Override protected void implCloseChannel() { }
        @Override public int read(ByteBuffer dst) { throw new UnsupportedOperationException(); }
        @Override public long read(ByteBuffer[] dsts, int offset, int length) { throw new UnsupportedOperationException(); }
        @Override public int write(ByteBuffer src) { throw new UnsupportedOperationException(); }
        @Override public long write(ByteBuffer[] srcs, int offset, int length) { throw new UnsupportedOperationException(); }
        @Override public int write(ByteBuffer src, long position) { throw new UnsupportedOperationException(); }
        @Override public long position() { throw new UnsupportedOperationException(); }
        @Override public FileChannel position(long position) { throw new UnsupportedOperationException(); }
        @Override public long size() { return bytes.length; }
        @Override public FileChannel truncate(long size) { throw new UnsupportedOperationException(); }
        @Override public void force(boolean metadata) { throw new UnsupportedOperationException(); }
        @Override public long transferTo(long position, long count, WritableByteChannel target) { throw new UnsupportedOperationException(); }
        @Override public long transferFrom(ReadableByteChannel src, long position, long count) { throw new UnsupportedOperationException(); }
        @Override public MappedByteBuffer map(MapMode mode, long position, long size) { throw new UnsupportedOperationException(); }
        @Override public FileLock lock(long position, long size, boolean shared) { throw new UnsupportedOperationException(); }
        @Override public FileLock tryLock(long position, long size, boolean shared) { throw new UnsupportedOperationException(); }
    }
}
