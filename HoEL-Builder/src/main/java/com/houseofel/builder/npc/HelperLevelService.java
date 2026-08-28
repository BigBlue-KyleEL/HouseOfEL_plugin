package com.houseofel.builder.npc;

import com.houseofel.builder.antigrind.DailyTaperStore;
import com.houseofel.builder.antigrind.PresenceTracker;
import com.houseofel.builder.antigrind.VarietyTracker;
import com.houseofel.builder.choice.MilestoneChoiceOption;
import com.houseofel.builder.choice.MilestoneChoiceRecord;
import com.houseofel.builder.choice.MilestoneChoiceRegistry;
import com.houseofel.builder.choice.MilestoneChoiceStore;
import com.houseofel.builder.death.DeathRecordStore;
import com.houseofel.builder.death.HelperRust;
import com.houseofel.builder.death.RustState;
import com.houseofel.builder.death.ScarChoice;
import com.houseofel.builder.gui.Target;
import com.houseofel.builder.gui.TaskType;
import com.houseofel.builder.timing.HelperTempo;
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
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
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
import java.util.stream.Collectors;

/**
 * Per-NPC Toil ledger, level, dig speed, harvest-tier gating, and specialization
 * bonus-drop rolls — the Phase 1-F chassis. Replaces the earlier flat XP-per-block
 * prototype entirely: Toil is awarded per finished work ticket (never per block), backed
 * by SQLite (authoritative, per the framework) rather than the old per-NPC YAML files.
 * Keyed by {@link NPC#getUniqueId()} — Citizens' own stable UUID, not {@link NPC#getId()}
 * or the entity's Minecraft UUID, since only the Citizens UUID survives entity
 * replacement (verified against the real Citizens jar).
 *
 * <p>Ledger rows are cached in memory once read, since {@link #levelOf} is consulted
 * every tick a Helper is digging (pacing reads it constantly) — a SQLite round-trip that
 * often would be wasteful. Every write updates the cache and the database together, so
 * they never drift.
 *
 * <p>Level and banked Toil are also mirrored onto the NPC entity's
 * {@link PersistentDataContainer} on every write, per the framework's storage rule
 * ("SQLite is authoritative... PDC alone does not survive entity replacement"). SQLite
 * remains the source of truth read from everywhere above — the PDC copy is pure
 * defense-in-depth, never read back by this class.
 */
public final class HelperLevelService {

    private static final double BONUS_DROP_CHANCE = 0.15;
    /** WARY's 3rd-scar choice — see the Death Policy plan. Small enough to read inline rather than its own class. */
    private static final double WARY_DUTY_PENALTY = 0.03;

    private final Logger logger;
    private final ToilLedgerStore store;
    private final BalanceEstimator estimator;
    private final ToilPipeline pipeline;
    private final NamespacedKey pdcLevelKey;
    private final NamespacedKey pdcToilKey;
    private final HelperTitleService titleService;
    private final DeathRecordStore deathRecordStore;
    private final MilestoneChoiceStore choiceStore;
    private final DailyTaperStore dailyTaperStore;
    private final Map<UUID, HelperLedgerRecord> cache = new HashMap<>();
    /** In-memory ticket progress — see {@link #awardProgress} for why it isn't written through. */
    private record ProgressKey(UUID npcUuid, TicketKind kind) { }
    private final Map<ProgressKey, Integer> progressCache = new HashMap<>();

