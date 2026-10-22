package com.thewadesmith.prismfawe;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded, non-blocking handoff from FAWE workers to Paper's main thread. */
final class PendingChanges {
    private final ArrayBlockingQueue<BlockChange> queue;
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong unsupported = new AtomicLong();
    private final AtomicLong late = new AtomicLong();
    private final Runnable onLate;
    private boolean closed;

    PendingChanges(int capacity) {
        this(capacity, () -> {});
    }

    PendingChanges(int capacity, Runnable onLate) {
        queue = new ArrayBlockingQueue<>(capacity);
        this.onLate = onLate;
    }

    synchronized boolean offer(BlockChange change) {
        if (closed) {
            if (late.getAndIncrement() == 0) {
                onLate.run();
            }
            return false;
        }
        if (queue.offer(change)) {
            return true;
        }
        dropped.incrementAndGet();
        return false;
    }

    BlockChange poll() { return queue.poll(); }
    int size() { return queue.size(); }
    long dropped() { return dropped.get(); }
    long unsupported() { return unsupported.get(); }
    void unsupportedSession() { unsupported.incrementAndGet(); }
    synchronized void close() { closed = true; }
    long late() { return late.get(); }
}
