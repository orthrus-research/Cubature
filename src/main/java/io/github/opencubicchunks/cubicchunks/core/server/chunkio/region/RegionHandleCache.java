package io.github.opencubicchunks.cubicchunks.core.server.chunkio.region;

import cubicchunks.regionlib.api.region.IRegion;
import cubicchunks.regionlib.api.region.IRegionProvider;
import cubicchunks.regionlib.api.region.key.IKey;
import cubicchunks.regionlib.api.region.key.IKeyProvider;
import cubicchunks.regionlib.api.region.key.RegionKey;
import cubicchunks.regionlib.lib.provider.SimpleRegionProvider.RegionFactory;
import cubicchunks.regionlib.util.CheckedBiConsumer;
import cubicchunks.regionlib.util.CheckedConsumer;
import cubicchunks.regionlib.util.CheckedFunction;

import java.io.Closeable;
import java.io.Flushable;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

/**
 * One storage's bounded region ownership. The metadata monitor never encloses file I/O or
 * a region operation lock. Opening/retiring entries consume capacity until disposal succeeds.
 * A lease pins a handle; its operation lock serializes the mutable allocator/channel.
 */
public final class RegionHandleCache implements Closeable, Flushable {
    private enum State { OPENING, OPEN, RETIRING, FAILED }
    private final Object metadata = new Object();
    private final ReentrantLock lifecycle = new ReentrantLock();
    private final LinkedHashMap<Key, Handle> handles = new LinkedHashMap<>(16, .75f, true);
    private final Map<Thread, Integer> leaseOwners = new IdentityHashMap<>();
    private final int capacity;
    private boolean closing;

    public RegionHandleCache(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("Region capacity must be positive");
        this.capacity = capacity;
    }

    public <K extends IKey<K>> Provider<K> provider(Path directory, IKeyProvider<K> keys,
            boolean overflow, RegionFactory<K> factory) {
        return new Provider<>(directory, keys, overflow, factory);
    }

    private static final class Key {
        final Object provider;
        final RegionKey region;
        Key(Object provider, RegionKey region) { this.provider = provider; this.region = region; }
        @Override public int hashCode() { return 31 * System.identityHashCode(provider) + region.hashCode(); }
        @Override public boolean equals(Object other) {
            return other instanceof Key && ((Key) other).provider == provider && ((Key) other).region.equals(region);
        }
    }

    private static final class Handle {
        final Key key;
        final ReentrantLock operation = new ReentrantLock();
        State state = State.OPENING;
        IRegion<?> region;
        int leases;
        IOException closeFailure;
        Handle(Key key) { this.key = key; }
    }