    public HelperLevelService(Plugin plugin, ToilDatabase database, HelperTitleService titleService,
                               DeathRecordStore deathRecordStore, MilestoneChoiceStore choiceStore,
                               VarietyTracker varietyTracker, PresenceTracker presenceTracker,
                               DailyTaperStore dailyTaperStore) {
        this.logger = plugin.getLogger();
        this.store = new ToilLedgerStore(database, logger);
        this.estimator = new BalanceEstimator();
        this.pipeline = new ToilPipeline(varietyTracker, presenceTracker, deathRecordStore);
        this.pdcLevelKey = new NamespacedKey(plugin, "toil-level");
        this.pdcToilKey = new NamespacedKey(plugin, "toil-banked");
        this.titleService = titleService;
        this.deathRecordStore = deathRecordStore;
        this.choiceStore = choiceStore;
        this.dailyTaperStore = dailyTaperStore;
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
        long timestamp = System.currentTimeMillis();
        // Read once here rather than letting the pipeline's daily-taper stage query it
        // itself — this same value is reused for the recordAward write below too, so the
        // row is only ever queried once per ticket, not twice.
        int dailyCumulativeBefore = dailyTaperStore.cumulativeToilFor(npc.getUniqueId(), timestamp);
        TicketContext context = new TicketContext(npc.getUniqueId(), record.specialization(), kind, rawMinutes,
                orderId, timestamp, dailyCumulativeBefore);
        int finalToil = pipeline.award(context);

        RustState rust = deathRecordStore.rustFor(npc.getUniqueId());
        if (rust != null) {
            finalToil = HelperRust.toilFor(finalToil);
        }

        if (!store.logTicket(orderId, npc.getUniqueId(), kind, rawMinutes, finalToil)) {
            return null;
        }
        // Same timestamp AND same already-read cumulative value the pipeline's daily-taper
        // stage just checked against — a ticket landing right at a day boundary can't
        // check and record against two different day keys, and the row isn't queried twice.
        dailyTaperStore.recordAward(npc.getUniqueId(), timestamp, dailyCumulativeBefore, finalToil);

        int oldLevel = record.level();
        int newBankedToil = record.bankedToil() + finalToil;
        int newLevel = LevelCurve.levelForToil(newBankedToil);

        HelperLedgerRecord updated = new HelperLedgerRecord(npc.getUniqueId(), record.specialization(), newLevel, newBankedToil);
        store.save(updated);
        cache.put(npc.getUniqueId(), updated);
        mirrorToPdc(npc, updated);

        if (rust != null) {
            progressRust(npc.getUniqueId(), rust, finalToil);
        }

        boolean leveledUp = newLevel > oldLevel;
        List<String> announcementLines;
        if (leveledUp) {
            titleService.applyTitle(npc, record.specialization(), newLevel);
            announcementLines = announcementLinesFor(npc, newLevel);
            announceChoiceIfApplicable(npc, newLevel, record.specialization());
        } else {
            announcementLines = List.of();
        }
        return new TicketAwardResult(finalToil, leveledUp, newLevel, announcementLines);
    }

    /** Counts this award's (already-halved) Toil against the 200 needed to clear Rust; clears it at zero. */
    private void progressRust(UUID npcUuid, RustState rust, int toilEarned) {
        int remaining = rust.toilRemaining() - toilEarned;
        if (remaining <= 0) {
            deathRecordStore.clearRust(npcUuid);
        } else {
            deathRecordStore.saveRust(new RustState(rust.npcUuid(), remaining, rust.deathWorld(),
                    rust.deathX(), rust.deathY(), rust.deathZ(), rust.startedAt()));
        }
    }

    /**
     * Death Policy's rank-floor fallback — the one place a level can be lost. Floors both
     * level and banked Toil down to the last rank actually reached (or 1, if never ranked),
     * so a later ticket award derives the SAME floored level from Toil via
     * {@link LevelCurve#levelForToil} rather than silently restoring the old one.
     */
    public void applyRankFloor(NPC npc) {
        HelperLedgerRecord record = recordOf(npc);
        if (record == null) {
            return;
        }
        Rank floor = Rank.floorFor(record.level());
        int flooredLevel = floor == null ? 1 : floor.level();
        if (flooredLevel >= record.level()) {
            return;
        }
        HelperLedgerRecord updated = new HelperLedgerRecord(
                record.npcUuid(), record.specialization(), flooredLevel, LevelCurve.toilThresholdFor(flooredLevel));
        store.save(updated);
        cache.put(npc.getUniqueId(), updated);
        mirrorToPdc(npc, updated);
    }

