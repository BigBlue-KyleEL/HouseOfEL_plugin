package com.houseofel.builder.toil;

import java.util.List;

/**
 * The work-ticket award pipeline, in the framework's normative order (Implementation
 * Notes: "necessity gate → redundancy decay → variety decay → presence → K → order
 * source → mentorship → rest/first-of-day → daily taper... implement it as an ordered
 * list of named stages"). Only the K (parity coefficient) stage carries real per-spec
 * logic this phase — every other stage is a neutral pass-through stub until Phase 1-F's
 * anti-grind chassis item (redundancy/variety decay, presence, daily taper) and the
 * per-specialization ladders / VanillaTiming items (necessity gate's order-source ties,
 * mentorship, rest/first-of-day) give them real behavior. This is deliberate scaffolding
 * — the ordered structure the framework requires, not yet the enforcement it describes.
 */
public final class ToilPipeline {

    private static final List<PipelineStage> STAGES = List.of(
            stub("necessity-gate"),
            stub("redundancy-decay"),
            stub("variety-decay"),
            stub("presence"),
            ToilPipeline::parityCoefficient,
            stub("order-source"),
            stub("mentorship"),
            stub("rest-first-of-day"),
            stub("daily-taper")
    );

    /** Runs every stage in order and returns the final banked Toil. */
    public int award(TicketContext context) {
        double toil = context.rawMinutes();
        for (PipelineStage stage : STAGES) {
            toil *= stage.apply(context).multiplier();
        }
        return (int) Math.round(toil);
    }

    private static StageResult parityCoefficient(TicketContext context) {
        double k = context.specialization().parityCoefficient();
        return new StageResult("parity-coefficient", k, context.specialization().label() + " K-band");
    }

    private static PipelineStage stub(String stageName) {
        return context -> new StageResult(stageName, 1.0, "stub — real logic lands in a later Phase 1-F item");
    }
}
