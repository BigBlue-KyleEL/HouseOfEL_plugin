package com.houseofel.builder.job;

import com.houseofel.builder.antigrind.FreshLedger;
import com.houseofel.builder.antigrind.RedundancyTracker;
import com.houseofel.builder.antigrind.TaskFingerprint;
import com.houseofel.builder.gui.BlockTool;
import com.houseofel.builder.gui.Target;
import com.houseofel.builder.gui.TaskType;
import com.houseofel.builder.npc.BuilderNpcService;
import com.houseofel.builder.npc.HelperLevelService;
import com.houseofel.builder.npc.Specialization;
import com.houseofel.builder.npc.TicketAwardResult;
import com.houseofel.builder.region.RegionOutline;
import com.houseofel.builder.timing.HelperTempo;
import com.houseofel.builder.timing.VanillaTiming;
import com.houseofel.builder.toil.TicketKind;
import net.citizensnpcs.api.npc.NPC;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Levelled;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Silverfish;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;

/**
 * Runs one Clearing job for one Helper — walks to each block, digs it, then moves on,
 * so the pace comes from real travel time rather than an arbitrary throttle. Skips
 * anything not exposed to open sky in surface mode, so it clears surface material
 * instead of tunnelling through terrain.
 *
 * <p>Can be paused mid-run (via {@link #pause()}) and resumed in the same server
 * session (via {@link #resumeTicking()}) without losing a beat, since the in-memory
 * state is left untouched. A full server restart is coarser: {@link #resume} rebuilds
 * a job from a saved {@link JobState} and always restarts at the top of a seek, since a
 * stale mid-walk/mid-dig target genuinely isn't safe to trust after time has passed.
 */
public final class ClearJobTask {

    /**
     * How close the NPC must be to dig a block. Deliberately past the vanilla ~4.5
     * survival reach — Helpers are specialists, so they work a little wider than a player.
     */
    private static final double REACH_DISTANCE = 5.5;
    /**
     * Movement speed while working. Vanilla sprinting is ~5.6 blocks/sec against
     * ~4.3 walking, so ~1.3x reads as a player jogging between blocks.
     */
    private static final float WALK_SPEED_MODIFIER = 1.3f;
    /** Pathfinding range in blocks. Citizens defaults to 25 and caps at 100. */
    private static final float PATHFINDING_RANGE = 100.0f;
    /**
     * Basic ledge/fall-risk avoidance — how far a path is allowed to drop the NPC in one
     * go. Also doubles as the dig-band height {@link #seekNextBlock()} uses (2026-08-18):
     * a tall region is worked in stacked Y-bands of at most this many layers, each fully
     * exhausted (through its own full multi-pass retry budget) before the band below it
     * is ever touched — so the floor immediately under wherever the NPC is currently
     * digging is never more than this many blocks down, even in the worst case where
     * every reachable block in the current band ends up cleared. Confirmed live
     * 2026-08-18 that without this, a tall region's second/third retry pass (mopping up
     * scattered stragglers the first pass missed) can leave a Helper's own footing as the
     * literal last block standing in an otherwise fully hollow column — digging it out
     * dropped Horace clean through the shaft he'd already dug. {@link JobFallProtectionListener}
     * is a separate, additional safety net on top of this, not a replacement for it.
     */
    private static final int MAX_TOLERATED_FALL = 3;
    /** Grace period for the navigator to actually start moving before we call it a failed path. */
    private static final int PATH_GRACE_TICKS = 20;
    /**
     * Fallback working distance. If the NPC walked as close as it could but still can't
     * stand beside the block, it works from here rather than leaving a gap in the job.
     */
    private static final double MAX_WORK_DISTANCE = 12.0;
    /**
     * "GhostDig" (Kyle's own name, from this project's very first pathing pass) — the
     * original spec: "unbounded [reach] from pass 3, once IT has genuinely tried twice."
     * Corrected 2026-08-19: the original implementation approximated "tried twice" with
     * this BAND's shared sweep-pass counter, which works fine when only a few cells are
     * ever simultaneously stuck, but breaks down hard when many are (a narrow stairway
     * next to an old pit, live-confirmed) — every stuck cell then has to wait on every
     * OTHER stuck cell in the same band to also fail twice before any of them gets
     * unbounded reach, since they all share one counter. Replaced with a genuinely
     * per-candidate attempt count (see {@link #stuckAttempts}), matching the spec's own
     * "once IT has tried twice" wording literally instead of approximating it band-wide.
     */
    private static final int GHOST_DIG_MIN_ATTEMPTS = 3;
    /**
     * How long a scaffold climb (see {@link #attemptScaffoldUp}) stays up before it's
     * cleared away — long enough to dig the block that triggered it plus maybe a couple
     * more nearby, short enough not to visibly linger for the rest of the job. Kyle's own
     * follow-up call, 2026-08-19: "make it disappear like sponges and cobblestone" (see
     * {@link #beginBulkhead}) rather than staying up until the whole job ends.
     */
    private static final int SCAFFOLD_LINGER_TICKS = 100;
    /**
     * How much the NPC hauls before walking a load back to the chest. Deliberately a
     * few stacks rather than a full chest's worth — a large capacity means a job of a
     * few hundred blocks never makes a visible trip.
     */
    private static final int CARRY_CAPACITY = 4 * 64;
    /**
     * TEMPORARY, FOR TESTING ONLY — set above 1 to inflate drops and exercise chest
     * overflow without clearing a huge region. Must stay 1 outside of that testing.
     */
    private static final int TEST_DROP_MULTIPLIER = 1;
    /**
     * Groundworker's real Toil ticket per the framework's XP-sources table: "512 blocks
     * excavated, smoothed or drained, spoil hauled to a real chest — 8 Toil." The only
     * live-wired ticket source as of Phase 1-F item 1 — see {@link #awardGroundworkerProgress(int)}.
     *
     * <p>Was temporarily dropped to 8 during Phase 1-F item 2 so a Helper could be walked
     * through all ten milestone titles in one sitting; restored to the real value
     * 2026-08-13 once that was confirmed working. Helpers levelled during that window
     * (Bartholomew reached 20) keep their levels — deliberate, since the dev world is
     * reset before go-live anyway.
     */
    private static final int GROUNDWORKER_TICKET_BLOCKS = 512;
    /**
     * How the anti-grind chassis represents fractional (0.25x) Redundancy Decay credit
     * without floating point or a SQLite schema change: a block contributes 0, 1, or this
     * many quarter-units instead of a flat 1, and {@code unitsPerTicket} is scaled by the
     * same factor at the one call site — everything downstream (progress accumulation,
     * the {@code ticket_progress} column) stays an opaque int, unaware anything changed.
     * See {@link #creditUnitsFor(Block)}.
     */
    private static final int CREDIT_UNITS_PER_BLOCK = 4;
    /** Squared-distance improvement that counts as real progress toward the target. */
    private static final double PROGRESS_EPSILON = 0.05;
    /**
     * Give up only after the NPC stops closing the gap for this long. A flat time limit
     * punishes long-but-legitimate walks across a big site, so judge progress instead.
     */
    private static final int NO_PROGRESS_TICKS = 20 * 3;
    /** Absolute backstop so a pathological case can't wedge a job forever. */
    private static final int WALK_TIMEOUT_TICKS = 20 * 30;
    /** Safety bound on repeat sweeps, so a genuinely unreachable block can't loop forever. */
    private static final int MAX_PASSES = 8;
    /** Upper bound on cells scanned per tick, so a big sparse region doesn't stall the server. */
    private static final int MAX_CELLS_SCANNED_PER_TICK = 10_000;
    private static final double LABEL_HEIGHT_OFFSET = 2.3;
    /** How often the constant white glow of the work-area outline is refreshed. */
    private static final int GLOW_TICK_PERIOD = 20;
    /** The yellow tint refreshes faster, so its pulse reads as a fade not a few steps. */
    private static final int TINT_TICK_PERIOD = 5;
    /** How often a little particle puff shows while walking, so a long trip isn't silent. */
    private static final int WALK_CUE_PERIOD = 10;
    /** How far away a hostile mob still counts as "nearby" for the alert. */
    private static final double MOB_ALERT_RADIUS = 10.0;
    /** How often to scan for hostile mobs — no need to check every tick. */
    private static final int MOB_ALERT_CHECK_PERIOD = GLOW_TICK_PERIOD;

    private enum Phase { SEEKING, WALKING, DIGGING, HAULING, BULKHEAD }