    /** True while this Helper still refuses to enter the exact block it died in (Rust's site-refusal). */
    public boolean isDeathSiteBlocked(NPC npc, Location candidate) {
        RustState rust = deathRecordStore.rustFor(npc.getUniqueId());
        return rust != null && HelperRust.blocksReentry(rust, candidate, System.currentTimeMillis());
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
        int stored = progressCache.computeIfAbsent(key, k -> store.progressFor(k.npcUuid(), k.kind()));
        if (stored >= unitsPerTicket) {
            // A healthy value can never persist at or past the threshold — the while loop
            // below always fires and reduces it below unitsPerTicket before returning. If
            // we ever read one that's already there, unitsPerTicket itself must have
            // changed since this was banked (e.g. the anti-grind chassis's per-block
            // quarter-unit scaling, or a GROUNDWORKER_TICKET_BLOCKS testing-knob flip) —
            // the stored number no longer means what the current scale thinks it means.
            // Discard rather than risk an instant burst of tickets from stale-scale data.
            logger.warning("Discarding stale ticket_progress for NPC " + npc.getId() + "/" + kind
                    + ": stored " + stored + " already >= current threshold " + unitsPerTicket
                    + " — likely a unit-scale change since this was banked.");
            stored = 0;
        }
        int total = stored + newUnits;

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
        // Groundworker's level-3 and level-6 verbs, "Clears Anything" and "Bulkhead" —
        // the only milestone slots with real perk content so far. Every other
        // VERB/CHOICE/CAPSTONE slot has no content yet (later Phase 1-F items) — log
        // those server-side rather than claim something player-facing that isn't there.
        if (newLevel == 3 && specializationOf(npc) == Specialization.GROUNDWORKER) {
            lines.add(BuilderNpcService.baseNameOf(npc)
                    + ": No more picking one material at a time — mark a region as Anything and I'll clear everything in it.");
        } else if (newLevel == 6 && specializationOf(npc) == Specialization.GROUNDWORKER) {
            lines.add(BuilderNpcService.baseNameOf(npc)
                    + ": Flooding won't stop me anymore — sponges for water, a plug for lava, then I keep clearing.");
            List<MilestoneChoiceOption> preview = MilestoneChoiceRegistry.optionsFor(Specialization.GROUNDWORKER, 8);
            if (!preview.isEmpty()) {
                String names = preview.stream().map(MilestoneChoiceOption::label).collect(Collectors.joining(" or "));
                lines.add(BuilderNpcService.baseNameOf(npc)
                        + ": Two levels from now I'll have a choice to make — " + names + ".");
            }
        } else if (newLevel == 10 && specializationOf(npc) == Specialization.GROUNDWORKER) {
            lines.add(BuilderNpcService.baseNameOf(npc)
                    + ": Two more levels and I'll stake out every job before I start — survey the region, flag the hazards, refuse the bad ones.");
        } else if (newLevel == 12 && specializationOf(npc) == Specialization.GROUNDWORKER) {
            lines.add(BuilderNpcService.baseNameOf(npc)
                    + ": I can read a site now. Before any job, I'll survey the region — block counts, fluid hazards, spoil estimates. If something's wrong, I'll tell you instead of finding out the hard way.");
        } else if (newLevel == 14 && specializationOf(npc) == Specialization.GROUNDWORKER) {
            MilestoneChoiceRecord l8 = choiceStore.find(npc.getUniqueId(), 8);
            String parentChoice = l8 != null ? l8.choice() : null;
            List<MilestoneChoiceOption> preview = MilestoneChoiceRegistry.optionsFor(Specialization.GROUNDWORKER, 16, parentChoice);
            if (!preview.isEmpty()) {
                String names = preview.stream().map(MilestoneChoiceOption::label).collect(Collectors.joining(" or "));
                lines.add(BuilderNpcService.baseNameOf(npc)
                        + ": Two levels from now I'll have another choice to make — " + names + ".");
            }
        } else if (milestones.contains(MilestoneType.VERB) || milestones.contains(MilestoneType.CHOICE)
                || milestones.contains(MilestoneType.CAPSTONE)) {
            logger.info(BuilderNpcService.baseNameOf(npc) + " (#" + npc.getId() + ") reached level " + newLevel
                    + " — milestone slot(s) " + milestones + " reached, no perk content built yet.");
        }
        return lines;
    }

