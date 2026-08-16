package com.houseofel.builder.antigrind;

import com.houseofel.builder.toil.TicketKind;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Variety decay: a rolling-hour count of identical ticket TYPES per Helper (no location
 * component — distinct from {@link RedundancyTracker}, which is exact-position fingerprint
 * based). 20 of the same kind in the rolling hour pays half, 40 pays a quarter, floored at
 * a quarter — grinding one job type stays possible, it's just never zero and always the
 * worst available strategy. In-memory only, same accepted force-kill tradeoff as the other
 * short-timescale trackers in this package.
 */
public final class VarietyTracker {

    private static final long WINDOW_MILLIS = 60L * 60 * 1000;
    private static final int HALF_THRESHOLD = 20;
    private static final int QUARTER_THRESHOLD = 40;
    private static final double FLOOR_MULTIPLIER = 0.25;

    private record Key(UUID npcUuid, TicketKind kind) {
    }

    private final Map<Key, Deque<Long>> timestampsByKey = new HashMap<>();

    /**
     * Records this ticket's timestamp, then returns the multiplier for THIS ticket based on
     * how many of the same kind this Helper already banked within the rolling window
     * (not counting this one).
     */
    public double recordAndMultiplierFor(UUID npcUuid, TicketKind kind, long nowMillis) {
        Deque<Long> timestamps = timestampsByKey.computeIfAbsent(new Key(npcUuid, kind), k -> new ArrayDeque<>());

        while (!timestamps.isEmpty() && nowMillis - timestamps.peekFirst() >= WINDOW_MILLIS) {
            timestamps.removeFirst();
        }

        int priorCount = timestamps.size();
        timestamps.addLast(nowMillis);

        if (priorCount >= QUARTER_THRESHOLD) {
            return FLOOR_MULTIPLIER;
        }
        if (priorCount >= HALF_THRESHOLD) {
            return 0.5;
        }
        return 1.0;
    }
}
