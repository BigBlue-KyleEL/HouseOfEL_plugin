package com.houseofel.builder.npc;

import com.houseofel.builder.gui.TaskType;

/**
 * A Helper's fixed passive-bonus identity, chosen by the player when the NPC is spawned.
 * Doesn't gate speed (any NPC can max out speed at any task) — its only value is the
 * specialization bonus-drop skill, which applies when the NPC does work matching its
 * mapped {@link TaskType}.
 */
public enum Specialization {
    GROUNDWORKER("Groundworker", TaskType.CLEAR),
    LUMBERJACK("Lumberjack", TaskType.LUMBERJACK),
    FARMER("Farmer", TaskType.FARM);

    private final String label;
    private final TaskType taskType;

    Specialization(String label, TaskType taskType) {
        this.label = label;
        this.taskType = taskType;
    }

    public String label() {
        return label;
    }

    /** The task type this specialization's bonus-drop skill applies to. */
    public TaskType taskType() {
        return taskType;
    }
}