    private void awaitChange() throws InterruptedIOException {
        try { metadata.wait(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw interrupted(e); }
    }

    private static InterruptedIOException interrupted(InterruptedException cause) {
        InterruptedIOException error = new InterruptedIOException("Interrupted waiting for owned region storage");
        error.initCause(cause); return error;
    }

    private static void lock(ReentrantLock lock) throws InterruptedIOException {
        try { lock.lockInterruptibly(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw interrupted(e); }
    }

    private void pin(Handle handle, Thread owner) {
        handle.leases++;
        leaseOwners.put(owner, leaseOwners.getOrDefault(owner, 0) + 1);
    }

    private void release(Handle handle, Thread owner) {
        synchronized (metadata) {
            handle.leases--;
            int count = leaseOwners.get(owner) - 1;
            if (count == 0) leaseOwners.remove(owner); else leaseOwners.put(owner, count);
            metadata.notifyAll();
        }
    }

    private <K extends IKey<K>> Lease<K> acquire(Provider<K> provider, K location, boolean create) throws IOException {
        Key key = new Key(provider, location.getRegionKey());
        Thread owner = Thread.currentThread();
        while (true) {
            Handle opening = null, retiring = null;
            synchronized (metadata) {
                if (closing || provider.closed) throw new IOException("Region storage is closing or closed");
                Handle hit = handles.get(key);
                if (hit != null) {
                    if (hit.state == State.OPEN) { pin(hit, owner); return new Lease<>(hit, owner); }
                    if (hit.state == State.FAILED) throw new IOException("Region close failed; explicit close retry required", hit.closeFailure);
                    if (leaseOwners.containsKey(owner)) throw new IOException("Cannot wait for another region inside a lease");
                    awaitChange(); continue;
                }
                if (handles.size() < capacity) {
                    opening = new Handle(key); handles.put(key, opening);
                } else {
                    IOException failure = null;
                    for (Handle candidate : handles.values()) {
                        if (candidate.state == State.OPEN && candidate.leases == 0) { retiring = candidate; break; }
                        if (candidate.closeFailure != null) failure = candidate.closeFailure;
                    }
                    if (retiring == null) {
                        if (failure != null) throw new IOException("Region capacity retained after failed close", failure);
                        if (leaseOwners.containsKey(owner)) throw new IOException("Nested region acquisition would exhaust the cache");
                        awaitChange(); continue;
                    }
                    retiring.state = State.RETIRING;
                }
            }
            if (retiring != null) { retire(retiring, false); continue; }
            IRegion<K> region;
            try {
                region = create || Files.exists(provider.file(location.getRegionKey()))
                        ? provider.factory.create(provider.keys, location.getRegionKey()) : null;
            } catch (IOException | RuntimeException | Error error) {
                synchronized (metadata) { handles.remove(key); metadata.notifyAll(); }
                throw error;
            }
            synchronized (metadata) {
                if (region == null) { handles.remove(key); metadata.notifyAll(); return null; }
                opening.region = region; opening.state = State.OPEN; pin(opening, owner);
                metadata.notifyAll(); return new Lease<>(opening, owner);
            }
        }
    }

    /** Called only after admission marks an unleased handle retiring. Never holds metadata. */
    private void retire(Handle handle, boolean retryClose) throws IOException {
        boolean closeAttempted = retryClose, success = false;
        IOException failure = null;
        try {
            lock(handle.operation);
            try {
                if (!retryClose) handle.region.flush();
                closeAttempted = true; handle.region.close(); success = true;
            } finally { handle.operation.unlock(); }
        } catch (IOException error) { failure = error; throw error; }
        catch (RuntimeException error) { failure = new IOException("Region retirement failed", error); throw failure; }
        catch (Error error) { failure = new IOException("Region retirement aborted", error); throw error; }
        finally {
            synchronized (metadata) {
                if (success) handles.remove(handle.key);
                else {
                    handle.state = closeAttempted ? State.FAILED : State.OPEN;
                    handle.closeFailure = closeAttempted ? failure : null;
                }
                metadata.notifyAll();
            }
        }
    }

    private static IOException append(IOException first, IOException next) {
        if (first == null) return next;
        if (first != next) first.addSuppressed(next);
        return first;
    }

    @Override public void flush() throws IOException { flush(null); }

    private void checkLifecycleCaller() throws IOException {
        // Check before taking lifecycle: another closer may already be waiting for this lease.
        synchronized (metadata) {
            if (leaseOwners.containsKey(Thread.currentThread())) throw new IOException("Cannot flush or close storage inside a region lease");
        }
    }

    private void flush(Provider<?> provider) throws IOException {
        checkLifecycleCaller();
        lock(lifecycle);
        try {
            List<Handle> targets;
            synchronized (metadata) {
                if (closing || provider != null && provider.closed) throw new IOException("Region storage is closing or closed");
                targets = new ArrayList<>(handles.values());
            }
            IOException failure = null;
            for (Handle handle : targets) {
                if (provider != null && handle.key.provider != provider) continue;
                boolean pinned = false;
                try {
                    synchronized (metadata) {
                        while (handles.get(handle.key) == handle && (handle.state == State.OPENING || handle.state == State.RETIRING)) awaitChange();
                        // An eviction completed the durable close while this flush was waiting.
                        if (handles.get(handle.key) != handle) continue;
                        if (handle.state == State.FAILED) throw new IOException("Owned region close failed", handle.closeFailure);
                        pin(handle, Thread.currentThread()); pinned = true;
                    }
                    lock(handle.operation);
                    try { handle.region.flush(); } finally { handle.operation.unlock(); }
                } catch (IOException error) { failure = append(failure, error); }
                catch (RuntimeException error) { failure = append(failure, new IOException("Region flush failed", error)); }
                finally { if (pinned) release(handle, Thread.currentThread()); }
            }
            if (failure != null) throw failure;
        } finally { lifecycle.unlock(); }
    }

    @Override public void close() throws IOException { close(null); }

    private void close(Provider<?> provider) throws IOException {
        checkLifecycleCaller();
        lock(lifecycle);
        try {
            List<Handle> targets;
            synchronized (metadata) {
                if (provider == null) closing = true; else provider.closed = true;
                metadata.notifyAll(); targets = new ArrayList<>(handles.values());
            }
            IOException failure = null;
            for (Handle handle : targets) {
                if (provider != null && handle.key.provider != provider) continue;
                try {
                    boolean retryClose;
                    synchronized (metadata) {
                        while (handles.get(handle.key) == handle && (handle.leases != 0 || handle.state == State.OPENING || handle.state == State.RETIRING)) awaitChange();
                        if (handles.get(handle.key) != handle) continue;
                        retryClose = handle.state == State.FAILED; handle.state = State.RETIRING;
                    }
                    retire(handle, retryClose);
                } catch (IOException error) { failure = append(failure, error); }
            }
            if (failure != null) throw failure;
        } finally { lifecycle.unlock(); }
    }

    int size() { synchronized (metadata) { return handles.size(); } }

    public final class Provider<K extends IKey<K>> implements IRegionProvider<K> {
        private final Path directory;
        private final IKeyProvider<K> keys;
        private final boolean overflow;
        private final RegionFactory<K> factory;
        private boolean closed;
        private Provider(Path directory, IKeyProvider<K> keys, boolean overflow, RegionFactory<K> factory) {
            this.directory = directory; this.keys = keys; this.overflow = overflow; this.factory = factory;
        }
        private Path file(RegionKey key) { return directory.resolve(key.getName() + (overflow ? ".ext" : "")); }
        @Override public IRegion<K> getRegion(K key) throws IOException { return acquire(this, key, true); }
        @Override public Optional<IRegion<K>> getExistingRegion(K key) throws IOException { return Optional.ofNullable(acquire(this, key, false)); }
        @Override public <R> R fromRegion(K key, CheckedFunction<? super IRegion<K>, R, IOException> action) throws IOException {
            try (IRegion<K> lease = getRegion(key)) { return action.apply(lease); }
        }
        @Override public <R> Optional<R> fromExistingRegion(K key, CheckedFunction<? super IRegion<K>, R, IOException> action) throws IOException {
            try (IRegion<K> lease = acquire(this, key, false)) { return lease == null ? Optional.empty() : Optional.of(action.apply(lease)); }
        }
        @Override public void forRegion(K key, CheckedConsumer<? super IRegion<K>, IOException> action) throws IOException {
            fromRegion(key, region -> { action.accept(region); return Boolean.TRUE; });
        }
        @Override public void forExistingRegion(K key, CheckedConsumer<? super IRegion<K>, IOException> action) throws IOException {
            fromExistingRegion(key, region -> { action.accept(region); return Boolean.TRUE; });
        }
        private void existingKeys(CheckedConsumer<RegionKey, IOException> action) throws IOException {
            synchronized (metadata) { if (closing || closed) throw new IOException("Region storage is closing or closed"); }
            try (Stream<Path> paths = Files.list(directory)) {
                java.util.Iterator<Path> iterator = paths.iterator();
                while (iterator.hasNext()) {
                    String name = iterator.next().getFileName().toString();
                    if (overflow) { if (!name.endsWith(".ext")) continue; name = name.substring(0, name.length() - 4); }
                    RegionKey key = new RegionKey(name);
                    if (keys.isValid(key)) action.accept(key);
                }
            }
        }
        @Override public void forAllRegions(CheckedBiConsumer<RegionKey, ? super IRegion<K>, IOException> action) throws IOException {
            existingKeys(key -> forExistingRegion(keys.fromRegionAndId(key, 0), region -> action.accept(key, region)));
        }
        /** Release the region before invoking user callbacks or probing another provider. */
        public void forAllKeys(CheckedConsumer<? super K, IOException> action) throws IOException {
            existingKeys(key -> {
                List<K> found = fromExistingRegion(keys.fromRegionAndId(key, 0), region -> {
                    List<K> batch = new ArrayList<>(); region.forEachKey(batch::add); return batch;
                }).orElseGet(ArrayList::new);
                for (K entry : found) action.accept(entry);
            });
        }
        @Override public void flush() throws IOException { RegionHandleCache.this.flush(this); }
        @Override public void close() throws IOException { RegionHandleCache.this.close(this); }
    }

    /** Closing a borrowed handle releases its lease, never the underlying channel. */
    private final class Lease<K extends IKey<K>> implements IRegion<K> {
        final Handle handle;
        final Thread owner;
        boolean released;
        Lease(Handle handle, Thread owner) { this.handle = handle; this.owner = owner; }
        @SuppressWarnings("unchecked") private <R> R use(CheckedFunction<IRegion<K>, R, IOException> action) throws IOException {
            lock(handle.operation);
            try {
                if (released) throw new IOException("Region lease already released");
                return action.apply((IRegion<K>) handle.region);
            } finally { handle.operation.unlock(); }
        }
        @Override public void writeValue(K key, ByteBuffer value) throws IOException { use(r -> { r.writeValue(key, value); return null; }); }
        @Override public void writeValues(Map<K, ByteBuffer> values) throws IOException { use(r -> { r.writeValues(values); return null; }); }
        @Override public void writeSpecial(K key, Object value) throws IOException { use(r -> { r.writeSpecial(key, value); return null; }); }
        @Override public Optional<ByteBuffer> readValue(K key) throws IOException { return use(r -> r.readValue(key)); }
        @Override public boolean hasValue(K key) {
            try { return use(r -> r.hasValue(key)); }
            catch (IOException error) { throw new java.io.UncheckedIOException(error); }
        }
        @Override public void forEachKey(CheckedConsumer<? super K, IOException> action) throws IOException {
            // Arbitrary callbacks must not run under the operation lock (they may acquire another region).
            List<K> keys = use(r -> { List<K> found = new ArrayList<>(); r.forEachKey(found::add); return found; });
            for (K key : keys) action.accept(key);
        }
        @Override public void flush() throws IOException { use(r -> { r.flush(); return null; }); }
        @Override public void close() {
            handle.operation.lock();
            try { if (released) return; released = true; }
            finally { handle.operation.unlock(); }
            release(handle, owner);
        }
    }
}
