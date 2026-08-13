package com.houseofel.builder.npc;

import com.houseofel.builder.gui.Target;
import com.houseofel.builder.gui.TaskType;
import com.houseofel.builder.title.HelperTitleService;
import com.houseofel.builder.toil.BalanceEstimator;
import com.houseofel.builder.toil.HelperLedgerRecord;
import com.houseofel.builder.toil.LevelCurve;
import com.houseofel.builder.toil.MilestoneType;
import com.houseofel.builder.toil.Rank;
import com.houseofel.builder.toil.TicketContext;
import com.houseofel.builder.toil.TicketKind;
import com.houseofel.builder.toil.ToilDatabase;
import com.houseofel.builder.toil.ToilLedgerStore;
import com.houseofel.builder.toil.ToilPipeline;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;

/**
 * Per-NPC Toil ledger, level, dig speed, harvest-tier gating, and specialization
 * bonus-drop rolls — the Phase 1-F chassis. Replaces the earlier flat XP-per-block
 * prototype entirely: Toil is awarded per finished work ticket (never per block), backed
 * by SQLite (authoritative, per the framework) rather than the old per-NPC YAML files.
 * Keyed by {@link NPC#getUniqueId()} — Citizens' own stable UUID, not {@link NPC#getId()}
 * or the entity's Minecraft UUID, since only the Citizens UUID survives entity
 * replacement (verified against the real Citizens jar).
 *
 * <p>Ledger rows are cached in memory once read, since {@link #digTicksFor} and
 * {@link #canHarvest} are called every tick a Helper is digging — a SQLite round-trip
 * that often would be wasteful. Every write updates the cache and the database together,
 * so they never drift.
 *
 * <p>Level and banked Toil are also mirrored onto the NPC entity's
 * {@link PersistentDataContainer} on every write, per the framework's storage rule
 * ("SQLite is authoritative... PDC alone does not survive entity replacement"). SQLite
 * remains the source of truth read from everywhere above — the PDC copy is pure
 * defense-in-depth, never read back by this class.
 */
public final class HelperLevelService {

    private static final double BONUS_DROP_CHANCE = 0.15;

    private final Logger logger;
    private final ToilLedgerStore store;
    private final BalanceEstimator estimator;
    private final ToilPipeline pipeline;
    private final NamespacedKey pdcLevelKey;
    private final NamespacedKey pdcToilKey;
    private final HelperTitleService titleService;
    private final Map<UUID, HelperLedgerRecord> cache = new HashMap<>();
    /** In-memory ticket progress — see {@link #awardProgress} for why it isn't written through. */
    private record ProgressKey(UUID npcUuid, TicketKind kind) { }
    private final Map<ProgressKey, Integer> progressCache = new HashMap<>();

    public HelperLevelService(Plugin plugin, ToilDatabase database, HelperTitleService titleService) {
        this.logger = plugin.getLogger();
        this.store = new ToilLedgerStore(database, logger);
        this.estimator = new BalanceEstimator();
        this.pipeline = new ToilPipeline();
        this.pdcLevelKey = new NamespacedKey(plugin, "toil-level");
        this.pdcToilKey = new NamespacedKey(plugin, "toil-banked");
        this.titleService = titleService;
    }

    /** Called once at spawn time, when the player picks the NPC's specialization. */
    public void assign(NPC npc, Specialization specialization) {
        HelperLedgerRecord record = new HelperLedgerRecord(npc.getUniqueId(), specialization, 1, 0);
        store.save(record);
        cache.put(npc.getUniqueId(), record);
        mirrorToPdc(npc, record);
    }

    /** Null for a pre-1-F NPC that was never assigned a specialization. */
    public Specialization specializationOf(NPC npc) {
        HelperLedgerRecord record = recordOf(npc);
        return record == null ? null : record.specialization();
    }

    public int levelOf(NPC npc) {
        HelperLedgerRecord record = recordOf(npc);
        return record == null ? 1 : record.level();
    }

    public int bankedToilOf(NPC npc) {
        HelperLedgerRecord record = recordOf(npc);
        return record == null ? 0 : record.bankedToil();
    }

    /**
     * Completes one work ticket: estimates its raw displaced-minutes value via
     * {@link BalanceEstimator}, runs it through the 9-stage {@link ToilPipeline}, banks
     * the result, and reports a level-up if the new total crosses a threshold. Returns
     * null (with a logged warning) for an NPC with no ledger record — awarding Toil to an
     * unassigned Helper isn't meaningful — or if the generated order-id somehow collided
     * with one already logged (a genuine duplicate call that must not double-pay).
     */
    public TicketAwardResult awardTicket(NPC npc, TicketKind kind) {
        HelperLedgerRecord record = recordOf(npc);
        if (record == null) {
            logger.warning("Tried to award a Toil ticket to NPC #" + npc.getId()
                    + " (" + BuilderNpcService.baseNameOf(npc) + "), which has no ledger record.");
            return null;
        }

        int rawMinutes = estimator.rawMinutesFor(kind);
        String orderId = UUID.randomUUID().toString();
        TicketContext context = new TicketContext(npc.getUniqueId(), record.specialization(), kind, rawMinutes, orderId);
        int finalToil = pipeline.award(context);

        if (!store.logTicket(orderId, npc.getUniqueId(), kind, rawMinutes, finalToil)) {
            return null;
        }

        int oldLevel = record.level();
        int newBankedToil = record.bankedToil() + finalToil;
        int newLevel = LevelCurve.levelForToil(newBankedToil);

        HelperLedgerRecord updated = new HelperLedgerRecord(npc.getUniqueId(), record.specialization(), newLevel, newBankedToil);
        store.save(updated);
        cache.put(npc.getUniqueId(), updated);
        mirrorToPdc(npc, updated);

        boolean leveledUp = newLevel > oldLevel;
        List<String> announcementLines;
        if (leveledUp) {
            titleService.applyTitle(npc, record.specialization(), newLevel);
            announcementLines = announcementLinesFor(npc, newLevel);
        } else {
            announcementLines = List.of();
        }
        return new TicketAwardResult(finalToil, leveledUp, newLevel, announcementLines);
    }

