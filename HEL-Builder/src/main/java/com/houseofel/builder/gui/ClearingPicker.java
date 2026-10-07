package com.houseofel.builder.gui;

import com.houseofel.builder.npc.Specialization;
import org.bukkit.Material;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Shared search and authorization rules for all three Clearing wizards. */
public final class ClearingPicker {
    private static final List<Material> COMMON_PICKS = List.of(Material.DIRT, Material.STONE,
            Material.GRAVEL, Material.SAND, Material.DEEPSLATE, Material.GRASS_BLOCK);

    public enum EverythingState { HIDDEN, LOCKED, AVAILABLE }

    private ClearingPicker() {}

    public static EverythingState everythingState(Specialization specialization, int level) {
        if (specialization != Specialization.GROUNDWORKER) return EverythingState.HIDDEN;
        return level >= 3 ? EverythingState.AVAILABLE : EverythingState.LOCKED;
    }

    public static String query(String text) { return text == null ? "" : text.strip(); }

    /** Empty input means the confirmed quick picks; searches return every match. */
    public static List<Material> search(String text) {
        Set<Material> pool = ClearingTargetPool.allowedMaterials();
        String query = query(text).toLowerCase(Locale.ROOT).replace("minecraft:", "")
                .replace('_', ' ').strip();
        if (query.isEmpty()) return COMMON_PICKS.stream().filter(pool::contains).toList();
        String[] words = query.split("\\s+");
        return pool.stream().filter(material -> {
            String name = Target.blockLabel(material).toLowerCase(Locale.ROOT);
            return Arrays.stream(words).allMatch(name::contains);
        }).sorted(Comparator.comparing(Target::blockLabel)).toList();
    }

    /** Rechecked at pick and submit time, including after class/level changes. */
    public static boolean isAllowed(Target target, Specialization specialization, int level) {
        if (target == null) return false;
        if (target == Target.ANY_EARTH) {
            return everythingState(specialization, level) == EverythingState.AVAILABLE;
        }
        return target.isSpecificBlock()
                && ClearingTargetPool.allowedMaterials().contains(target.specificMaterial());
    }
}
