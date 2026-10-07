package com.houseofel.builder.antigrind;

import org.bukkit.block.Block;

import java.util.Deque;

/**
 * The Fresh rule's ledger — any block a player placed within the last 30 real minutes pays
 * zero Toil to any Helper that clears it ("player-caused work is not work," the framework's
 * single largest exploit-class closer). Per the framework's explicit instruction, this is a
 * per-chunk rolling ring buffer, NOT per-block PDC — per-block persistent data across a
 * base-sized region would be both expensive and unbounded. In-memory only, same accepted
 * force-kill tradeoff as {@link RedundancyTracker}.
 *
 * <p>Eviction is age-based (anything older than the 30-minute window is pruned), not
 * count-based — a fixed count cap alone (confirmed live 2026-08-14 via code review: 128
 * was the original bound) evicts prematurely under ordinary building, since placing more
 * than 128 blocks in one chunk inside 30 minutes is routine, not a burst edge case.
 * {@link #MAX_RING_SIZE_PER_CHUNK} exists only as a pathological-case safety valve
 * (unbounded spam), generous enough that normal play never approaches it.
 *
 * <p>Only tracks placements. The "player destroyed → helper restores" direction of Fresh
 * is out of scope — no repair-type specialization exists yet to need it.
 */
public final class FreshLedger {

    private static final long FRESH_WINDOW_MILLIS = 30L * 60 * 1000;
    private static final int MAX_RING_SIZE_PER_CHUNK = 4096;

    private record ChunkKey(String world, int chunkX, int chunkZ) {
    }

    private record Placement(int x, int y, int z, long timestamp) {
    }

    private final AgeBoundedRingBuffer<ChunkKey, Placement> rings = new AgeBoundedRingBuffer<>(Placement::timestamp);

    public void recordPlacement(Block block, long nowMillis) {
        Deque<Placement> ring = rings.ringFor(keyFor(block));
        rings.pruneExpired(ring, nowMillis, FRESH_WINDOW_MILLIS);
        rings.addCapped(ring, new Placement(block.getX(), block.getY(), block.getZ(), nowMillis), MAX_RING_SIZE_PER_CHUNK);
    }

    /** True if a player placed a block at this exact position within the last 30 real minutes. */
    public boolean isFresh(Block block, long nowMillis) {
        Deque<Placement> ring = rings.existingRingFor(keyFor(block));
        if (ring == null) {
            return false;
        }
        rings.pruneExpired(ring, nowMillis, FRESH_WINDOW_MILLIS);
        for (Placement placement : ring) {
            if (placement.x() == block.getX() && placement.y() == block.getY() && placement.z() == block.getZ()) {
                return true;
            }
        }
        return false;
    }

    private static ChunkKey keyFor(Block block) {
        return new ChunkKey(block.getWorld().getName(), block.getX() >> 4, block.getZ() >> 4);
    }
}