    /**
     * Groundworker level-6 verb ("Bulkhead," scoped to flooding only — see the
     * Development Timeline's item 6 entry for what's deferred). LAVA ONLY — water
     * breaches are handled with a free-placed {@link Material#SPONGE} instead (see
     * {@link #beginBulkhead}), since sponges don't absorb lava at all (confirmed against
     * the real NMS bytecode 2026-08-17: {@code SpongeBlock}'s absorption search checks
     * water exclusively). Free, matching {@link JobStorage}'s own "conjured for free"
     * chest placement — no material cost. Cobblestone specifically because it matches
     * neither {@link Target#STONE} nor {@link Target#DIRT}'s {@code matches()}, so a plug
     * can never later get misidentified as a real target block by {@link #isClearable}.
     */
    private static final Material BULKHEAD_PLUG_MATERIAL = Material.COBBLESTONE;
    /**
     * Flat settle timer, not polling until no fluid neighbor remains — a real lake has
     * many other source blocks that keep flowing nearby regardless of this one plug, so
     * waiting for "no fluid nearby" could spin for the rest of the job. Matches every
     * other timeout in this file ({@link #PATH_GRACE_TICKS}, {@link #NO_PROGRESS_TICKS}),
     * which are all flat constants too. Also long enough for a placed sponge's own
     * (synchronous, instant-on-placement) absorption to have already happened and for the
     * surrounding area to read as genuinely handled before the plug is cleared.
     */
    private static final int BULKHEAD_SETTLE_TICKS = 60;
    /**
     * Caps the LAVA connected-source flood-fill below — a real lava pool is a few dozen
     * source blocks at most; this stops an ocean-of-lava-scale connection from turning one
     * dig into an unbounded, server-lag-inducing fill. Doesn't apply to water anymore —
     * sponges have their own independent, engine-owned 64-block cap per placement (see
     * {@link #detectWaterBreach}), not something this codebase enforces. A lava fill that
     * hits this cap only seals part of a genuinely huge body — logged when it happens so
     * it's visible during verification.
     */
    private static final int BULKHEAD_MAX_PLUGS = 64;
    /**
     * How many sponges one Bulkhead cycle will place at most, across BOTH the flat ring
     * and the vertical accents below (see {@link #BULKHEAD_MAX_VERTICAL_SPONGES}). Each
     * independently absorbs up to 64 connected water blocks (confirmed against real NMS
     * bytecode), so this caps total reach at roughly {@code BULKHEAD_MAX_SPONGES * 64}
     * blocks if well spread out (see {@link #BULKHEAD_SPONGE_SPACING_BLOCKS}) — "not that
     * many blocks placed, but plenty enough to make a difference" (Kyle's own framing)
     * against a genuinely large body, while keeping the number of synchronous
     * placement-triggered absorption BFS calls bounded (now spread across the wave — see
     * {@link #BULKHEAD_WAVE_TICKS_PER_STEP} — rather than all in one tick, which is what
     * makes a much higher count than the original 8 affordable at all).
     *
     * <p>Raised 10 → 18 on 2026-08-18: confirmed live that 10, spread thin across a real
     * water body, read as scattered points rather than the dense mockup Kyle built — a
     * meaningfully bigger count is what actually makes a dome silhouette legible with only
     * a few seconds of visible lifetime, not spacing alone.
     */
    private static final int BULKHEAD_MAX_SPONGES = 18;
    /**
     * Of {@link #BULKHEAD_MAX_SPONGES}'s total, how many slots are reserved for anchors
     * sitting noticeably above or below the breach (see {@link #BULKHEAD_VERTICAL_DY_THRESHOLD})
     * rather than the usual flat ring — Kyle's 2026-08-18 request for the dome to "cover
     * even water sources above and below." Still ADDITIVE, never forced: the flat ring
     * fills first, and these only get used when real water actually supports them.
     *
     * <p>Raised 2 → 6 alongside {@link #BULKHEAD_MAX_SPONGES} on 2026-08-18 — Kyle's
     * mockup shows height variation across MOST of the shape, not just one or two accent
     * points, so the vertical share needed to grow proportionally with the total, not stay
     * fixed at a small constant.
     */
    private static final int BULKHEAD_MAX_VERTICAL_SPONGES = 6;
    /**
     * Minimum absolute Y-difference from the breach for a candidate to count as a
     * "vertical accent" instead of part of the flat ring. Live-tuned starting point, not
     * measured — a sloped lakebed could trip this from ordinary terrain, not real
     * vertical relief, so nudge upward if the accents don't read as deliberate.
     */
    private static final int BULKHEAD_VERTICAL_DY_THRESHOLD = 2;
    /**
     * Minimum distance (in blocks) between two chosen sponge anchor points. Sponges
     * placed right next to each other mostly compete for the SAME water within their own
     * 6-block reach rather than covering more area — confirmed live 2026-08-17 that this,
     * not merely too few sponges, was why "two isn't enough" against a real ocean.
     *
     * <p>Originally 8 (a little past the 6-block absorption radius, so reach would barely
     * touch rather than overlap) — confirmed live 2026-08-17 that 8 read as "way too far
     * apart" in practice, tuned down to 5. Confirmed live AGAIN on 2026-08-18 that 5 still
     * read as "not even remotely close" to Kyle's dense, near-touching mockup — tuned down
     * hard to 2, deliberately accepting more overlap between neighboring sponges' own
     * absorption in exchange for a visibly clustered shape; the higher
     * {@link #BULKHEAD_MAX_SPONGES} count compensates for the coverage that overlap
     * costs. Live-tuned, not mathematically derived — nudge further if it still doesn't
     * look right.
     */
    private static final int BULKHEAD_SPONGE_SPACING_BLOCKS = 2;
    /**
     * How many connected water cells {@link #detectWaterBreach} is willing to LOOK AT
     * (cheap — just reading block types) while searching for well-spread anchor points.
     * Much larger than {@link #BULKHEAD_MAX_PLUGS}'s lava cap on purpose: this only reads
     * blocks, it doesn't place anything at most of them, so scanning further to find
     * genuinely spread-out anchors in a large or oddly-shaped body is cheap and safe.
     *
     * <p>Confirmed live 2026-08-17 that the original value here (300) was badly
     * undersized against {@link #BULKHEAD_SPONGE_SPACING_BLOCKS}'s then-8-block rule:
     * clearing just the exclusion disk around a SINGLE already-placed anchor costs roughly
     * {@code π × 8² ≈ 200} explored cells on its own (math checked, not guessed), leaving
     * almost no budget to ever find a second, spaced-out anchor — every subsequent one
     * needs to clear its own zone plus every prior anchor's, compounding fast. Raised well
     * past the reasoned minimum for finding all anchors in a real, sprawling ocean shape.
     */
    private static final int BULKHEAD_WATER_EXPLORE_CELLS = 4096;
    /**
     * Ticks between each sponge landing during the wave rollout (2026-08-18, "back to
     * front wave kind animation" — Kyle's own framing) — roughly 5 sponges/second. Piggybacks
     * on this task's own existing per-tick loop ({@link #tick()}) rather than a separate
     * scheduler, matching how {@link #bulkheadTicks} and {@code outlineTicks} already drive
     * timed behavior. Live-tuned starting point, not measured.
     */
    private static final int BULKHEAD_WAVE_TICKS_PER_STEP = 4;

    private final Plugin plugin;
    private final Logger logger;
    private final JobManager jobManager;
    private final HelperLevelService levelService;
    private final RedundancyTracker redundancyTracker;
    private final FreshLedger freshLedger;
    private final UUID playerId;
    private final NPC npc;
    private final Entity npcEntity;
    private final EntityEquipment equipment;
    private final TextDisplay label;
    private final World world;
    private final Target target;
    /** The tool currently equipped — recomputed per block in {@link #beginDigging()}, not fixed for the whole job. */
    private Material currentTool;
    private final boolean surfaceOnly;
    private final boolean storeInChest;
    private final int savedMinX;
    private final int savedMaxX;
    private final int savedMinY;
    private final int savedMaxY;
    private final int savedMinZ;
    private final int savedMaxZ;
    private final int minX;
    private final int maxY;
    private final int minZ;
    private final int spanX;
    private final int spanZ;
    private final long totalCells;
    private final JobStorage storage;
    private final RegionOutline outline;
    private int outlineTicks;
    private final Map<Material, Integer> carried;
    private int carriedTotal;
    private Location depositPoint;

    private Phase phase;
    /**
     * Ticks left dithering before the next action — the duty-cycle refinement made
     * concrete. Shrinks as the Helper levels. See {@link HelperTempo#hesitationTicksFor}.
     */
    private int hesitationTicks;
    private long processedCells;
    private long clearedCells;
    private long deposited;
    /** Blocks fumbled and left for a later sweep — the error-rate refinement. */
    private long fumbled;
    private long clearedThisPass;
    private long skippedThisPass;
    private long skippedNoPath;
    private long skippedTimeout;
    private int passNumber;
    /** True once every band has been swept at least once and the final whole-region mop-up pass has started. */
    private boolean reconciling;
    private Block pendingBlock;
    /** Flat sweep index of {@link #pendingBlock} — the key {@link #stuckAttempts} tracks failures by. */
    private long pendingBlockIndex;
    /** Per-candidate failure count, keyed by sweep index — see GHOST_DIG_MIN_ATTEMPTS. Small and self-bounded (never bigger than the region's own cell count), no explicit cleanup needed. */
    private final Map<Long, Integer> stuckAttempts = new HashMap<>();
    private int walkTicks;
    private int noProgressTicks;
    private double closestApproachSquared;
    private int digTicks;
    private boolean usedStraightLine;
    private boolean usedScaffold;
    private boolean jobComplete;
    private boolean paused;
    /** Guards the 50%/85% progress pings so each fires exactly once per job. */
    private boolean announced50;
    private boolean announced85;
    private BukkitTask task;
    /** Hostile mobs already alerted about, so the same one doesn't re-trigger every scan. */
    private final Set<UUID> alertedMobs = new HashSet<>();
    /** Fluid source blocks currently plugged, awaiting {@link #tickBulkhead()}'s settle timer. */
    private final List<Block> bulkheadPlugs = new ArrayList<>();
    /** Temporary scaffolding placed by {@link #attemptScaffoldUp}, cleared back to air at job end. */
    private final List<Block> scaffoldBlocks = new ArrayList<>();
    private int bulkheadTicks;
    /**
     * Water anchors still waiting to land during the wave rollout, in placement order
     * (flat ring furthest-first, then the vertical accents last) — see
     * {@link #detectWaterBreach} for how this order is built and {@link #tickBulkhead()}
     * for how it's drained one anchor at a time.
     */
    private final List<Block> bulkheadWaveAnchors = new ArrayList<>();
    private int bulkheadWaveIndex;
    private int bulkheadWaveStepTicks;
    /** Chunks currently held open via a plugin ticket — see {@link #refreshChunkTickets()}. */
    private final Set<Long> ticketedChunks = new HashSet<>();
    /**
     * Set once at construction (fresh dispatch or restart-resume) and never touched by a
     * same-session pause/resume, so {@link #estimatedRemainingMillis()} measures against
     * the job's true start rather than resetting its baseline every time it's paused.
     */
    private final long startedAtMillis = System.currentTimeMillis();

    /** Fresh job, just dispatched. */
    ClearJobTask(Plugin plugin, JobManager jobManager, HelperLevelService levelService,
                 RedundancyTracker redundancyTracker, FreshLedger freshLedger, Player player,
                 NPC npc, Entity npcEntity, EntityEquipment equipment, TextDisplay label, World world,
                 Target target, Material tool, int minX, int maxX, int minY, int maxY, int minZ, int maxZ,
                 int spanX, int spanZ, long totalCells, JobStorage storage, boolean storeInChest,
                 boolean surfaceOnly, RegionOutline outline) {
        this(plugin, jobManager, levelService, redundancyTracker, freshLedger, player.getUniqueId(), npc, npcEntity,
                equipment, label, world, target, tool, minX, maxX, minY, maxY, minZ, maxZ, spanX, spanZ, totalCells,
                storage, storeInChest, surfaceOnly, outline,
                Phase.SEEKING, 0, 0, 0, 1, 0, 0, 0, 0, new HashMap<>());
    }

