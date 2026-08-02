package com.houseofel.builder.gui;

import org.bukkit.Material;

public enum Target {
    STONE("Stone", Material.STONE),
    DIRT("Dirt", Material.DIRT),
    OAK_LOG("Oak Log", Material.OAK_LOG),
    WHEAT("Wheat", Material.WHEAT);

    private final String label;
    private final Material icon;

    Target(String label, Material icon) {
        this.label = label;
        this.icon = icon;
    }

    public String label() {
        return label;
    }

    /** Whether a world block counts as this target — Dirt also covers Grass Block. */
    public boolean matches(Material candidate) {
        if (this == DIRT) {
            return candidate == Material.DIRT || candidate == Material.GRASS_BLOCK;
        }
        return candidate == icon;
    }
}
