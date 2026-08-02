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

    /**
     * Whether a world block counts as this target — Dirt also covers Grass Block, and
     * Stone also covers its Infested variants, since those are visually identical to the
     * real thing and there's no way to tell until one's already broken.
     */
    public boolean matches(Material candidate) {
        if (this == DIRT) {
            return candidate == Material.DIRT || candidate == Material.GRASS_BLOCK;
        }
        if (this == STONE) {
            return candidate == Material.STONE || isInfested(candidate);
        }
        return candidate == icon;
    }

    /** Breaking one of these spawns a Silverfish — the "unexpected" part of clearing Stone. */
    public static boolean isInfested(Material material) {
        return switch (material) {
            case INFESTED_STONE, INFESTED_COBBLESTONE, INFESTED_STONE_BRICKS,
                 INFESTED_MOSSY_STONE_BRICKS, INFESTED_CRACKED_STONE_BRICKS,
                 INFESTED_CHISELED_STONE_BRICKS, INFESTED_DEEPSLATE -> true;
            default -> false;
        };
    }
}
