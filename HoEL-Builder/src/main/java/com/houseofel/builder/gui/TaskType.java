package com.houseofel.builder.gui;

import org.bukkit.Material;

/** Placeholder roster — full task-type set arrives in Phase 1-G. */
public enum TaskType {
    MINE("Mining", Material.IRON_PICKAXE, "Pickaxe"),
    LUMBERJACK("Lumberjacking", Material.IRON_AXE, "Axe"),
    FARM("Farming", Material.IRON_HOE, "Hoe"),
    CLEAR("Clearing", Material.IRON_SHOVEL, "Shovel"),
    QUARRY("Quarrying", Material.DIAMOND_PICKAXE, "Pickaxe");

    private final String label;
    private final Material icon;
    private final String toolNoun;

    TaskType(String label, Material icon, String toolNoun) {
        this.label = label;
        this.icon = icon;
        this.toolNoun = toolNoun;
    }

    public String label() {
        return label;
    }

    /** Readable noun for this task's tool, used for the per-NPC cosmetic name (e.g. "Pickaxe"). */
    public String toolNoun() {
        return toolNoun;
    }

    /** Reverse lookup for contexts (like restart-resume) that only saved the tool Material. */
    public static TaskType fromTool(Material tool) {
        for (TaskType type : values()) {
            if (type.icon == tool) {
                return type;
            }
        }
        return CLEAR;
    }
}
