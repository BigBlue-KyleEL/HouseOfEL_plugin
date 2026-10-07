package com.houseofel.builder.gui;

import org.bukkit.Material;
import org.bukkit.Tag;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/** Runtime Bukkit tags are authoritative; never infer eligibility from material names. */
public final class ClearingTargetPool {
    private ClearingTargetPool() {}

    /** Call after server tags are available. Returns an immutable snapshot of the pool. */
    public static Set<Material> allowedMaterials() {
        EnumSet<Material> allowed = EnumSet.noneOf(Material.class);
        allowed.addAll(Tag.MINEABLE_PICKAXE.getValues());
        allowed.addAll(Tag.MINEABLE_SHOVEL.getValues());
        for (Tag<Material> ores : java.util.List.of(Tag.COAL_ORES, Tag.COPPER_ORES, Tag.IRON_ORES,
                Tag.GOLD_ORES, Tag.REDSTONE_ORES, Tag.LAPIS_ORES, Tag.DIAMOND_ORES, Tag.EMERALD_ORES)) {
            allowed.removeAll(ores.getValues());
        }
        allowed.remove(Material.NETHER_QUARTZ_ORE);
        allowed.remove(Material.ANCIENT_DEBRIS);
        allowed.remove(Material.SPAWNER);
        return Collections.unmodifiableSet(allowed);
    }
}
