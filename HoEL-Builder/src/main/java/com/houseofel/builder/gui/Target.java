package com.houseofel.builder.gui;

import org.bukkit.Material;
import org.bukkit.Tag;

public enum Target {
    STONE("Stone", Material.STONE),
    DIRT("Dirt", Material.DIRT),
    OAK_LOG("Oak Log", Material.OAK_LOG),
    WHEAT("Wheat", Material.WHEAT),
    /**
     * Groundworker's level-3 verb, "Clears Anything" — matches every real EARTH/ground
     * material, not just plain Stone/Dirt (2026-08-18, Kyle's own framing: "the player
     * should be responsible enough to know that he wants to clear everything on that work
     * area"). Deliberately as broad as {@link Tag#MINEABLE_PICKAXE}/{@link
     * Tag#MINEABLE_SHOVEL} allow — every stone variant, ore, and soft-ground material
     * Mojang itself classifies as pickaxe/shovel-mineable, not a hand-picked subset. Does
     * NOT include axe/hoe-mineable material (wood, crops) — those already have their own
     * distinct targets ({@link #OAK_LOG}, {@link #WHEAT}) and read as a different kind of
     * job, not "ground clearing." Gated to GROUNDWORKER level 3+ at the GUI layer
     * ({@code JavaJobDialog}/{@code BedrockJobForm}) — this enum constant itself has no
     * level awareness, it's just what "anything" resolves to once offered.
     */
    ANY_EARTH("Anything", Material.DIRT);

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
        if (this == ANY_EARTH) {
            return Tag.MINEABLE_PICKAXE.isTagged(candidate) || Tag.MINEABLE_SHOVEL.isTagged(candidate)
                    || isInfested(candidate);
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
