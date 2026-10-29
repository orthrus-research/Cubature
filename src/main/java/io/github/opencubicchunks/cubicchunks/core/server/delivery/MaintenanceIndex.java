package io.github.opencubicchunks.cubicchunks.core.server.delivery;

import java.util.IdentityHashMap;
import java.util.Iterator;

/** Owner-thread round-robin cursors. No iterator or per-inspection allocation survives a tick. */
public final class MaintenanceIndex<T> {
    private static final class Node<T> {
        final T value;
        Node<T> gcPrevious, gcNext, savePrevious, saveNext;
        Node(T value) { this.value = value; }
    }
    private final IdentityHashMap<T, Node<T>> nodes = new IdentityHashMap<>();
    private Node<T> gc, save;

    public void add(T value) {
        if (nodes.containsKey(value)) return;
        Node<T> node = new Node<>(value);
        nodes.put(value, node);
        if (gc == null) {
            node.gcNext = node.gcPrevious = node.saveNext = node.savePrevious = node;
            gc = save = node;
        } else {
            // Each lane has its own ring: insert behind its next candidate, so continuous
            // loading cannot keep inserting ahead of an older survivor on either lane.
            node.gcNext = gc;
            node.gcPrevious = gc.gcPrevious;
            gc.gcPrevious.gcNext = node;
            gc.gcPrevious = node;
            node.saveNext = save;
            node.savePrevious = save.savePrevious;
            save.savePrevious.saveNext = node;
            save.savePrevious = node;
        }
    }

    public void remove(T value) {
        Node<T> node = nodes.remove(value);
        if (node == null) return;
        if (node.gcNext == node) { gc = save = null; return; }
        if (gc == node) gc = node.gcNext;
        if (save == node) save = node.saveNext;
        node.gcPrevious.gcNext = node.gcNext;
        node.gcNext.gcPrevious = node.gcPrevious;
        node.savePrevious.saveNext = node.saveNext;
        node.saveNext.savePrevious = node.savePrevious;
    }

    public T next(boolean autosave) {
        Node<T> node = autosave ? save : gc;
        if (node == null) return null;
        if (autosave) save = node.saveNext; else gc = node.gcNext;
        return node.value;
    }

    public int size() { return nodes.size(); }

    public Iterator<T> track(Iterator<T> source) {
        return new Iterator<T>() {
            private T last;
            @Override public boolean hasNext() { return source.hasNext(); }
            @Override public T next() { return last = source.next(); }
            @Override public void remove() { source.remove(); MaintenanceIndex.this.remove(last); }
        };
    }
}