    private ClearJobTask(Plugin plugin, JobManager jobManager, HelperLevelService levelService,
                          RedundancyTracker redundancyTracker, FreshLedger freshLedger, UUID playerId,
                          NPC npc, Entity npcEntity, EntityEquipment equipment, TextDisplay label, World world,
                          Target target, Material tool,
                          int minX, int maxX, int minY, int maxY, int minZ, int maxZ,
                          int spanX, int spanZ, long totalCells, JobStorage storage,
                          boolean storeInChest, boolean surfaceOnly,
                          RegionOutline outline,
                          Phase phase, long processedCells, long clearedCells, long deposited, int passNumber,
                          long clearedThisPass, long skippedThisPass, long skippedNoPath, long skippedTimeout,
                          Map<Material, Integer> carried) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.jobManager = jobManager;
        this.levelService = levelService;
        this.redundancyTracker = redundancyTracker;
        this.freshLedger = freshLedger;
        this.playerId = playerId;
        this.npc = npc;
        this.npcEntity = npcEntity;
        this.equipment = equipment;
        this.label = label;
        this.world = world;
        this.target = target;
        this.currentTool = tool;
        this.savedMinX = minX;
        this.savedMaxX = maxX;
        this.savedMinY = minY;
        this.savedMaxY = maxY;
        this.savedMinZ = minZ;
        this.savedMaxZ = maxZ;
        this.minX = minX;
        this.maxY = maxY;
        this.minZ = minZ;
        this.spanX = spanX;
        this.spanZ = spanZ;
        this.totalCells = totalCells;
        this.storage = storage;
        this.storeInChest = storeInChest;
        this.surfaceOnly = surfaceOnly;
        this.outline = outline;
        this.phase = phase;
        this.processedCells = processedCells;
        this.clearedCells = clearedCells;
        this.deposited = deposited;
        this.passNumber = passNumber;
        this.clearedThisPass = clearedThisPass;
        this.skippedThisPass = skippedThisPass;
        this.skippedNoPath = skippedNoPath;
        this.skippedTimeout = skippedTimeout;
        this.carried = carried;
        this.carriedTotal = carried.values().stream().mapToInt(Integer::intValue).sum();
        // Derived from restored progress rather than persisted separately, so a job
        // resumed past a milestone doesn't re-announce it.
        this.announced50 = percentComplete() >= 50;
        this.announced85 = percentComplete() >= 85;
    }

    /**
     * Rebuilds a job from a saved snapshot after a restart. Always restarts at the top
     * of a seek with the saved cell cursor — a stale mid-walk/mid-dig target isn't worth
     * trusting after the world may have changed underneath it. Returns null if the
     * world, target, or tool this job needs no longer resolves.
     */
    static ClearJobTask resume(Plugin plugin, JobManager jobManager, HelperLevelService levelService,
                                RedundancyTracker redundancyTracker, FreshLedger freshLedger, JobState state, NPC npc) {
        World world = Bukkit.getWorld(state.worldName);
        Entity npcEntity = npc.getEntity();
        if (world == null || npcEntity == null) {
            return null;
        }
        // Bulkhead itself doesn't need to survive a restart — resuming at Phase.SEEKING
        // and re-detecting fresh is fine, same as every other mid-action phase. But a
        // plug left over from a restart mid-wait would otherwise be a permanent stray
        // cobblestone/sponge block, since processedCells already advanced past that
        // position before it was ever dug. Force-clear any saved plugs unconditionally
        // (guarded on the block still being a plug material, in case a player already
        // dealt with it) — sponge auto-converts to wet sponge after absorbing, so both
        // states need covering.
        for (String encoded : state.bulkheadPlugs) {
            Block plug = JobStorage.decodeBlock(world, encoded);
            Material plugType = plug.getType();
            if (plugType == BULKHEAD_PLUG_MATERIAL || plugType == Material.SPONGE || plugType == Material.WET_SPONGE) {
                plug.setType(Material.AIR);
                plugin.getLogger().info("[groundworker] Bulkhead: cleared stray plug at resume for NPC #"
                        + state.npcId + " at " + plug.getX() + "," + plug.getY() + "," + plug.getZ());
            }
        }
        Target target;
        try {
            target = Target.valueOf(state.target);
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Couldn't resume job for NPC #" + state.npcId
                    + " — unrecognised target: " + e.getMessage());
            return null;
        }
        // Purely a cosmetic placeholder for the few ticks before the resumed job reaches
        // its first block — resume() always restarts at Phase.SEEKING (below), so
        // beginDigging() corrects this to the real per-block tool before any real
        // digging happens. There's nothing meaningful to actually resume a tool "to."
        Material initialTool = Material.IRON_SHOVEL;

        int spanX = state.maxX - state.minX + 1;
        int spanZ = state.maxZ - state.minZ + 1;
        long totalCells = (long) spanX * spanZ * (state.maxY - state.minY + 1);

        JobStorage storage = null;
        if (state.storeInChest) {
            storage = new JobStorage(plugin, world, state.minX, state.maxX, state.minY, state.maxY,
                    state.minZ, state.maxZ);
            List<Block> chests = new ArrayList<>();
            for (String encoded : state.chests) {
                chests.add(JobStorage.decodeBlock(world, encoded));
            }
            Set<Long> occupiedColumns = new HashSet<>();
            for (String encoded : state.occupiedColumns) {
                occupiedColumns.add(JobStorage.decodeColumn(encoded));
            }
            Block anchor = state.anchor == null ? null : JobStorage.decodeBlock(world, state.anchor);
            Block lastCubeAnchor = state.lastCubeAnchor == null ? null
                    : JobStorage.decodeBlock(world, state.lastCubeAnchor);
            Block[] rowFoot = {
                state.rowFoot0 == null ? null : JobStorage.decodeBlock(world, state.rowFoot0),
                state.rowFoot1 == null ? null : JobStorage.decodeBlock(world, state.rowFoot1),
            };
            storage.restore(chests, occupiedColumns, anchor, lastCubeAnchor, rowFoot,
                    state.cubeUnitIndex, state.rowSign, state.columnSign);
        }

        EntityEquipment equipment = equipTool(npcEntity, initialTool, BuilderNpcService.baseNameOf(npc), TaskType.fromTool(initialTool).toolNoun());
        TextDisplay label = spawnLabel(npcEntity.getLocation());
        RegionOutline outline = new RegionOutline(world, state.minX, state.minY, state.minZ,
                state.maxX, state.maxY, state.maxZ);

        Map<Material, Integer> carried = new HashMap<>();
        for (var entry : state.carried.entrySet()) {
            Material material = Material.matchMaterial(entry.getKey());
            if (material != null) {
                carried.put(material, entry.getValue());
            }
        }

        return new ClearJobTask(plugin, jobManager, levelService, redundancyTracker, freshLedger, state.playerId,
                npc, npcEntity, equipment, label, world, target, initialTool, state.minX, state.maxX, state.minY,
                state.maxY, state.minZ, state.maxZ, spanX, spanZ, totalCells, storage, state.storeInChest,
                state.surfaceOnly, outline, Phase.SEEKING, state.processedCells, state.clearedCells,
                state.deposited, state.passNumber, state.clearedThisPass, state.skippedThisPass,
                state.skippedNoPath, state.skippedTimeout, carried);
    }

    static TextDisplay spawnLabel(Location npcLocation) {
        Location labelLocation = npcLocation.clone().add(0, LABEL_HEIGHT_OFFSET, 0);
        TextDisplay label = labelLocation.getWorld().spawn(labelLocation, TextDisplay.class);
        label.setBillboard(Display.Billboard.CENTER);
        label.text(Component.text("0%", NamedTextColor.YELLOW));
        return label;
    }

    /**
     * Returns the entity's equipment if it can hold one, so the job can clear it when
     * done. The equipped copy gets a per-NPC display name ("Thaddeus's Trusty Shovel")
     * for flavor — purely cosmetic, so this must never be the item passed to
     * {@link Block#getDrops(ItemStack)} in {@link #collectDrops}, which needs the plain
     * material to keep drop-table math unaffected.
     */
    static EntityEquipment equipTool(Entity npcEntity, Material tool, String npcName, String toolNoun) {
        if (!(npcEntity instanceof LivingEntity livingEntity)) {
            return null;
        }
        EntityEquipment equipment = livingEntity.getEquipment();
        if (equipment != null) {
            ItemStack item = new ItemStack(tool);
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.displayName(Component.text(npcName + "'s Trusty " + toolNoun, NamedTextColor.AQUA)
                        .decoration(TextDecoration.ITALIC, false));
                item.setItemMeta(meta);
            }
            equipment.setItemInMainHand(item);
        }
        return equipment;
    }

    NPC npc() {
        return npc;
    }

    UUID playerId() {
        return playerId;
    }

    boolean isPaused() {
        return paused;
    }

    /** Rough 0-100 estimate of how far through the sweep this job is. */
    int percentComplete() {
        return (int) (processedCells * 100 / totalCells);
    }

    /**
     * Rough remaining-time estimate, extrapolated from progress-per-elapsed-time so far.
     * Only meaningful once some real progress has been made; returns {@code Long.MAX_VALUE}
     * before that so a caller can treat "unknown" distinctly from "almost done."
     */
    long estimatedRemainingMillis() {
        int percent = percentComplete();
        if (percent <= 0) {
            return Long.MAX_VALUE;
        }
        long elapsed = System.currentTimeMillis() - startedAtMillis;
        long estimatedTotal = elapsed * 100 / percent;
        return Math.max(0, estimatedTotal - elapsed);
    }

    void start() {
        // Citizens defaults to a 25-block pathfinding range and simply gives up past it,
        // which strands the NPC on anything across a decent-sized site. 100 is the max.
        // fallDistance caps how far a path is allowed to drop the NPC in one go — Citizens'
        // NavigatorParameters has no dedicated "avoid lava" flag (checked against the real
        // jar), so lava-adjacency is handled separately via isNearLava() in isClearable().
        // stuckAction: Citizens' own bundled TeleportStuckAction can teleport onto
        // unconfirmed-solid ground when it can't find any nearby — see SafeStuckAction's
        // own doc comment for why that's a real, confirmed hazard on irregular terrain.
        npc.getNavigator().getDefaultParameters()
                .speedModifier(WALK_SPEED_MODIFIER)
                .range(PATHFINDING_RANGE)
                .fallDistance(MAX_TOLERATED_FALL)
                .stuckAction(SafeStuckAction.INSTANCE);
        paused = false;
        refreshChunkTickets();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 0L, 1L);
    }

    /** Stops ticking but keeps every in-memory field intact, so {@link #resumeTicking()} picks up mid-stride. */
    void pause() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        npc.getNavigator().cancelNavigation();
        releaseChunkTickets();
        paused = true;
    }

    void resumeTicking() {
        if (paused) {
            start();
        }
    }

    /** Stops the job for good — not a natural completion, so no "finished clearing" summary. */
    void cancelJob() {
        if (task != null) {
            task.cancel();
        }
        npc.getNavigator().cancelNavigation();
        releaseChunkTickets();
        clearBulkheadPlugs();
        clearScaffoldBlocks();
        bulkheadWaveAnchors.clear();
        bulkheadWaveIndex = 0;
        label.remove();
        if (equipment != null) {
            equipment.setItemInMainHand(null);
        }
        jobManager.onJobEnded(npc.getId());
        logger.info(BuilderNpcService.baseNameOf(npc) + "'s job was cancelled ["
                + clearedCells + " cleared, " + deposited + " stored]");
    }

    /** Clears any Bulkhead plug blocks back to air — shared by every teardown path so none of them can strand one. */
    private void clearBulkheadPlugs() {
        for (Block plug : bulkheadPlugs) {
            plug.setType(Material.AIR);
        }
        bulkheadPlugs.clear();
    }

    /**
     * Keeps the chunk the Helper is standing in — plus wherever it's headed next — force
     * loaded, so the job survives nobody being nearby instead of dying the moment its
     * chunk would otherwise unload. Deliberately narrow (just the immediate work area,
     * not the whole region) rather than paying to keep a possibly enormous region
     * resident the entire time a job runs. Goes through {@link JobManager} rather than
     * the world directly, since Paper's ticket API is keyed by (chunk, plugin) and
     * another job could need the same chunk at the same time.
     */
    private void refreshChunkTickets() {
        Set<Long> desired = new HashSet<>();
        desired.add(chunkKey(npcEntity.getLocation()));
        if (pendingBlock != null) {
            desired.add(chunkKey(pendingBlock.getLocation()));
        } else if (phase == Phase.HAULING && depositPoint != null) {
            desired.add(chunkKey(depositPoint));
        }

        for (long key : desired) {
            if (ticketedChunks.add(key)) {
                jobManager.requestChunk(world, chunkX(key), chunkZ(key));
            }
        }
        ticketedChunks.removeIf(key -> {
            if (desired.contains(key)) {
                return false;
            }
            jobManager.releaseChunk(world, chunkX(key), chunkZ(key));
            return true;
        });
    }

    private void releaseChunkTickets() {
        for (long key : ticketedChunks) {
            jobManager.releaseChunk(world, chunkX(key), chunkZ(key));
        }
        ticketedChunks.clear();
    }

    private static long chunkKey(Location location) {
        return chunkKey(location.getBlockX() >> 4, location.getBlockZ() >> 4);
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return (((long) chunkX) << 32) ^ (chunkZ & 0xffffffffL);
    }

    private static int chunkX(long key) {
        return (int) (key >> 32);
    }

    private static int chunkZ(long key) {
        return (int) key;
    }

    /** Snapshots current progress for persistence — see {@link #resume} for the inverse. */
    JobState toJobState() {
        JobState state = new JobState();
        state.npcId = npc.getId();
        state.playerId = playerId;
        state.worldName = world.getName();
        state.minX = savedMinX;
        state.maxX = savedMaxX;
        state.minY = savedMinY;
        state.maxY = savedMaxY;
        state.minZ = savedMinZ;
        state.maxZ = savedMaxZ;
        state.target = target.name();
        state.surfaceOnly = surfaceOnly;
        state.storeInChest = storeInChest;
        state.processedCells = processedCells;
        state.clearedCells = clearedCells;
        state.deposited = deposited;
        state.passNumber = passNumber;
        state.clearedThisPass = clearedThisPass;
        state.skippedThisPass = skippedThisPass;
        state.skippedNoPath = skippedNoPath;
        state.skippedTimeout = skippedTimeout;
        for (var entry : carried.entrySet()) {
            state.carried.put(entry.getKey().name(), entry.getValue());
        }
        for (Block plug : bulkheadPlugs) {
            state.bulkheadPlugs.add(JobStorage.encodeBlock(plug));
        }
        if (storage != null) {
            for (Block block : storage.chests()) {
                state.chests.add(JobStorage.encodeBlock(block));
            }
            for (long key : storage.occupiedColumns()) {
                state.occupiedColumns.add(JobStorage.encodeColumn(key));
            }
            state.anchor = storage.anchor() == null ? null : JobStorage.encodeBlock(storage.anchor());
            state.lastCubeAnchor = storage.lastCubeAnchor() == null ? null
                    : JobStorage.encodeBlock(storage.lastCubeAnchor());
            Block[] rowFoot = storage.rowFoot();
            state.rowFoot0 = rowFoot[0] == null ? null : JobStorage.encodeBlock(rowFoot[0]);
            state.rowFoot1 = rowFoot[1] == null ? null : JobStorage.encodeBlock(rowFoot[1]);
            state.cubeUnitIndex = storage.cubeUnitIndex();
            state.rowSign = storage.rowSign();
            state.columnSign = storage.columnSign();
        }
        return state;
    }

    private void tick() {
        if (!npcEntity.isValid()) {
            finish(BuilderNpcService.baseNameOf(npc) + " disappeared mid-job — clearing stopped early.");
            return;
        }

        // Duty cycle: a Helper that hasn't learned its trade yet stands about between
        // actions before getting on with the next one. Only the work phases pause — the
        // visual/upkeep work below still runs, so a hesitating Helper looks like it's
        // thinking rather than frozen.
        if (hesitationTicks > 0) {
            hesitationTicks--;
        } else {
            switch (phase) {
                case SEEKING -> seekNextBlock();
                case WALKING -> walkToPendingBlock();
                case DIGGING -> digPendingBlock();
                case HAULING -> haulToChest();
                case BULKHEAD -> tickBulkhead();
            }
        }

        // A long walk across a big site otherwise has zero feedback between one dig and
        // the next — this gives a distant or just-idle-looking Helper a visible sign
        // it's actually en route, not stuck.
        if ((phase == Phase.WALKING || phase == Phase.HAULING) && outlineTicks % WALK_CUE_PERIOD == 0) {
            world.spawnParticle(Particle.CLOUD, npcEntity.getLocation().add(0, 0.1, 0),
                    2, 0.15, 0.05, 0.15, 0.01);
        }

        // Glow is the constant base and lingers, so it only needs redrawing about
        // once a second; the tint runs faster so the pulse reads as a smooth fade.
        if (outlineTicks % GLOW_TICK_PERIOD == 0) {
            outline.drawGlowNearby();
        }
        if (outlineTicks % TINT_TICK_PERIOD == 0) {
            outline.drawTintNearby(RegionOutline.WORKING_TINT, RegionOutline.tintSize(outlineTicks));
        }
        // Same cadence as the glow redraw — chunk tickets don't need to track the NPC's
        // exact position every tick, just often enough that it never outruns them.
        if (outlineTicks % GLOW_TICK_PERIOD == 0) {
            refreshChunkTickets();
        }
        if (outlineTicks % MOB_ALERT_CHECK_PERIOD == 0) {
            checkForHostileMobs();
        }
        announceMilestones();
        outlineTicks++;
        updateLabel();
    }

    /** Fires the 50%/85% progress pings exactly once each, whenever that point is crossed. */
    private void announceMilestones() {
        int percent = percentComplete();
        if (!announced50 && percent >= 50) {
            announced50 = true;
            messagePlayer(Component.text(
                    BuilderNpcService.baseNameOf(npc) + ": Making good progress — about halfway through the "
                            + target.label() + " now.",
                    NamedTextColor.YELLOW));
        }
        if (!announced85 && percent >= 85) {
            announced85 = true;
            messagePlayer(Component.text(
                    BuilderNpcService.baseNameOf(npc) + ": Nearly there — just a bit more " + target.label() + " to go.",
                    NamedTextColor.YELLOW));
        }
    }

    /**
     * Warns about any hostile mob that's wandered close while the Helper is working — it's
     * a builder, not a fighter, so it won't defend itself. Each mob only triggers this once;
     * re-alerting every scan while it lingers nearby would just be noise.
     */
    private void checkForHostileMobs() {
        for (Entity nearby : npcEntity.getNearbyEntities(MOB_ALERT_RADIUS, MOB_ALERT_RADIUS, MOB_ALERT_RADIUS)) {
            if (nearby instanceof Monster monster && alertedMobs.add(monster.getUniqueId())) {
                messagePlayer(Component.text(
                        BuilderNpcService.baseNameOf(npc) + ": Careful — there's a " + prettyName(monster.getType()) + " nearby!",
                        NamedTextColor.RED));
            }
        }
    }

    /**
     * Infested blocks are indistinguishable from their normal counterpart until broken —
     * vanilla spawns a Silverfish on break, but that's normally triggered by the block's
     * own break handling, which setting the type to air directly bypasses. So this does it
     * by hand, and immediately marks the new Silverfish as already-alerted so the general
     * hostile-mob scan doesn't also fire on it a moment later.
     */
    private void spawnSurpriseSilverfish(Location location) {
        Silverfish silverfish = world.spawn(location.add(0.5, 0.5, 0.5), Silverfish.class);
        alertedMobs.add(silverfish.getUniqueId());
        messagePlayer(Component.text(
                BuilderNpcService.baseNameOf(npc) + ": Whoa — that wasn't just stone! Silverfish!", NamedTextColor.RED));
    }

    private static String prettyName(EntityType type) {
        String name = type.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    /**
     * How many cells make up one dig band — {@link #MAX_TOLERATED_FALL} full Y-layers.
     * {@link #blockAt(long)} walks the region top layer first, so a contiguous run of
     * this many layers' worth of cells (starting anywhere aligned to a band boundary) is
     * exactly one band, top to bottom.
     */
    private long cellsPerBand() {
        return cellsPerLayer() * MAX_TOLERATED_FALL;
    }

    private long cellsPerLayer() {
        return (long) spanX * spanZ;
    }

    /** Start (inclusive) of whichever band {@code processedCells} currently sits in — the whole region once {@link #reconciling}. */
    private long currentBandStart() {
        if (reconciling) {
            return 0;
        }
        long cellsPerBand = cellsPerBand();
        return (processedCells / cellsPerBand) * cellsPerBand;
    }

    /**
     * End (exclusive) of whichever band {@code processedCells} currently sits in — the
     * last band may be shorter, and it's the whole region once {@link #reconciling}.
     */
    private long currentBandEnd() {
        if (reconciling) {
            return totalCells;
        }
        return Math.min(totalCells, currentBandStart() + cellsPerBand());
    }

    /** Scans forward for the next clearable block and starts walking to it. */
    private void seekNextBlock() {
        long bandStart = currentBandStart();
        long bandEnd = currentBandEnd();
        long scanLimit = Math.min(processedCells + MAX_CELLS_SCANNED_PER_TICK, bandEnd);
        while (processedCells < scanLimit) {
            Block candidate = blockAt(processedCells);
            processedCells++;
            if (isClearable(candidate)) {
                // Rust's site-refusal: a Helper won't enter the exact block it died in
                // until an owner clears the site or 24h passes. Reuses the same skip
                // mechanism as the error-rate miss below — the multi-pass sweep re-checks
                // it every pass, so it clears itself the moment the refusal lifts instead
                // of needing separate re-queue logic.
                if (levelService.isDeathSiteBlocked(npc, candidate.getLocation())) {
                    skippedThisPass++;
                    continue;
                }
                // Error rate: a green Helper misses one now and then. It's counted as a
                // skip, which the existing multi-pass sweep already re-visits — so the
                // job still finishes clean, the mistake just costs time. Exactly what a
                // future "works the face clean without doubling back" verb removes.
                if (ThreadLocalRandom.current().nextDouble()
                        < HelperTempo.errorRateFor(levelService.levelOf(npc))) {
                    fumbled++;
                    skippedThisPass++;
                    continue;
                }
                pendingBlock = candidate;
                pendingBlockIndex = processedCells - 1;
                walkTicks = 0;
                noProgressTicks = 0;
                closestApproachSquared = Double.MAX_VALUE;
                usedStraightLine = false;
                usedScaffold = false;
                npc.getNavigator().setTarget(candidate.getLocation().add(0.5, 1.0, 0.5));
                phase = Phase.WALKING;
                return;
            }
        }

        if (processedCells < bandEnd) {
            return;
        }

        // A block skipped this pass (unreachable, or buried under one that was) can
        // become clearable once its neighbours go, so sweep this BAND again until a pass
        // both clears nothing and skips nothing. Counting skips matters: a pass that only
        // skipped would otherwise end the band before the unlimited-reach sweep. Never
        // rescans a lower band — the whole point of banding is that a band's floor stays
        // solid ground until the band itself is fully exhausted (see MAX_TOLERATED_FALL).
        if ((clearedThisPass > 0 || skippedThisPass > 0) && passNumber < MAX_PASSES) {
            passNumber++;
            clearedThisPass = 0;
            skippedThisPass = 0;
            processedCells = bandStart;
            return;
        }

        // This band is as done as it'll get. If there's another band below, drop into it
        // — its own floor (the top of the band after that) is still fully solid, so this
        // never exposes the NPC to more than MAX_TOLERATED_FALL of open air beneath it.
        if (bandEnd < totalCells) {
            processedCells = bandEnd;
            passNumber = 1;
            clearedThisPass = 0;
            skippedThisPass = 0;
            return;
        }

        // Every band is done, but a gravity block from outside the dispatched box (or
        // dislodged by digging near its edge) can resettle into a band that already
        // closed out and would otherwise never get revisited — confirmed live
        // 2026-08-19 (painted sand/gravel sitting just above the job's own top edge fell
        // back into an already-swept cell once that layer was cleared). One final pass
        // over the WHOLE region, not just the last band, catches exactly that — reusing
        // the same pass-retry budget above (MAX_PASSES), just scoped to everything
        // instead of one band. Safe even though this briefly drops the per-band fall
        // guarantee back to "the whole region": by now almost everything is already air,
        // so there's little left to dig, and JobFallProtectionListener's blanket
        // fall-immunity (active for the entire job, this phase included) already covers
        // whatever residual risk that reintroduces.
        if (!reconciling) {
            reconciling = true;
            processedCells = 0;
            passNumber = 1;
            clearedThisPass = 0;
            skippedThisPass = 0;
            return;
        }

        // Take the last part-load back before knocking off.
        if (storage != null && carriedTotal > 0) {
            startHauling();
            jobComplete = true;
            return;
        }

        finish(BuilderNpcService.baseNameOf(npc) + ": All done — cleared " + clearedCells + " " + target.label()
                + " block(s)." + storedSuffix());
    }

    /** Waits until the NPC is within reach, or gives up on an unreachable block. */
    private void walkToPendingBlock() {
        if (!isClearable(pendingBlock)) {
            abandonPendingBlock();
            return;
        }

        Location blockCenter = pendingBlock.getLocation().add(0.5, 0.5, 0.5);
        double distanceSquared = npcEntity.getLocation().distanceSquared(blockCenter);
        if (distanceSquared <= REACH_DISTANCE * REACH_DISTANCE) {
            npc.getNavigator().cancelNavigation();
            beginDigging();
            return;
        }

        walkTicks++;

        // Judge by progress, not elapsed time: a long walk across a big site is fine
        // as long as the NPC is still closing the distance.
        if (distanceSquared < closestApproachSquared - PROGRESS_EPSILON) {
            closestApproachSquared = distanceSquared;
            noProgressTicks = 0;
        } else {
            noProgressTicks++;
        }

        // The pathfinder gave up (or never found a route). Real A* checks footing at
        // every step against Citizens' own BlockExaminer chain — but the straight-line
        // fallback below does NOT (confirmed 2026-08-19 by decompiling Citizens'
        // StraightLineNavigationStrategy: pure aim-and-walk, zero ledge/pit awareness).
        // So it's only ever worth the risk as a short last-stretch nudge — already
        // within MAX_WORK_DISTANCE, same "close enough to just work it" threshold used
        // below — never as a stand-in for a real route across unknown or irregular
        // terrain. That's exactly what sent a Helper repeatedly off the edge of a
        // player-built stairway next to an open pit: A* kept giving up within this
        // 1-second grace window, and blind straight-line walking from far away kept
        // aiming it straight through the gap. Skipping instead when too far away loses
        // nothing — the multi-pass sweep, or the pass-3 force-dig fallback right below,
        // still gets this block eventually, without ever walking blind.
        if (walkTicks > PATH_GRACE_TICKS && !npc.getNavigator().isNavigating()) {
            if (!usedStraightLine && distanceSquared <= MAX_WORK_DISTANCE * MAX_WORK_DISTANCE) {
                usedStraightLine = true;
                walkTicks = 0;
                noProgressTicks = 0;
                closestApproachSquared = Double.MAX_VALUE;
                npc.getNavigator().setStraightLineTarget(
                        pendingBlock.getLocation().add(0.5, 1.0, 0.5));
                return;
            }
            // Last resort before giving up on this block entirely: if the reason it
            // can't be reached is mostly a VERTICAL gap, stop trusting Citizens'
            // pathfinding for that gap and just build a way up instead (see
            // attemptScaffoldUp — Kyle's own idea, 2026-08-19, after two rounds of
            // hardening the stuck-response still couldn't fully tame this terrain).
            if (!usedScaffold && attemptScaffoldUp(pendingBlock)) {
                usedScaffold = true;
                walkTicks = 0;
                noProgressTicks = 0;
                closestApproachSquared = Double.MAX_VALUE;
                npc.getNavigator().setTarget(pendingBlock.getLocation().add(0.5, 1.0, 0.5));
                return;
            }
            if (ghostDigIfEarned(distanceSquared)) {
                return;
            }
            skippedNoPath++;
            skippedThisPass++;
            logger.info("[groundworker] " + BuilderNpcService.baseNameOf(npc) + ": gave up reaching "
                    + pendingBlock.getX() + "," + pendingBlock.getY() + "," + pendingBlock.getZ()
                    + " — no path found" + (usedStraightLine ? " (straight-line also failed)" : " (too far for a blind nudge)"));
            abandonPendingBlock();
            return;
        }

        if (noProgressTicks > NO_PROGRESS_TICKS || walkTicks > WALK_TIMEOUT_TICKS) {
            // It stopped making headway — usually a pit it dug or terrain in the way.
            // Already close enough to just reach out and work it? Do that immediately,
            // same "GhostDig" fast path as always. Otherwise try building a way up
            // before resorting to GhostDig's other half — see ghostDigIfEarned and
            // GHOST_DIG_MIN_ATTEMPTS for why this is no longer gated on the shared
            // band-wide pass count.
            if (ghostDigIfEarned(distanceSquared)) {
                return;
            }
            if (!usedScaffold && attemptScaffoldUp(pendingBlock)) {
                usedScaffold = true;
                walkTicks = 0;
                noProgressTicks = 0;
                closestApproachSquared = Double.MAX_VALUE;
                npc.getNavigator().setTarget(pendingBlock.getLocation().add(0.5, 1.0, 0.5));
                return;
            }
            skippedTimeout++;
            skippedThisPass++;
            logger.info("[groundworker] " + BuilderNpcService.baseNameOf(npc) + ": gave up reaching "
                    + pendingBlock.getX() + "," + pendingBlock.getY() + "," + pendingBlock.getZ()
                    + " — timed out, still " + String.format("%.1f", Math.sqrt(distanceSquared)) + " blocks away");
            abandonPendingBlock();
        }
    }

    /**
     * Builds a temporary scaffolding column straight up from wherever the Helper is
     * currently standing, when a block it's trying to reach sits notably above it —
     * Kyle's own idea, 2026-08-19, after live-confirming that no amount of hardening
     * Citizens' stuck-recovery (the straight-line-fallback distance gate, then
     * {@link SafeStuckAction} replacing the teleport-into-air behavior) fully tamed a
     * narrow player-built stairway next to an old dig pit. Rather than keep chasing
     * pathfinding edge cases, this sidesteps the problem: stop trying to find a safe
     * route and just build one. Only fills cells that are actually empty — solid ground
     * already in the column is left alone, so this can never overwrite un-dug target
     * material or grief anything outside what's genuinely missing. Free, conjured
     * placement, same convention as {@link #beginBulkhead}'s cobblestone plugs and
     * sponges — no {@link JobStorage} withdrawal, since it's temporary and self-clearing.
     * Cleared automatically after {@link #SCAFFOLD_LINGER_TICKS} (Kyle's own follow-up
     * call — it was staying up for the whole job, he wanted it gone like a sponge/plug
     * instead), with {@link #scaffoldBlocks}/{@link #clearScaffoldBlocks} as the job-end
     * safety net in case the job itself ends first.
     *
     * @return true if a column was built and the walk is worth retrying; false if this
     *         candidate isn't actually a vertical-gap case (the normal give-up path
     *         should proceed as before).
     */
    private boolean attemptScaffoldUp(Block target) {
        int npcX = npcEntity.getLocation().getBlockX();
        int npcZ = npcEntity.getLocation().getBlockZ();
        int startY = npcEntity.getLocation().getBlockY();
        int targetY = target.getY();
        if (targetY <= startY) {
            return false;
        }
        List<Block> placed = new ArrayList<>();
        for (int y = startY; y <= targetY; y++) {
            Block cell = world.getBlockAt(npcX, y, npcZ);
            if (!cell.getType().isSolid()) {
                cell.setType(Material.SCAFFOLDING);
                placed.add(cell);
            }
        }
        scaffoldBlocks.addAll(placed);
        npc.teleport(new Location(world, npcX + 0.5, targetY + 1, npcZ + 0.5), PlayerTeleportEvent.TeleportCause.PLUGIN);
        world.playSound(new Location(world, npcX, targetY, npcZ), Sound.BLOCK_SCAFFOLDING_PLACE, 1.0f, 1.0f);
        logger.info("[groundworker] " + BuilderNpcService.baseNameOf(npc) + ": scaffolded up to "
                + npcX + "," + (targetY + 1) + "," + npcZ + " to reach a stuck block.");
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (Block scaffold : placed) {
                if (scaffold.getType() == Material.SCAFFOLDING) {
                    scaffold.setType(Material.AIR);
                }
            }
            scaffoldBlocks.removeAll(placed);
        }, SCAFFOLD_LINGER_TICKS);
        return true;
    }

    /** Clears any scaffolding this job placed back to air — the job-end safety net for whatever a linger timer hasn't caught yet. */
    private void clearScaffoldBlocks() {
        for (Block scaffold : scaffoldBlocks) {
            scaffold.setType(Material.AIR);
        }
        scaffoldBlocks.clear();
    }

    /**
     * "GhostDig" — see {@link #GHOST_DIG_MIN_ATTEMPTS}'s own comment for the corrected,
     * genuinely-per-candidate version of the original spec. Called once walking has
     * already failed on {@link #pendingBlock} this attempt; digs it from wherever the
     * Helper stands if it's earned that (close enough, or failed enough times), clearing
     * its entry from {@link #stuckAttempts} either way it's no longer needed.
     */
    private boolean ghostDigIfEarned(double distanceSquared) {
        boolean earned = distanceSquared <= MAX_WORK_DISTANCE * MAX_WORK_DISTANCE
                || stuckAttempts.merge(pendingBlockIndex, 1, Integer::sum) >= GHOST_DIG_MIN_ATTEMPTS;
        if (earned) {
            npc.getNavigator().cancelNavigation();
            beginDigging();
            stuckAttempts.remove(pendingBlockIndex);
        }
        return earned;
    }

    /**
     * Starts digging pendingBlock. Also where the equipped tool gets checked against
     * what this specific block actually needs (see {@link BlockTool}) and swapped if it
     * changed — once per block, not per tick, and only when the needed tool is actually
     * different from what's already held, so a same-material run never re-equips.
     */
    private void beginDigging() {
        Material neededTool = BlockTool.bestToolFor(pendingBlock.getType());
        if (neededTool != currentTool) {
            currentTool = neededTool;
            equipTool(npcEntity, currentTool, BuilderNpcService.baseNameOf(npc), TaskType.fromTool(currentTool).toolNoun());
        }
        digTicks = 0;
        phase = Phase.DIGGING;
    }

    /**
     * How long this Helper takes on the block in front of it. The base comes from
     * {@link VanillaTiming} — tool and block only, no level — and {@link HelperTempo}
     * applies the level curve on top. See {@code HelperTempo}'s dig-speed note: that
     * multiplier is a deliberate, documented departure from the framework.
     */
    private int digTicksForPendingBlock() {
        int vanillaTicks = VanillaTiming.durationFor(
                TaskType.fromTool(currentTool), new ItemStack(currentTool), pendingBlock.getBlockData());
        return HelperTempo.digTicksFor(levelService.levelOf(npc), vanillaTicks);
    }

    /** Plays the dig animation for a beat, then actually breaks the block. */
    private void digPendingBlock() {
        if (!isClearable(pendingBlock)) {
            abandonPendingBlock();
            return;
        }

        faceBlock(pendingBlock);
        if (digTicks % 4 == 0) {
            swingArm();
            world.spawnParticle(Particle.BLOCK, pendingBlock.getLocation().add(0.5, 0.5, 0.5),
                    8, 0.25, 0.25, 0.25, pendingBlock.getBlockData());
        }
        digTicks++;

        if (digTicks < digTicksForPendingBlock()) {
            return;
        }

        world.playSound(pendingBlock.getLocation(),
                pendingBlock.getBlockData().getSoundGroup().getBreakSound(), 1.0f, 1.0f);
        world.spawnParticle(Particle.BLOCK, pendingBlock.getLocation().add(0.5, 0.5, 0.5),
                16, 0.3, 0.3, 0.3, pendingBlock.getBlockData());

        boolean wasInfested = Target.isInfested(pendingBlock.getType());
        collectDrops(pendingBlock);
        pendingBlock.setType(Material.AIR);
        if (wasInfested) {
            spawnSurpriseSilverfish(pendingBlock.getLocation());
        }
        clearedCells++;
        clearedThisPass++;
        awardGroundworkerProgress(creditUnitsFor(pendingBlock));

        if (canBreachLava()) {
            List<Block> waterBreach = detectWaterBreach(pendingBlock);
            List<Block> lavaBreach = detectLavaBreach(pendingBlock);
            if (!waterBreach.isEmpty() || !lavaBreach.isEmpty()) {
                beginBulkhead(waterBreach, lavaBreach);
                return;
            }
        }

        abandonPendingBlock();
        // Duty cycle: pause before getting on with the next one. Shrinks with level.
        hesitationTicks = HelperTempo.hesitationTicksFor(levelService.levelOf(npc));

        if (storage != null && carriedTotal >= CARRY_CAPACITY) {
            startHauling();
        }
    }

    /**
     * True for a Groundworker past the "Bulkhead" milestone (level 6) — gates both the
     * post-dig fluid-breach reaction below AND the {@link #isNearLava} pre-dig veto in
     * {@link #isClearable}, since a lava-adjacent block is vetoed before ever reaching a
     * dig otherwise, which would make the lava half of this feature permanently
     * unreachable. Every other Helper's behavior (any other spec, any Groundworker below
     * level 6) is byte-for-byte unchanged.
     */
    private boolean canBreachLava() {
        return levelService.levelOf(npc) >= 6 && levelService.specializationOf(npc) == Specialization.GROUNDWORKER;
    }

    /**
     * A small, deliberately SPREAD-OUT set of anchor points across the connected WATER
     * body touching the just-dug block (source or flowing — sponges absorb both,
     * confirmed against the real NMS {@code SpongeBlock} bytecode 2026-08-17: its own
     * breadth-first absorption checks {@code FluidTags.WATER} broadly, not source-only),
     * one sponge to be placed at each. Explores outward (up to
     * {@link #BULKHEAD_WATER_EXPLORE_CELLS} cells scanned — cheap, just reading block
     * types, no placement yet) but only KEEPS a candidate as an anchor if it's at least
     * {@link #BULKHEAD_SPONGE_SPACING_BLOCKS} away from every anchor already picked, so
     * each sponge's own engine-owned absorption (confirmed: up to 64 blocks, 6 blocks out
     * from where it's placed) reaches genuinely new water instead of mostly re-covering
     * the same ~64 blocks its neighbor already would have. Originally just checked the
     * dug block's own 6 faces — all touching each other, so even multiple sponges placed
     * there overlapped almost completely instead of covering more ground. Confirmed live
     * 2026-08-17 ("two isn't enough" against a real ocean) that clustered placement was
     * the actual problem, not merely too few sponges.
     *
     * <p>2026-08-18: candidates are now split into two pools — a FLAT RING (same rough
     * level as the breach) and a small VERTICAL ACCENT pool (noticeably above or below,
     * see {@link #BULKHEAD_VERTICAL_DY_THRESHOLD}), capped separately at
     * {@link #BULKHEAD_MAX_VERTICAL_SPONGES} out of the shared
     * {@link #BULKHEAD_MAX_SPONGES} total — so the dome shape only ever uses REAL water
     * found above/below, never forced, and never at the flat ring's expense. The
     * returned order (flat ring furthest-from-breach first, vertical accents last) is
     * also the wave's placement order — see {@link #beginBulkhead}.
     */
    private List<Block> detectWaterBreach(Block diggedBlock) {
        List<Block> flatRing = new ArrayList<>();
        List<Block> verticalAccents = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Deque<Block> frontier = new ArrayDeque<>();
        for (BlockFace face : ADJACENT_FACES) {
            Block neighbor = diggedBlock.getRelative(face);
            if (neighbor.getType() == Material.WATER) {
                frontier.add(neighbor);
            }
        }
        int explored = 0;
        while (!frontier.isEmpty() && explored < BULKHEAD_WATER_EXPLORE_CELLS
                && flatRing.size() + verticalAccents.size() < BULKHEAD_MAX_SPONGES) {
            Block current = frontier.poll();
            if (!visited.add(JobStorage.encodeBlock(current))) {
                continue;
            }
            explored++;
            if (isFarEnoughFromAnchors(current, flatRing) && isFarEnoughFromAnchors(current, verticalAccents)) {
                int dy = Math.abs(current.getY() - diggedBlock.getY());
                if (dy >= BULKHEAD_VERTICAL_DY_THRESHOLD) {
                    if (verticalAccents.size() < BULKHEAD_MAX_VERTICAL_SPONGES) {
                        verticalAccents.add(current);
                    }
                    // Vertical quota already full — not flat either, so skip rather than
                    // demote it into the flat ring; keep exploring past it.
                } else {
                    flatRing.add(current);
                }
            }
            for (BlockFace face : ADJACENT_FACES) {
                Block neighbor = current.getRelative(face);
                if (neighbor.getType() == Material.WATER && !visited.contains(JobStorage.encodeBlock(neighbor))) {
                    frontier.add(neighbor);
                }
            }
        }
        Comparator<Block> byDistanceFromBreachDescending =
                Comparator.<Block>comparingInt(anchor -> squaredDistance(anchor, diggedBlock)).reversed();
        flatRing.sort(byDistanceFromBreachDescending);
        verticalAccents.sort(byDistanceFromBreachDescending);
        List<Block> ordered = new ArrayList<>(flatRing);
        ordered.addAll(verticalAccents);
        return ordered;
    }

    private static boolean isFarEnoughFromAnchors(Block candidate, List<Block> anchors) {
        int minDistanceSquared = BULKHEAD_SPONGE_SPACING_BLOCKS * BULKHEAD_SPONGE_SPACING_BLOCKS;
        for (Block anchor : anchors) {
            if (squaredDistance(candidate, anchor) < minDistanceSquared) {
                return false;
            }
        }
        return true;
    }

    private static int squaredDistance(Block a, Block b) {
        int dx = a.getX() - b.getX();
        int dy = a.getY() - b.getY();
        int dz = a.getZ() - b.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * Every LAVA source (not a partial flow) connected to the just-dug block, found by a
     * breadth-first flood-fill capped at {@link #BULKHEAD_MAX_PLUGS}. Sponges don't
     * absorb lava at all (confirmed: the real {@code SpongeBlock} BFS acceptor checks
     * {@code FluidTags.WATER} exclusively, no lava branch exists) — lava keeps this
     * codebase's own manual flood-fill-and-cobblestone-plug approach, unchanged from
     * before sponges were introduced for water. Plugging only the one block touching the
     * dig left the rest of a real pond's sources free to keep feeding the flood right back
     * in, and re-triggered a whole separate Bulkhead cycle (plug/wait/message/unplug) for
     * every subsequent dig along the shoreline — confirmed live 2026-08-17. Sealing the
     * whole connected body in one pass fixes that: the settle window is long enough for
     * the entire pool to actually drain, and a job only triggers one Bulkhead cycle per
     * genuinely separate body of lava, not one per exposed block. Capped rather than
     * unbounded so an ocean-of-lava-scale connection can't turn one dig into a
     * server-lag-inducing fill.
     */
    private List<Block> detectLavaBreach(Block diggedBlock) {
        // Visited set is keyed by encoded coordinate string, not the Block object itself
        // — same reasoning as JobStorage's own encodeBlock/decodeBlock convention: Block's
        // equals/hashCode isn't part of the documented Bukkit API contract, so don't lean
        // on it for correctness (a broken visited-check here would either loop or
        // re-plug the same source repeatedly).
        List<Block> found = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Deque<Block> frontier = new ArrayDeque<>();
        for (BlockFace face : ADJACENT_FACES) {
            Block neighbor = diggedBlock.getRelative(face);
            if (isLavaSource(neighbor)) {
                frontier.add(neighbor);
            }
        }
        while (!frontier.isEmpty() && found.size() < BULKHEAD_MAX_PLUGS) {
            Block current = frontier.poll();
            if (!visited.add(JobStorage.encodeBlock(current))) {
                continue;
            }
            found.add(current);
            for (BlockFace face : ADJACENT_FACES) {
                Block neighbor = current.getRelative(face);
                if (isLavaSource(neighbor) && !visited.contains(JobStorage.encodeBlock(neighbor))) {
                    frontier.add(neighbor);
                }
            }
        }
        return found;
    }

    private static boolean isLavaSource(Block block) {
        return block.getType() == Material.LAVA
                && block.getBlockData() instanceof Levelled levelled && levelled.getLevel() == 0;
    }

    /**
     * Lava gets plugged instantly (cobblestone, same convention as {@link JobStorage}'s
     * free chest placement) — it's an urgent stop-the-bleeding mechanic, not a decorative
     * one, so no reason to delay it. Water anchors are queued for
     * {@link #tickBulkhead()}'s wave rollout instead of placed here directly (2026-08-18,
     * "back to front wave kind animation" — Kyle's own framing): each still becomes a
     * real {@link Material#SPONGE} via the same plain {@code setType(Material)} call as
     * before once its turn comes, vanilla's own engine still does the actual absorbing,
     * unchanged. Both plug types get cleared back to air identically later — the cleanup
     * doesn't care which material is sitting there.
     */
    private void beginBulkhead(List<Block> waterBreach, List<Block> lavaBreach) {
        npc.getNavigator().cancelNavigation();
        pendingBlock = null;
        for (Block source : lavaBreach) {
            source.setType(BULKHEAD_PLUG_MATERIAL);
            bulkheadPlugs.add(source);
        }
        bulkheadWaveAnchors.clear();
        bulkheadWaveAnchors.addAll(waterBreach);
        bulkheadWaveIndex = 0;
        bulkheadWaveStepTicks = BULKHEAD_WAVE_TICKS_PER_STEP;
        // A don't-care placeholder when there's a wave to run — tickBulkhead() seeds the
        // real settle countdown itself once the last sponge actually lands, so the settle
        // window doesn't burn away while the wave is still rolling out. For a lava-only
        // breach (no water queued), tickBulkhead()'s wave branch never runs, so this is
        // the only place that seeds it — matches the old always-instant behavior exactly.
        bulkheadTicks = bulkheadWaveAnchors.isEmpty() ? BULKHEAD_SETTLE_TICKS : 0;
        phase = Phase.BULKHEAD;
        logger.info("[groundworker] Bulkhead: breach detected, " + waterBreach.size() + " sponge(s) queued and "
                + lavaBreach.size() + " lava source(s) plugged for " + BuilderNpcService.baseNameOf(npc)
                + (lavaBreach.size() >= BULKHEAD_MAX_PLUGS ? " (lava fill hit the cap — partial seal of a larger body)" : ""));
        // No chat line here on purpose (2026-08-18) — a shoreline dig can trigger a breach
        // every few seconds, and the sponge wave itself (particles + sound + the dome
        // shape appearing) already tells the player what's happening without also
        // spamming chat once per cycle.
    }

    /**
     * While a water wave is still queued, places its next anchor every
     * {@link #BULKHEAD_WAVE_TICKS_PER_STEP} ticks — piggybacks on this task's own
     * existing per-tick loop ({@link #tick()}) rather than a separate scheduler, the same
     * way {@link #outlineTicks} already paces itself. Once the wave is exhausted (or
     * there never was one), falls through to counting down the settle timer, then clears
     * every plug and resumes clearing.
     */
    private void tickBulkhead() {
        if (bulkheadWaveIndex < bulkheadWaveAnchors.size()) {
            bulkheadWaveStepTicks--;
            if (bulkheadWaveStepTicks > 0) {
                return;
            }
            Block anchor = bulkheadWaveAnchors.get(bulkheadWaveIndex);
            anchor.setType(Material.SPONGE);
            bulkheadPlugs.add(anchor);
            world.spawnParticle(Particle.SPLASH, anchor.getLocation().add(0.5, 0.5, 0.5),
                    12, 0.3, 0.3, 0.3, 0.05);
            world.playSound(anchor.getLocation(), Sound.BLOCK_SPONGE_PLACE, 1.0f, 1.0f);
            bulkheadWaveIndex++;
            // One line per placement (not a single batch summary) — logged in placement
            // order, immediately adjacent to that sponge's own SpongeAbsorbEvent line in
            // SpongeAbsorptionDiagnosticListener, so the still-open weaker-Helper-sponge
            // investigation can still correlate each sponge to its actual absorbed count
            // even though placement is now spread over ~2 seconds instead of one tick.
            logger.info("[groundworker] Bulkhead: sponge " + bulkheadWaveIndex + "/" + bulkheadWaveAnchors.size()
                    + " placed at " + anchor.getX() + "," + anchor.getY() + "," + anchor.getZ()
                    + " for " + BuilderNpcService.baseNameOf(npc));
            bulkheadWaveStepTicks = BULKHEAD_WAVE_TICKS_PER_STEP;
            if (bulkheadWaveIndex == bulkheadWaveAnchors.size()) {
                bulkheadTicks = BULKHEAD_SETTLE_TICKS;
            }
            return;
        }
        bulkheadTicks--;
        if (bulkheadTicks > 0) {
            return;
        }
        clearBulkheadPlugs();
        logger.info("[groundworker] Bulkhead: unplugged, resuming for " + BuilderNpcService.baseNameOf(npc));
        bulkheadWaveAnchors.clear();
        bulkheadWaveIndex = 0;
        // No chat line here either, for the same reason as beginBulkhead() — the sponges
        // visibly vanishing and digging resuming already says "back to work" on its own.
        phase = Phase.SEEKING;
        hesitationTicks = HelperTempo.hesitationTicksFor(levelService.levelOf(npc));
        if (storage != null && carriedTotal >= CARRY_CAPACITY) {
            startHauling();
        }
    }

    /** Picks up what the block would really drop for this tool, so grass yields dirt. */
    private void collectDrops(Block block) {
        if (storage == null) {
            return;
        }
        for (ItemStack drop : block.getDrops(new ItemStack(currentTool))) {
            int amount = drop.getAmount() * TEST_DROP_MULTIPLIER;
            // Specialization bonus-drop skill: a matching-specialty Helper occasionally
            // pulls one extra unit. Rolled on the plain drop, same material either way —
            // this is a bonus quantity, not a different item.
            if (levelService.rollBonusDrop(npc, target)) {
                amount += 1;
            }
            carried.merge(drop.getType(), amount, Integer::sum);
            carriedTotal += amount;
        }
    }

    private void startHauling() {
        depositPoint = storage.depositPoint();
        if (depositPoint == null) {
            // Nowhere to build a chest — carry on working rather than stalling the job.
            messagePlayer(Component.text(
                    "No room to place a storage chest nearby — " + BuilderNpcService.baseNameOf(npc)
                            + " is working without one.", NamedTextColor.RED));
            carried.clear();
            carriedTotal = 0;
            return;
        }
        walkTicks = 0;
        noProgressTicks = 0;
        closestApproachSquared = Double.MAX_VALUE;
        npc.getNavigator().setTarget(depositPoint);
        phase = Phase.HAULING;
    }

    /** Walks a full load back to the chest and unloads it. */
    private void haulToChest() {
        double distanceSquared = npcEntity.getLocation().distanceSquared(depositPoint);

        if (distanceSquared <= REACH_DISTANCE * REACH_DISTANCE) {
            unload();
            return;
        }

        walkTicks++;
        if (distanceSquared < closestApproachSquared - PROGRESS_EPSILON) {
            closestApproachSquared = distanceSquared;
            noProgressTicks = 0;
        } else {
            noProgressTicks++;
        }

        if (walkTicks > PATH_GRACE_TICKS && !npc.getNavigator().isNavigating() && !usedStraightLine) {
            usedStraightLine = true;
            walkTicks = 0;
            noProgressTicks = 0;
            closestApproachSquared = Double.MAX_VALUE;
            npc.getNavigator().setStraightLineTarget(depositPoint);
            return;
        }

        // Same escalation as digging: if it genuinely can't get to the chest, unload
        // from here rather than wedging the job forever.
        if (noProgressTicks > NO_PROGRESS_TICKS || walkTicks > WALK_TIMEOUT_TICKS) {
            unload();
        }
    }

    private void unload() {
        npc.getNavigator().cancelNavigation();
        npc.faceLocation(depositPoint);
        world.playSound(depositPoint, Sound.BLOCK_BARREL_OPEN, 0.7f, 1.0f);

        Map<Material, Integer> leftover = storage.deposit(carried);
        int stored = carriedTotal - leftover.values().stream().mapToInt(Integer::intValue).sum();
        deposited += stored;

        carried.clear();
        carried.putAll(leftover);
        carriedTotal = leftover.values().stream().mapToInt(Integer::intValue).sum();

        if (!leftover.isEmpty()) {
            messagePlayer(Component.text(
                    "Storage is full and there's no room to expand — " + BuilderNpcService.baseNameOf(npc)
                            + " is dropping the rest.", NamedTextColor.RED));
            carried.clear();
            carriedTotal = 0;
        }

        usedStraightLine = false;
        usedScaffold = false;

        if (jobComplete) {
            finish(BuilderNpcService.baseNameOf(npc) + " finished clearing — " + clearedCells + " " + target.label()
                    + " block(s) cleared." + storedSuffix());
            return;
        }
        phase = Phase.SEEKING;
    }

    private String storedSuffix() {
        return storage == null ? "" : " " + deposited + " item(s) stored.";
    }

    /**
     * The anti-grind chassis's per-block pre-filter, checked before a block is allowed to
     * contribute toward ticket progress at all — Groundworker batches 512 blocks into one
     * ticket, so by the time the Toil pipeline runs, individual block identity is gone and
     * a single multiplier can't fairly represent a batch mixing legitimate and cheaty
     * blocks. Both trackers are always consulted regardless of which one ends up gating,
     * so each keeps accurate history. Returns quarter-units: {@link #CREDIT_UNITS_PER_BLOCK}
     * (full), 1 (Redundancy's 20-minute reduced tier), or 0 (the Fresh rule, or
     * Redundancy's 5-minute zero tier).
     */
    private int creditUnitsFor(Block block) {
        long now = System.currentTimeMillis();
        TaskFingerprint fingerprint = new TaskFingerprint(TaskType.CLEAR, block.getWorld().getName(),
                block.getX(), block.getY(), block.getZ(), canonicalMaterialClass(block));
        RedundancyTracker.CreditTier tier = redundancyTracker.check(npc.getUniqueId(), fingerprint, now);
        if (freshLedger.isFresh(block, now)) {
            logger.info("[antigrind] Fresh block skipped for " + BuilderNpcService.baseNameOf(npc)
                    + " at " + block.getX() + "," + block.getY() + "," + block.getZ());
            return 0;
        }
        if (tier != RedundancyTracker.CreditTier.FULL) {
            logger.info("[antigrind] Redundancy " + tier + " for " + BuilderNpcService.baseNameOf(npc)
                    + " at " + block.getX() + "," + block.getY() + "," + block.getZ());
        }
        return switch (tier) {
            case ZERO -> 0;
            case REDUCED -> 1;
            case FULL -> CREDIT_UNITS_PER_BLOCK;
        };
    }

    /**
     * The fingerprint's material class must be the block's own real family (Stone or
     * Dirt), never the job's dispatched {@link #target} verbatim — {@link Target#ANY_EARTH}
     * matches both, so two touches of the SAME block position under two differently-
     * dispatched jobs (one Stone/Dirt-targeted, one Anything-targeted) would otherwise
     * fingerprint as different materials and silently dodge Redundancy Decay. Still a
     * plain Stone-or-Dirt binary bucket, matching the anti-grind chassis's existing
     * two-family design — but the Stone side now uses the same broad
     * {@link Tag#MINEABLE_PICKAXE} check {@link Target#ANY_EARTH} itself matches with
     * (2026-08-18), not the old plain-Stone-only check, so an ore or a stone variant now
     * correctly buckets as Stone-family instead of silently defaulting into Dirt.
     */
    private Target canonicalMaterialClass(Block block) {
        return Tag.MINEABLE_PICKAXE.isTagged(block.getType()) ? Target.STONE : Target.DIRT;
    }

    /**
     * Groundworker's Toil ticket: one per {@link #GROUNDWORKER_TICKET_BLOCKS} cleared.
     * Called once per block broken, so progress accrues steadily as the Helper works and
     * tickets fire one at a time — rather than the whole job's worth landing in a single
     * lump the moment it reaches a chest, which read as the Helper jumping several levels
     * at once with nothing visible in between.
     *
     * <p>Counting on the BREAK rather than on the deposit is a deliberate divergence from
     * the framework's Groundworker entry ("spoil hauled to a real chest... voided spoil is
     * an unfinished ticket"), made at Kyle's call 2026-08-13 for progression feel. It also
     * closes a real hole in the deposit-gated version: that one only ran inside
     * {@code unload()}, so any job dispatched with "Store in Chest" unchecked earned zero
     * Toil no matter how much it cleared. The framework's "never award per block" rule is
     * still honoured — the ledger is credited per finished TICKET; only the counter that
     * accumulates toward one ticks per block.
     *
     * <p>{@code creditUnits} is in quarter-units, not blocks — see {@link #creditUnitsFor}
     * and {@link #CREDIT_UNITS_PER_BLOCK} for the anti-grind chassis's per-block
     * pre-filter this now runs through before reaching here.
     *
     * <p>Gated on the NPC's ASSIGNED specialization, not just the target block — the same
     * gating {@link HelperLevelService#rollBonusDrop} already uses — so a non-Groundworker
     * Helper sent to clear Stone/Dirt still does the job but earns no Groundworker Toil.
     * Farmer/Lumberjack tickets aren't wired yet (they need tree-shape/replant logic this
     * job engine doesn't have) — those Helpers earn no Toil until a later item wires them.
     */
    private void awardGroundworkerProgress(int creditUnits) {
        if (target != Target.STONE && target != Target.DIRT && target != Target.ANY_EARTH) {
            return;
        }
        if (levelService.specializationOf(npc) != Specialization.GROUNDWORKER) {
            return;
        }
        if (creditUnits <= 0) {
            return;
        }
        List<TicketAwardResult> results = levelService.awardProgress(
                npc, TicketKind.GROUNDWORKER_CLEAR_512, GROUNDWORKER_TICKET_BLOCKS * CREDIT_UNITS_PER_BLOCK, creditUnits);
        for (TicketAwardResult result : results) {
            for (String line : result.announcementLines()) {
                messagePlayer(Component.text(line, NamedTextColor.GREEN));
            }
        }
    }

    private void abandonPendingBlock() {
        npc.getNavigator().cancelNavigation();
        pendingBlock = null;
        phase = Phase.SEEKING;
    }

    private boolean isClearable(Block block) {
        if (block == null || !target.matches(block.getType())) {
            return false;
        }
        // Basic hazard-avoidance pathing (warning-only Nether/End dispatch still lets the
        // job proceed, but the NPC won't walk right up to a lava-adjacent block for it) —
        // except a level-6+ Groundworker, who can handle what digging into it exposes.
        if (isNearLava(block) && !canBreachLava()) {
            return false;
        }
        // Surface mode leaves buried material alone. With it off the job hollows the
        // region out instead — still safe to path, since clearing a full Y layer before
        // descending means the NPC always has open space above the layer it's working.
        return !surfaceOnly
                || world.getBlockAt(block.getX(), block.getY() + 1, block.getZ()).isPassable();
    }

    private static final BlockFace[] ADJACENT_FACES = {
        BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST,
    };

    /** Basic hazard-avoidance: don't walk up to dig a block sitting right next to lava. */
    private boolean isNearLava(Block block) {
        for (BlockFace face : ADJACENT_FACES) {
            if (block.getRelative(face).getType() == Material.LAVA) {
                return true;
            }
        }
        return false;
    }

    /**
     * Walks the region top layer first: every X/Z cell of one Y level is visited
     * before dropping to the level below, so the job reads as deliberate stripping.
     * Within a layer the X direction flips each row (a serpentine/lawnmower sweep),
     * so the NPC never has to run back across the site to start the next row.
     */
    private Block blockAt(long index) {
        long cellsPerLayer = (long) spanX * spanZ;
        int layer = (int) (index / cellsPerLayer);
        long withinLayer = index % cellsPerLayer;

        int row = (int) (withinLayer / spanX);
        int col = (int) (withinLayer % spanX);

        int y = maxY - layer;
        int z = minZ + row;
        int x = (row % 2 == 0) ? minX + col : minX + (spanX - 1 - col);
        return world.getBlockAt(x, y, z);
    }

    private void swingArm() {
        if (npcEntity instanceof LivingEntity livingEntity) {
            livingEntity.swingMainHand();
        }
    }

    private void faceBlock(Block block) {
        npc.faceLocation(block.getLocation().add(0.5, 0.5, 0.5));
    }

    private void updateLabel() {
        label.teleport(npcEntity.getLocation().add(0, LABEL_HEIGHT_OFFSET, 0));
        int percent = (int) (processedCells * 100 / totalCells);
        String text = passNumber == 1 ? percent + "%" : percent + "% (pass " + passNumber + ")";
        label.text(Component.text(text, NamedTextColor.YELLOW));
    }

    private void finish(String message) {
        if (task != null) {
            task.cancel();
        }
        npc.getNavigator().cancelNavigation();
        releaseChunkTickets();
        clearBulkheadPlugs();
        clearScaffoldBlocks();
        label.remove();
        if (equipment != null) {
            equipment.setItemInMainHand(null);
        }
        // A finished job matters even if nobody's around to see it end — queue it for
        // next login rather than letting it vanish the way every other status message
        // here does when the player's offline.
        Player player = Bukkit.getPlayer(playerId);
        if (player != null) {
            player.sendMessage(Component.text(message, NamedTextColor.GREEN));
        } else {
            jobManager.queueOfflineNotification(playerId, message);
        }
        logger.info(message + " [passes=" + passNumber + ", no-path-skips=" + skippedNoPath
                + ", timeout-skips=" + skippedTimeout + ", fumbled=" + fumbled + "]");
        jobManager.onJobEnded(npc.getId());
    }

    /**
     * Resolves the dispatching player fresh every time rather than holding a long-lived
     * reference — a resumed job may have been created with nobody online, and a live one
     * can outlast the player's original session if they reconnect under a new object.
     */
    private void messagePlayer(Component message) {
        Player player = Bukkit.getPlayer(playerId);
        if (player != null) {
            player.sendMessage(message);
        }
    }
}
