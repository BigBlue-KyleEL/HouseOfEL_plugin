package com.houseofel.builder.antigrind;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.function.ToLongFunction;

/**
 * Shared mechanism behind the per-key rolling ring buffers in this package
 * (see {@link RedundancyTracker}, {@link FreshLedger}): a lazily-created
 * {@code Deque} per key, with age-based pruning from the front and a
 * hard-cap eviction before appending. Callers keep their own scan /
 * classification logic — this only owns the buffer bookkeeping.
 */
final class AgeBoundedRingBuffer<K, V> {

    private final Map<K, Deque<V>> ringsByKey = new HashMap<>();
    private final ToLongFunction<V> timestampOf;

    AgeBoundedRingBuffer(ToLongFunction<V> timestampOf) {
        this.timestampOf = timestampOf;
    }

    /** Returns the ring for this key, creating an empty one if this key has never been touched. */
    Deque<V> ringFor(K key) {
        return ringsByKey.computeIfAbsent(key, k -> new ArrayDeque<>());
    }

    /** Returns the ring for this key, or null if this key has never been touched. Never creates an entry. */
    Deque<V> existingRingFor(K key) {
        return ringsByKey.get(key);
    }

    /** Removes entries older than maxAgeMillis from the front of the ring. */
    void pruneExpired(Deque<V> ring, long nowMillis, long maxAgeMillis) {
        while (!ring.isEmpty() && nowMillis - timestampOf.applyAsLong(ring.peekFirst()) >= maxAgeMillis) {
            ring.removeFirst();
        }
    }

    /** Appends value to the ring, evicting the oldest entry first if already at capacity. */
    void addCapped(Deque<V> ring, V value, int capacity) {
        if (ring.size() >= capacity) {
            ring.removeFirst();
        }
        ring.addLast(value);
    }
}
