package com.houseofel.builder.npc;

import org.bukkit.Material;

import java.util.Set;

/**
 * Mirrors vanilla's pickaxe-tier gating without needing an actual held tool — what a
 * Helper is even able to break/get real drops from is unlocked directly by XP level
 * instead. Tier 1 is the effective starting baseline ("iron-equivalent" per the
 * Masterfile), so nothing currently in {@link com.houseofel.builder.gui.Target} requires
 * anything above it — this only starts mattering once Phase 1-G adds ore targets.
 */
public enum HarvestTier {
    TIER_1(1),
    TIER_2(7),
    TIER_3(14);

    private static final Set<Material> TIER_2_MATERIALS = Set.of(
            Material.DIAMOND_ORE, Material.DEEPSLATE_DIAMOND_ORE,
            Material.REDSTONE_ORE, Material.DEEPSLATE_REDSTONE_ORE,
            Material.EMERALD_ORE, Material.DEEPSLATE_EMERALD_ORE);

    private static final Set<Material> TIER_3_MATERIALS = Set.of(
            Material.OBSIDIAN, Material.CRYING_OBSIDIAN, Material.ANCIENT_DEBRIS);

    private final int unlockedAtLevel;

    HarvestTier(int unlockedAtLevel) {
        this.unlockedAtLevel = unlockedAtLevel;
    }

    public int unlockedAtLevel() {
        return unlockedAtLevel;
    }

    /** Which tier a block belongs to — anything not explicitly ore/obsidian-tier is Tier 1. */
    public static HarvestTier requiredTier(Material material) {
        if (TIER_3_MATERIALS.contains(material)) {
            return TIER_3;
        }
        if (TIER_2_MATERIALS.contains(material)) {
            return TIER_2;
        }
        return TIER_1;
    }
}
