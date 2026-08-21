package com.houseofel.builder.job;

import net.citizensnpcs.api.npc.NPC;

import java.util.UUID;

/**
 * What {@link JobManager} needs from any job it tracks, regardless of which concrete
 * task is actually running — {@link ClearJobTask} and {@link QuarrymanJobTask} both
 * implement this so one registry can hold either. A static {@code resume(...)} factory
 * can't be part of this (no virtual statics in Java); {@link JobManager#resumeAllOnEnable}
 * branches on the persisted {@link JobType} to call the right concrete one.
 */
public interface JobTask {

    NPC npc();

    UUID playerId();

    boolean isPaused();

    void start();

    void pause();

    void resumeTicking();

    void cancelJob();

    JobState toJobState();

    long estimatedRemainingMillis();
}
