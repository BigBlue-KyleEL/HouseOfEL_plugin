package com.houseofel.builder.npc;

import com.houseofel.builder.gui.TaskType;

/**
 * A Helper's fixed passive-bonus identity, chosen by the player when the NPC is spawned.
 * Doesn't gate speed (any NPC can max out speed at any task) — its only value is the
 * specialization bonus-drop skill, which applies when the NPC does work matching its
 * mapped {@link TaskType}.
 */
public enum Specialization {
    // All three V0 specs sit in the framework's K=1.00 "muscle specs" band — the baseline
    // everything else is measured against. Later specs (Signalman/Watchman at K=2.50,
    // etc.) get their own band when they're actually built.
    GROUNDWORKER("Groundworker", TaskType.CLEAR, 1.00),
    LUMBERJACK("Lumberjack", TaskType.LUMBERJACK, 1.00),
    FARMER("Farmer", TaskType.FARM, 1.00);

    private final String label;
    private final TaskType taskType;
    private final double parityCoefficient;

    Specialization(String label, TaskType taskType, double parityCoefficient) {
        this.label = label;
        this.taskType = taskType;
        this.parityCoefficient = parityCoefficient;
    }

    public String label() {
        return label;
    }

    /** The task type this specialization's bonus-drop skill applies to. */
    public TaskType taskType() {
        return taskType;
    }

    /** The Toil pipeline's K coefficient — scales the ledger only, never output. */
    public double parityCoefficient() {
        return parityCoefficient;
    }
}
