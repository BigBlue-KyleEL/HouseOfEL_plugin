package com.houseofel.builder.toil;

import com.houseofel.builder.antigrind.PresenceTracker;
import com.houseofel.builder.antigrind.VarietyTracker;
import com.houseofel.builder.death.DeathRecordStore;

import java.util.List;
import java.util.UUID;

/**
 * The work-ticket award pipeline, in the framework's normative order (Implementation
 * Notes: "necessity gate → redundancy decay → variety decay → presence → K → order
 * source → mentorship → rest/first-of-day → daily taper... implement it as an ordered
 * list of named stages"). Real as of the anti-grind chassis item: parity coefficient (K),
 * variety decay, presence, and daily taper. Necessity gate, order-source, mentorship, and
 * rest/first-of-day stay stubbed — confirmed out of scope for this item (necessity gate
 * has nothing to gate yet with only one ticket type live; the other three aren't part of
 * this item's scope at all). Redundancy decay also stays a stub HERE — see its note below
 * for why.
 */
public final class ToilPipeline {

    private static final int DAILY_TOIL_CAP = 150;
    private static final double TAPERED_MULTIPLIER = 0.35;

    private final List<PipelineStage> stages;
    private final VarietyTracker varietyTracker;
    private final PresenceTracker presenceTracker;
    private final DeathRecordStore deathRecordStore;

    public ToilPipeline(VarietyTracker varietyTracker, PresenceTracker presenceTracker,
                         DeathRecordStore deathRecordStore) {
        this.varietyTracker = varietyTracker;
        this.presenceTracker = presenceTracker;
        this.deathRecordStore = deathRecordStore;
        this.stages = List.of(
                stub("necessity-gate"),
                stub("redundancy-decay", "enforced upstream in ClearJobTask — Groundworker's tickets are "
                        + "batched, not per-award; would become a real per-action stage for a future spec "
                        + "that awards per-action instead of per-batch"),
                this::varietyDecay,
                this::presenceGate,
                ToilPipeline::parityCoefficient,
                stub("order-source"),
                stub("mentorship"),
                stub("rest-first-of-day"),
                this::dailyTaper
        );
    }

    /**
     * Runs every stage in order and returns the final banked Toil. Each soft-decay stage
     * (variety decay, daily taper) is individually documented to floor at a reduced
     * multiplier, never zero — but several of them stacking multiplicatively can still
     * round the combined result down to zero, defeating that intent collectively even
     * though no single stage ever does alone. Floored at 1 (not 0) whenever there was any
     * raw Toil to begin with, so the guarantee holds for the combination, not just each
     * stage in isolation. Rust's separate post-pipeline halving in
     * {@code HelperLevelService.awardTicket} is untouched by this — a rusted Helper can
     * still land on exactly 0, which is that system's own intentional harsher penalty.
     */
    public int award(TicketContext context) {
        double toil = context.rawMinutes();
        for (PipelineStage stage : stages) {
            toil *= stage.apply(context).multiplier();
        }
        int rounded = (int) Math.round(toil);
        return context.rawMinutes() > 0 ? Math.max(rounded, 1) : rounded;
    }

    private static StageResult parityCoefficient(TicketContext context) {
        double k = context.specialization().parityCoefficient();
        return new StageResult("parity-coefficient", k, context.specialization().label() + " K-band");
    }

    /** Rolling-hour count of identical ticket TYPES — 20+ pays half, 40+ pays a quarter, floored at a quarter. */
    private StageResult varietyDecay(TicketContext context) {
        double multiplier = varietyTracker.recordAndMultiplierFor(context.npcUuid(), context.kind(), context.timestamp());
        return new StageResult("variety-decay", multiplier, "rolling-hour ticket-type count");
    }

    /**
     * Owner active in the last 10 real minutes → full; owner away but someone else online
     * active → half; nobody active → zero. Helpers keep WORKING at zero — this only gates
     * the Toil multiplier, never job execution/pacing.
     */
    private StageResult presenceGate(TicketContext context) {
        UUID owner = deathRecordStore.ownerOf(context.npcUuid());
        if (owner != null && presenceTracker.isActive(owner)) {
            return new StageResult("presence", 1.0, "owner active");
        }
        if (presenceTracker.anyOtherPlayerActive(owner)) {
            return new StageResult("presence", 0.5, "owner away, someone else active");
        }
        return new StageResult("presence", 0.0, "nobody active");
    }

    /**
     * Soft taper, not a hard stop — 150 Toil/helper/real-day at full value, everything
     * beyond that same server-local day at 0.35x, no rollover. Checked BEFORE this ticket
     * is added (confirmed simplification — a single ticket isn't split fractionally across
     * the threshold). Reads {@code context.dailyCumulativeBefore()} rather than querying
     * SQLite itself — {@code HelperLevelService.awardTicket} already read it once before
     * building this context, and the later write to {@code DailyTaperStore} reuses that
     * same read too, so the row is only ever queried once per ticket, not twice.
     */
    private StageResult dailyTaper(TicketContext context) {
        int cumulativeBefore = context.dailyCumulativeBefore();
        double multiplier = cumulativeBefore >= DAILY_TOIL_CAP ? TAPERED_MULTIPLIER : 1.0;
        return new StageResult("daily-taper", multiplier, cumulativeBefore + "/" + DAILY_TOIL_CAP + " banked today");
    }

    private static PipelineStage stub(String stageName) {
        return stub(stageName, "stub — real logic lands in a later Phase 1-F item");
    }

    private static PipelineStage stub(String stageName, String note) {
        return context -> new StageResult(stageName, 1.0, note);
    }
}
