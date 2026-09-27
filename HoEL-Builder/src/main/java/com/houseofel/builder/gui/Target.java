package com.houseofel.builder.gui;

import org.bukkit.Material;
import org.bukkit.Tag;

import java.util.Locale;
import java.util.Objects;

/** A legacy named target or one validated, exact-material Clearing target. */
public final class Target {
    public static final Target STONE = new Target("STONE", "Stone", Material.STONE, null);
    public static final Target DIRT = new Target("DIRT", "Dirt", Material.DIRT, null);
    public static final Target OAK_LOG = new Target("OAK_LOG", "Oak Log", Material.OAK_LOG, null);
    public static final Target WHEAT = new Target("WHEAT", "Wheat", Material.WHEAT, null);
    /** Groundworker L3: the full pickaxe/shovel family, including ores, except spawners. */
    public static final Target ANY_EARTH = new Target("ANY_EARTH", "Anything", Material.DIRT, null);

    private final String name;
    private final String label;
    private final Material icon;
    private final Material specificMaterial;

    private Target(String name, String label, Material icon, Material specificMaterial) {
        this.name = name;
        this.label = label;
        this.icon = icon;
        this.specificMaterial = specificMaterial;
    }

    /** Resolves and validates against server tags; clients cannot supply their own pool. */
    public static Target specificBlock(Material material) {
        if (material == null || !ClearingTargetPool.allowedMaterials().contains(material)) {
            throw new IllegalArgumentException("Not an allowed specific Clearing block: " + material);
        }
        StringBuilder label = new StringBuilder();
        for (String word : material.name().toLowerCase(Locale.ROOT).split("_")) {
            if (!label.isEmpty()) label.append(' ');
            label.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return new Target("SPECIFIC_BLOCK", label.toString(), material, material);
    }

    /** Legacy menu choices only. Specific Block is created with an explicit material. */
    public static Target[] values() {
        return new Target[] { STONE, DIRT, OAK_LOG, WHEAT, ANY_EARTH };
    }

    /** Compatibility with existing enum-name saves and callers. */
    public static Target valueOf(String name) {
        for (Target target : values()) {
            if (target.name.equals(name)) return target;
        }
        throw new IllegalArgumentException("Unrecognised target: " + name);
    }

    /** Old saves need no material field; new saves must contain a valid exact selection. */
    public static Target fromSaved(String name, String materialName) {
        if (!"SPECIFIC_BLOCK".equals(name)) return valueOf(name);
        if (materialName == null) throw new IllegalArgumentException("Missing specific target material");
        return specificBlock(Material.valueOf(materialName));
    }

    public String name() { return name; }
    public String label() { return label; }
    public Material specificMaterial() { return specificMaterial; }
    public boolean isSpecificBlock() { return specificMaterial != null; }

    /** Target eligibility only; callers still enforce Groundworker specialization. */
    public boolean earnsGroundworkerProgress() {
        return this == STONE || this == DIRT || this == ANY_EARTH || isSpecificBlock();
    }

    /** New picks are exact. Legacy Stone/Dirt saves retain their original pairings. */
    public boolean matches(Material candidate) {
        if (isSpecificBlock()) return candidate == specificMaterial;
        if (this == DIRT) {
            return candidate == Material.DIRT || candidate == Material.GRASS_BLOCK;
        }
        if (this == STONE) {
            return candidate == Material.STONE || isInfested(candidate);
        }
        if (this == ANY_EARTH) {
            return candidate != Material.SPAWNER
                    && (Tag.MINEABLE_PICKAXE.isTagged(candidate) || Tag.MINEABLE_SHOVEL.isTagged(candidate)
                    || isInfested(candidate));
        }
        return candidate == icon;
    }

    /** Breaking one of these spawns a Silverfish. */
    public static boolean isInfested(Material material) {
        return switch (material) {
            case INFESTED_STONE, INFESTED_COBBLESTONE, INFESTED_STONE_BRICKS,
                 INFESTED_MOSSY_STONE_BRICKS, INFESTED_CRACKED_STONE_BRICKS,
                 INFESTED_CHISELED_STONE_BRICKS, INFESTED_DEEPSLATE -> true;
            default -> false;
        };
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Target target && name.equals(target.name)
                && specificMaterial == target.specificMaterial;
    }

    @Override
    public int hashCode() { return Objects.hash(name, specificMaterial); }

    @Override
    public String toString() {
        return isSpecificBlock() ? name + ":" + specificMaterial.name() : name;
    }
}
