package com.houseofel.builder.gui;

import org.bukkit.Material;

/** Placeholder roster — full task-type set arrives in Phase 1-G. */
public enum TaskType {
    MINE("Mining", Material.IRON_PICKAXE),
    LUMBERJACK("Lumberjacking", Material.IRON_AXE),
    FARM("Farming", Material.IRON_HOE),
    CLEAR("Clearing", Material.IRON_SHOVEL);

    private final String label;
    private final Material icon;

    TaskType(String label, Material icon) {
        this.label = label;
        this.icon = icon;
    }

    public String label() {
        return label;
    }

    /** The tool the NPC equips while performing this task in the world. */
    public Material tool() {
        return icon;
    }
}
