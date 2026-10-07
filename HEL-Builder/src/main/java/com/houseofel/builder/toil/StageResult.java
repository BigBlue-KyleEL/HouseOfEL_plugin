package com.houseofel.builder.toil;

/**
 * What a pipeline stage returns — named so a future {@code /crew ledger} can show exactly
 * which stage zeroed (or scaled) an award, per the framework's Implementation Notes.
 */
public record StageResult(String stageName, double multiplier, String note) {
}
