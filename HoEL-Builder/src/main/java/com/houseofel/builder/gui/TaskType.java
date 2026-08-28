package com.houseofel.builder.gui;

import org.bukkit.Material;

/** Placeholder roster — full task-type set arrives in Phase 1-G. */
public enum TaskType {
    MINE("Mining", Material.IRON_PICKAXE, "Pickaxe"),
    LUMBERJACK("Lumberjacking", Material.IRON_AXE, "Axe"),
    FARM("Farming", Material.IRON_HOE, "Hoe"),
    CLEAR("Clearing", Material.IRON_SHOVEL, "Shovel"),
    QUARRY("Quarrying", Material.DIAMOND_PICKAXE, "Pickaxe"),
    /**
     * Groundworker level-8 Choice option B. Runs the same Clearing engine as
     * {@link #CLEAR} — it is a behavioural fork of that job, not a separate one — but
     * chains ClearJobTask's topsoil-restore phase afterwards. Given its own entry so the
     * menu can offer it as a distinct, discoverable job rather than making Clearing
     * silently mean something different for one Helper (Kyle, 2026-08-25).
     */
    LANDSCAPE("Landscaping", Material.IRON_SHOVEL, "Shovel"),
    COFFERDAM("Cofferdam", Material.IRON_SHOVEL, "Shovel");

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
