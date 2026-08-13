package com.houseofel.builder.npc;

import com.houseofel.builder.gui.Target;
import com.houseofel.builder.gui.TaskType;
import org.bukkit.Material;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Per-NPC XP, level, dig speed, harvest-tier gating, and specialization bonus-drop rolls
 * — replaces the old tool-tier/toolbelt system per the Masterfile's "Leveling &
 * specialization" section. XP is tracked per individual NPC (like a pet growing with
 * use), not shared across a specialization type.
 */
public final class HelperLevelService {

    /**
     * TEMPORARY, FOR TESTING ONLY — real value is 50 (level 7 at 300 blocks, level 14 at
     * 650). Dropped low so the level-7/level-14 speed and tier breakpoints are reachable
     * within a single test job instead of requiring hundreds of blocks cleared first.
     * Must go back to 50 once the leveling behavior itself is confirmed working.
     */
    private static final int XP_PER_LEVEL = 5;
    private static final int MAX_LEVEL = 20;
    private static final double BONUS_DROP_CHANCE = 0.15;

    private final HelperLevelStore store;

    public HelperLevelService(Plugin plugin) {
        this.store = new HelperLevelStore(plugin);
    }

    /** Called once at spawn time, when the player picks the NPC's specialization. */
    public void assign(int npcId, Specialization specialization) {
        store.put(npcId, new HelperLevelRecord(specialization, 0));
        store.saveAll();
    }

    /** NPCs spawned before 1-F have no record — they still earn XP, just with no specialization bonus. */
    public void awardXp(int npcId, int amount) {
        HelperLevelRecord record = store.get(npcId);
        if (record == null) {
            record = new HelperLevelRecord(null, 0);
            store.put(npcId, record);
        }
        record.xp += amount;
    }

    public int levelOf(int npcId) {
        HelperLevelRecord record = store.get(npcId);
        if (record == null) {
            return 1;
        }
        return Math.min(MAX_LEVEL, record.xp / XP_PER_LEVEL + 1);
    }

    /** Ticks required to dig one block once in range — shrinks as the NPC levels up. */
    public int digTicksFor(int npcId) {
        int level = levelOf(npcId);
        if (level >= HarvestTier.TIER_3.unlockedAtLevel()) {
            return 1;
        }
        if (level >= HarvestTier.TIER_2.unlockedAtLevel()) {
            return 2;
        }
        return 3;
    }

    public boolean canHarvest(int npcId, Material material) {
        return levelOf(npcId) >= HarvestTier.requiredTier(material).unlockedAtLevel();
    }

    /** True on a specialization-bonus hit for this target — one extra matching drop. */
    public boolean rollBonusDrop(int npcId, Target target) {
        HelperLevelRecord record = store.get(npcId);
        if (record == null || record.specialization == null
                || record.specialization.taskType() != matchingTaskType(target)) {
            return false;
        }
        return ThreadLocalRandom.current().nextDouble() < BONUS_DROP_CHANCE;
    }

    public void saveAll() {
        store.saveAll();
    }

    /**
     * Which specialization a Clearing target rewards. Groundworker (the area-clearer —
     * what this codebase's "Miner" actually was) covers Stone and Dirt, its own real
     * targets. A distinct ore-seeking Miner is a separate future specialization, not a
     * {@link Specialization} value yet, and isn't reachable through Clearing at all.
     */
    private static TaskType matchingTaskType(Target target) {
        return switch (target) {
            case STONE, DIRT -> TaskType.CLEAR;
            case OAK_LOG -> TaskType.LUMBERJACK;
            case WHEAT -> TaskType.FARM;
        };
    }
}
