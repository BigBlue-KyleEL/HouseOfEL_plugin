package com.houseofel.builder.job;

/**
 * Which concrete {@link JobTask} a persisted {@link JobState} belongs to — the
 * discriminator {@link JobManager} branches on when resuming from disk, since a static
 * factory method can't be part of an interface. Deliberately separate from
 * {@code com.houseofel.builder.gui.TaskType}: that enum carries placeholder values
 * (MINE/LUMBERJACK/FARM) with no wired job class behind them yet, and might never map
 * 1:1 to one — this enum's only job is guaranteeing that exact 1:1 mapping.
 */
enum JobType {
    CLEAR,
    QUARRY,
    LANDSCAPE
}
