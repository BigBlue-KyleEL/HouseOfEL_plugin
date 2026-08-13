package com.houseofel.builder.toil;

import java.util.Map;

/**
 * Raw-minutes-per-ticket lookup — the "displaced vanilla player time" figure the
 * framework's award pipeline takes as its RAW input, per the framework's own
 * already-published XP-sources table. Kept as its own small service rather than inline
 * constants at each award call site: the framework's design treats this figure as the
 * output of one shared estimator, reused everywhere Toil is computed, the same principle
 * it applies to a balance-enforcement estimator this codebase doesn't have yet. This class
 * is that estimator's placeholder home — correct published numbers now, ready to become
 * the real shared estimator once balance enforcement exists to consume it too.
 */
public final class BalanceEstimator {

    private static final Map<TicketKind, Integer> RAW_MINUTES = Map.of(
            // "Groundworker — 512 blocks excavated, smoothed or drained, spoil hauled to
            // a real chest — 8 Toil" per the framework's XP sources table.
            TicketKind.GROUNDWORKER_CLEAR_512, 8
    );

    public int rawMinutesFor(TicketKind kind) {
        Integer minutes = RAW_MINUTES.get(kind);
        if (minutes == null) {
            throw new IllegalArgumentException("No estimated Toil value for ticket kind " + kind);
        }
        return minutes;
    }
}
