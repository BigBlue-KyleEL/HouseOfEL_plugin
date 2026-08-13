package com.houseofel.builder.toil;

/** One named, ordered step in {@link ToilPipeline}. */
@FunctionalInterface
public interface PipelineStage {
    StageResult apply(TicketContext context);
}
