package com.houseofel.builder.antigrind;

import java.util.Deque;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Redundancy decay via task fingerprints, per the framework: a 200-entry-per-helper ring
 * buffer of recently-touched {@link TaskFingerprint}s. Same fingerprint again within 5
 * real minutes pays zero credit, within 20 pays reduced, otherwise full. In-memory only —
 * no persistence, the same accepted force-kill-loses-recent-state tradeoff
 * {@code HelperLevelService.progressCache} already makes, since this is a short-timescale
 * anti-exploit signal, not something that needs to survive a restart.
 *
 * <p>For Groundworker specifically, this stays a secondary defense behind the Fresh rule
 * (see {@link FreshLedger}) — Fresh's 30-minute window is broader than this one, and the
 * main way a cleared block position gets touched again is a player placing something back,
 * which Fresh already zeroes. This class is still real, general-purpose infrastructure
 * (keyed by {@link com.houseofel.builder.gui.TaskType}, not Groundworker-specific) for
 * block-physics refills (falling sand/gravel, water/lava) that Fresh can't see, and for any
 * future spec that awards per-action instead of in a 512-block batch.
 */
public final class RedundancyTracker {

    public enum CreditTier { FULL, REDUCED, ZERO }

    private static final int RING_SIZE = 200;
    private static final long ZERO_WINDOW_MILLIS = 5L * 60 * 1000;
    private static final long REDUCED_WINDOW_MILLIS = 20L * 60 * 1000;

    private record Touch(TaskFingerprint fingerprint, long timestamp) {
    }

    private final AgeBoundedRingBuffer<UUID, Touch> rings = new AgeBoundedRingBuffer<>(Touch::timestamp);
    private final Logger logger;

    public RedundancyTracker(Logger logger) {
        this.logger = logger;
    }

    /** Classifies this touch against the Helper's recent history, then records it regardless of outcome. */
    public CreditTier check(UUID npcUuid, TaskFingerprint fingerprint, long nowMillis) {
        Deque<Touch> ring = rings.ringFor(npcUuid);

        CreditTier tier = CreditTier.FULL;
        for (Touch touch : ring) {
            if (!touch.fingerprint().equals(fingerprint)) {
                continue;
            }
            long age = nowMillis - touch.timestamp();
            if (age < ZERO_WINDOW_MILLIS) {
                tier = CreditTier.ZERO;
                break;
            }
            if (age < REDUCED_WINDOW_MILLIS) {
                tier = CreditTier.REDUCED;
            }
            // Older matches (>= 20 min) leave tier untouched — iteration is oldest-first,
            // so a later (more recent) match always has the final say unless ZERO short-circuits.
        }

        rings.addCapped(ring, new Touch(fingerprint, nowMillis), RING_SIZE);

        if (tier != CreditTier.FULL) {
            logger.info("Redundancy decay: " + fingerprint + " classified " + tier + " (helper " + npcUuid + ")");
        }
        return tier;
    }
}