    /**
     * A real Choice-slot milestone (level 8, eventually 16) gets messaged directly to the
     * Helper's recorded OWNER, not whoever's currently running a job on it — deliberately
     * bypassing the {@link #announcementLinesFor}/{@code messagePlayer} pipe above, which
     * targets the job runner. This mirrors how {@code HelperDeathListener} splits its
     * public "has fallen" broadcast from its owner-only WARY/HARDENED prompt: a Choice
     * offer is a decision about the Helper's future, not feedback about a job, so it has
     * to reach the owner regardless of who dispatched it most recently. A no-op if this
     * (specialization, level) has no real Choice content registered yet, or if the owner
     * isn't currently online to receive it — they'll still see it via the right-click
     * picker or {@code "<Name> report"} whenever they next interact with the Helper.
     */
    private void announceChoiceIfApplicable(NPC npc, int newLevel, Specialization specialization) {
        String parentChoice = null;
        if (newLevel > 8) {
            MilestoneChoiceRecord l8 = choiceStore.find(npc.getUniqueId(), 8);
            parentChoice = l8 != null ? l8.choice() : null;
        }
        List<MilestoneChoiceOption> options = MilestoneChoiceRegistry.optionsFor(specialization, newLevel, parentChoice);
        if (options.isEmpty()) {
            return;
        }
        UUID ownerUuid = deathRecordStore.ownerOf(npc.getUniqueId());
        Player owner = ownerUuid == null ? null : Bukkit.getPlayer(ownerUuid);
        if (owner == null) {
            return;
        }
        String name = BuilderNpcService.baseNameOf(npc);
        owner.sendMessage(Component.text(
                name + " needs your help deciding which path to take — right-click him to choose.",
                NamedTextColor.LIGHT_PURPLE));
    }

    // Pacing (dig speed, hesitation, error rate) lives in HelperTempo, and the base
    // action timing it builds on lives in VanillaTiming — the job engine reads those
    // directly. This service only answers "what level is this Helper", which is the one
    // thing those need from it.

    /**
     * Fraction of its time this Helper spends actually working, for status display and for
     * the job engine's hesitation pacing. Base value from {@link HelperTempo}, then WARY's
     * permanent −3% if chosen, then Rust's 50% floor on top if currently rusted.
     */
    public double dutyCycleOf(NPC npc) {
        double duty = HelperTempo.dutyCycleFor(levelOf(npc));
        if (deathRecordStore.scarChoiceOf(npc.getUniqueId()) == ScarChoice.WARY) {
            duty -= WARY_DUTY_PENALTY;
        }
        RustState rust = deathRecordStore.rustFor(npc.getUniqueId());
        if (rust != null) {
            duty = HelperRust.dutyCycleFor(duty);
        }
        return duty;
    }

    /** Chance this Helper fumbles a given action — base value from {@link HelperTempo}, doubled while rusted. */
    public double errorRateOf(NPC npc) {
        double error = HelperTempo.errorRateFor(levelOf(npc));
        RustState rust = deathRecordStore.rustFor(npc.getUniqueId());
        if (rust != null) {
            error = HelperRust.errorRateFor(error);
        }
        return error;
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
            case STONE, DIRT, ANY_EARTH -> TaskType.CLEAR;
            case OAK_LOG -> TaskType.LUMBERJACK;
            case WHEAT -> TaskType.FARM;
        };
    }
}