    /**
     * Adds newly-completed progress toward a threshold-based ticket (e.g. Groundworker's
     * "512 blocks cleared") and fires as many tickets as the accumulated total now
     * crosses. Progress is banked per-Helper, not per-job — so a Helper sent out on many
     * separate small jobs still accumulates toward its next ticket instead of that
     * progress resetting to zero every time one job ends. Returns the tickets actually
     * fired (usually none, since this is called once per block cleared).
     *
     * <p>Progress is held in memory and only written to SQLite when a ticket actually
     * fires (plus on {@link #flushProgress()} at shutdown) — this is called on every
     * single block a Helper breaks, and a DB round-trip per block would be real
     * main-thread cost for no benefit. A force-kill can therefore lose partial progress
     * toward the next ticket, the same accepted tradeoff the pre-SQLite store already
     * made for XP, and never a banked ticket or a level.
     */
    public List<TicketAwardResult> awardProgress(NPC npc, TicketKind kind, int unitsPerTicket, int newUnits) {
        ProgressKey key = new ProgressKey(npc.getUniqueId(), kind);
        int total = progressCache.computeIfAbsent(key, k -> store.progressFor(k.npcUuid(), k.kind())) + newUnits;

        List<TicketAwardResult> results = new ArrayList<>();
        while (total >= unitsPerTicket) {
            total -= unitsPerTicket;
            TicketAwardResult result = awardTicket(npc, kind);
            if (result != null) {
                results.add(result);
            }
        }

        progressCache.put(key, total);
        if (!results.isEmpty()) {
            store.saveProgress(key.npcUuid(), kind, total);
        }
        return results;
    }

    /** Persists in-memory ticket progress — call on plugin disable so a clean stop loses nothing. */
    public void flushProgress() {
        for (Map.Entry<ProgressKey, Integer> entry : progressCache.entrySet()) {
            store.saveProgress(entry.getKey().npcUuid(), entry.getKey().kind(), entry.getValue());
        }
    }

    private List<String> announcementLinesFor(NPC npc, int newLevel) {
        List<String> lines = new ArrayList<>();
        lines.add(BuilderNpcService.baseNameOf(npc) + ": Reached level " + newLevel + ".");

        Set<MilestoneType> milestones = LevelCurve.milestonesAt(newLevel);
        Rank rank = Rank.at(newLevel);
        if (milestones.contains(MilestoneType.RANK) && rank != null) {
            lines.add(BuilderNpcService.baseNameOf(npc) + " is now a " + rank.label() + "!");
        }
        // VERB/CHOICE/CAPSTONE slots have no perk content yet (that's a later Phase 1-F
        // item) — log it server-side rather than claim something player-facing that isn't
        // actually there yet.
        if (milestones.contains(MilestoneType.VERB) || milestones.contains(MilestoneType.CHOICE)
                || milestones.contains(MilestoneType.CAPSTONE)) {
            logger.info(BuilderNpcService.baseNameOf(npc) + " (#" + npc.getId() + ") reached level " + newLevel
                    + " — milestone slot(s) " + milestones + " reached, no perk content built yet.");
        }
        return lines;
    }

    // Dig-speed / harvest-tier gating — unchanged behavior from the flat-XP prototype,
    // just reading the new SQLite-backed level. The framework's real throughput model
    // (a VanillaTiming service, chassis-owned duty cycle/error rate) is its own separate
    // Phase 1-F item, not part of this chassis build — see that item before extending this.
    public int digTicksFor(NPC npc) {
        int level = levelOf(npc);
        if (level >= HarvestTier.TIER_3.unlockedAtLevel()) {
            return 1;
        }
        if (level >= HarvestTier.TIER_2.unlockedAtLevel()) {
            return 2;
        }
        return 3;
    }

    public boolean canHarvest(NPC npc, Material material) {
        return levelOf(npc) >= HarvestTier.requiredTier(material).unlockedAtLevel();
    }

    /** True on a specialization-bonus hit for this target — one extra matching drop. */
    public boolean rollBonusDrop(NPC npc, Target target) {
        Specialization specialization = specializationOf(npc);
        if (specialization == null || specialization.taskType() != matchingTaskType(target)) {
            return false;
        }
        return ThreadLocalRandom.current().nextDouble() < BONUS_DROP_CHANCE;
    }

    private HelperLedgerRecord recordOf(NPC npc) {
        return cache.computeIfAbsent(npc.getUniqueId(), uuid -> store.find(uuid));
    }

    /**
     * Defense-in-depth backup copy of level/Toil onto the NPC entity's PDC — SQLite stays
     * authoritative and is what every read in this class actually uses. A no-op if the
     * NPC isn't currently spawned (nothing to mirror onto yet); the next award after it
     * respawns mirrors again regardless, so this never needs to catch up retroactively.
     */
    private void mirrorToPdc(NPC npc, HelperLedgerRecord record) {
        Entity entity = npc.getEntity();
        if (entity == null) {
            return;
        }
        PersistentDataContainer pdc = entity.getPersistentDataContainer();
        pdc.set(pdcLevelKey, PersistentDataType.INTEGER, record.level());
        pdc.set(pdcToilKey, PersistentDataType.INTEGER, record.bankedToil());
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
