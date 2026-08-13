package com.houseofel.builder.toil;

/**
 * Known work-ticket types a Helper can complete. V0 scope is intentionally just one —
 * Groundworker's 512-block clearing ticket, the only spec wired to real gameplay this
 * phase (Phase 1-F item 1). Farmer/Lumberjack tickets need tree-shape/replant logic that
 * doesn't exist yet in {@code ClearJobTask} and get their own entries when the
 * per-specialization ladders item wires them for real.
 */
public enum TicketKind {
    GROUNDWORKER_CLEAR_512
}
