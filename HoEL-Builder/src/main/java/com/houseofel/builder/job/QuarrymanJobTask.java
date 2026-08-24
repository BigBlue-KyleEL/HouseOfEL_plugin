package com.houseofel.builder.job;

import com.houseofel.builder.gui.BlockTool;
import com.houseofel.builder.gui.Target;
import com.houseofel.builder.gui.TaskType;
import com.houseofel.builder.npc.BuilderNpcService;
import com.houseofel.builder.npc.HelperLevelService;
import com.houseofel.builder.region.RegionOutline;
import com.houseofel.builder.timing.HelperTempo;
import com.houseofel.builder.timing.VanillaTiming;
import net.citizensnpcs.api.npc.NPC;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Levelled;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
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
import java.util.logging.Logger;

/**
 * Runs one Quarryman job for one Helper — Phase B, the bench-planner walking skeleton.
 * Digs a small, fixed shape (a fixed-size footprint that slides one block deeper per
 * level, along whichever horizontal axis the player's own two marked points call for,
 * see {@link #buildDigOrder} — Kyle's own redesign, 2026-08-20, replacing an earlier
 * two-fixed-bench-plus-ramp shape that turned out both more complex and less correct)
 * rather than a general player-marked volume, so both the dig order and the fact that
 * every reachable cell really is reachable are known up front — none of
 * {@link ClearJobTask}'s general-purpose stuck-recovery machinery (GhostDig,
 * scaffold-climb, the straight-line fallback) is needed here.
 *
 * <p>Registered with {@link JobManager} like {@link ClearJobTask}, via the shared
 * {@link JobTask} interface — real pause/resume, the shared concurrency ceiling, and
 * restart-survival (a job in progress at restart resumes at the top of a fresh seek,
 * same "a stale mid-walk/mid-dig target isn't safe to trust" reasoning
 * {@link ClearJobTask#resume} already documents) all follow for free from that, added
 * 2026-08-21. Before that this was deliberately standalone — no shared ceiling, no
 * pause/resume, abandoned outright on a restart — while {@code JobManager}/
 * {@code JobState} were concretely typed to {@code ClearJobTask} alone; generalizing
 * them behind a {@link JobType} discriminator was explicitly scoped to a later phase,
 * and this is that phase. Cancel-by-chat, pause-by-chat, and resume-by-chat now all
 * route the same way a Clear job's do — through {@code JobManager} — where cancel
 * previously had its own separate path direct to {@link #cancelJob()}.
 *
 * <p>Fall-safety here is structural, not listener-based: every single step
 * {@link #buildDigOrder} produces drops the Helper at most 1 block by
 * construction — a fact that holds regardless of {@link #MAX_TOLERATED_FALL}'s own value,
 * since it's the geometry, not the navigator setting, doing the real work there.
 * {@code MAX_TOLERATED_FALL} matches {@code ClearJobTask}'s own proven value (3), not a
 * tighter one: {@code fallDistance} governs Citizens' A* path search for the WHOLE job,
 * including the ordinary walk from wherever the Helper currently stands to the marked
 * site — confirmed live 2026-08-20 that setting it to 1 made Citizens silently refuse to
 * path across perfectly normal overworld terrain elevation on the way there, reading as
 * "stuck" with nothing actually blocking anything. 3 is still more than enough headroom
 * for the dig geometry's own 1-block steps; it just stops being tighter than the terrain
 * the Helper has to cross to ever reach them. {@link JobFallProtectionListener} now also
 * covers this job as a blanket backup on top of the structural guarantee, once
 * registering with {@code JobManager} made that possible — it wasn't reachable before
 * this phase.
 *
 * <p>That per-step guarantee covers the intended path, not real movement physics or
 * Citizens' own pathing choices. Two distinct failures confirmed live 2026-08-20: walking
 * fast ({@link #WALK_SPEED_MODIFIER}) across several consecutive 1-block steps in one
 * continuous walk can carry the Helper past more than one step's edge before gravity
 * catches up, landing it below grade instead of cleanly stepping down (Kyle: falls while
 * running, then "jumps up and down beside the stairs" instead of climbing back); separately,
 * Citizens can also route itself up and completely out of the pit while trying to reach a
 * cell close by, then never find its own way back down (Kyle: the next block was "right in
 * front of him" but Horace "would go up the quarry, stay up top"). {@link #walkToPendingCell}
 * now detects "not closing the distance to pendingCell" itself, in either direction (same
 * closest-approach/{@code noProgressTicks} idiom {@link ClearJobTask} already uses), and
 * recovers by teleporting into a confirmed-clear spot near the pending cell (see
 * {@link #safeLandingAbove}) — safe because every layer above a queued cell that a
 * shallower layer's own window already reached is already clear by construction, and
 * {@code safeLandingAbove} searches rather than assumes for the columns where that's not
 * true (see {@link #buildDigOrder}'s own doc for why those columns exist). The same
 * closest-approach idiom now also covers a stalled haul trip ({@link #haulToChest}) —
 * unload immediately once genuinely stuck rather than waiting the full walk timeout,
 * since {@link #unload} never actually required standing at the chest, only knowing
 * where it is.
 *
 * <p>One more thing the fixed geometry doesn't cover: a gravity block (sand, gravel)
 * from beside or above the marked footprint sliding into an already-cleared cell after
 * the Helper has already moved on. Flagged by Kyle 2026-08-21 before it had actually
 * happened, not after — {@link #seekNextCell} runs one settle-and-recheck pass over
 * every cell the dig order ever queued once the main pass empties, the same reason
 * {@code ClearJobTask} has its own final whole-region pass, just without that one's
 * banding — Quarryman's shape is small and bounded enough not to need it.
 */
public final class QuarrymanJobTask implements JobTask {

    private static final double REACH_DISTANCE = 5.5;
    private static final float WALK_SPEED_MODIFIER = 1.3f;
    private static final float PATHFINDING_RANGE = 100.0f;
    private static final int MAX_TOLERATED_FALL = 3;
    /**
     * Flat backstop for a walk that never resolves. The fixed geometry means every cell
     * is genuinely reachable by construction, so this should never actually fire — it
     * exists so a genuinely unexpected obstruction (a player-placed block in the way)
     * fails loud and stops the job, rather than hanging forever. Unlike
     * {@link ClearJobTask#walkToPendingBlock}, there's no progress-tracking, straight-line
     * fallback, or scaffold-climb here — none of that machinery earns its keep on a
     * shape this small and this predictable.
     */
    private static final int WALK_TIMEOUT_TICKS = 20 * 30;
    /** Grace period before checking whether Citizens ever actually started navigating — see the one-shot retry in {@link #walkToPendingCell}. */
    private static final int RETRY_GRACE_TICKS = 20;
    /**
     * How long a walk can go without closing the distance to {@code pendingCell} before
     * {@link #walkToPendingCell} treats it as genuinely stuck — Citizens unable to find its
     * own way there, in either direction — rather than an ordinary walk that just takes a
     * while (e.g. resuming digging after a haul trip). Same tuning as {@code ClearJobTask}'s
     * own proven {@code NO_PROGRESS_TICKS}.
     */
    private static final int STUCK_RECOVERY_NO_PROGRESS_TICKS = 20 * 3;
    /** Same tuning as {@code ClearJobTask}'s own proven {@code PROGRESS_EPSILON}. */
    private static final double PROGRESS_EPSILON = 0.05;
    /**
     * Grace period before the post-dig reconciliation scan (see {@link #seekNextCell})
     * actually looks for gravity blocks — a settling window, not a real wait for
     * anything specific: by the time the main dig order empties, most falls have long
     * since landed, but a block dislodged by the very last cell dug could still be mid-air.
     */
    private static final int RECONCILE_SETTLE_TICKS = 20 * 3;
    /**
     * How often {@link #reconcileRecentCells} re-checks already-passed cells for anything
     * resettled — confirmed live 2026-08-21 that the end-of-job-only reconciliation pass
     * (see {@link #seekNextCell}'s own doc) reads as broken to a player watching: a block
     * that falls back in gets walked straight past and left there for the rest of a
     * potentially long dig, only ever cleared once the whole job otherwise finishes. This
     * periodic check closes that gap by re-scanning only the small window of cells passed
     * since the last check (cheap — a handful of blocks, not the whole dig order) rather
     * than waiting for the end. The end-of-job full sweep stays as a final safety net for
     * whatever's passed since the last periodic check.
     */
    private static final int RECONCILE_CHECK_PERIOD_TICKS = 20 * 5;
    private static final int CARRY_CAPACITY = 4 * 64;

    /**
     * Phase D — "it handles fluid... at scale" ([[V1 Perk Ladders]]'s own words for the
     * full Quarryman spec). Reuses {@code ClearJobTask}'s Bulkhead mechanism (Groundworker
     * level 6) essentially verbatim — same tuning constants, same lava-flood-fill/
     * water-sponge-wave split, same settle-then-clear teardown — rather than a redesign:
     * Quarryman is always past level 6 already (it's a level-8 choice), so there's no
     * level gate to port, just the mechanism itself, triggered per dig-cell instead of
     * once per job. See {@code ClearJobTask}'s own doc on each constant for the full
     * reasoning/tuning history; values are copied unchanged, not re-derived, since nothing
     * about Quarryman's shape changes what makes a sponge placement good or bad.
     */
    private static final Material BULKHEAD_PLUG_MATERIAL = Material.COBBLESTONE;
    private static final int BULKHEAD_SETTLE_TICKS = 60;
    private static final int BULKHEAD_MAX_PLUGS = 64;
    private static final int BULKHEAD_MAX_SPONGES = 18;
    private static final int BULKHEAD_MAX_VERTICAL_SPONGES = 6;
    private static final int BULKHEAD_VERTICAL_DY_THRESHOLD = 2;
    private static final int BULKHEAD_SPONGE_SPACING_BLOCKS = 2;
    private static final int BULKHEAD_WATER_EXPLORE_CELLS = 4096;
    private static final int BULKHEAD_WAVE_TICKS_PER_STEP = 4;
    private static final BlockFace[] ADJACENT_FACES = {
        BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST,
    };
    /**
     * Horizontal-only faces, for wall-torch placement — a torch on the CEILING or FLOOR
     * isn't a wall torch (those are the standing/floor variant, handled separately as the
     * fallback below), so {@link #ADJACENT_FACES} itself (which includes UP/DOWN, needed
     * for the fluid flood-fills above) isn't the right set to search here.
     */
    private static final BlockFace[] HORIZONTAL_FACES = {
        BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST,
    };
    /**
     * Phase D — "lights the face as it descends" ([[V1 Perk Ladders]]). Every
     * {@code TORCH_SPACING_CELLS}'th cell dug gets checked for a real mob-spawn-dark spot
     * and lit if so — a period, not "one torch per bench," so a shallow bench with few
     * cells still gets lit at roughly the same real-world density as a deep one, rather
     * than one torch per bench regardless of how long that bench actually is. Live-tuned
     * starting point, not measured — matches roughly one torch every 2-3 real-world
     * seconds of digging at typical dig speed, close to (not identical to) vanilla's own
     * "torch every ~7 blocks" convention automated builds commonly use.
     */
    private static final int TORCH_SPACING_CELLS = 6;
    /**
     * Vanilla's own hostile-mob spawn threshold is block light &lt;= 7 — matching that
     * exactly (not a stricter number) means a torch only ever gets placed where mobs could
     * actually spawn, not "somewhat dim but already safe."
     */
    private static final int TORCH_LIGHT_THRESHOLD = 7;
    /**
     * Rule 11: a permanent placement (unlike a self-clearing Bulkhead plug) needs a real
     * chest-sourced cost, not a free conjure — see [[House of EL — Development Timeline]]'s
     * own risk note flagging this exact question as needing an explicit decision before
     * Phase D. Decided here: real cost, with a graceful skip (not a blocked dig) if the
     * chest is simply out of torches — same "storage optional, dig proceeds regardless"
     * philosophy {@link #storage} already has everywhere else in this class.
     */
    private static final Material TORCH_MATERIAL = Material.TORCH;
    private static final Material WALL_TORCH_MATERIAL = Material.WALL_TORCH;

    private static final double LABEL_HEIGHT_OFFSET = 2.3;
    private static final int GLOW_TICK_PERIOD = 20;
    private static final int TINT_TICK_PERIOD = 5;
    private static final int WALK_CUE_PERIOD = 10;
    private static final double MOB_ALERT_RADIUS = 10.0;
    private static final int MOB_ALERT_CHECK_PERIOD = GLOW_TICK_PERIOD;

    private enum Phase { SEEKING, WALKING, DIGGING, HAULING, BULKHEAD }

    private final Plugin plugin;
    private final Logger logger;
    private final JobManager jobManager;
    private final HelperLevelService levelService;
    private final UUID playerId;
    private final NPC npc;
    private final Entity npcEntity;
    private final EntityEquipment equipment;
    private final TextDisplay label;
    private final World world;
    private Material currentTool;
    private final JobStorage storage;
    private final RegionOutline outline;

    // Dig-order parameters, kept as fields (not just consumed once into a Deque) so
    // toJobState()/resume() can regenerate the identical dig order deterministically —
    // see buildDigOrder's own doc.
    private final int minX;
    private final int maxX;
    private final int minZ;
    private final int maxZ;
    private final int topY;
    private final int requestedDepth;
    private final boolean stepsAlongX;
    private final int stepDirection;

    private final Deque<Block> remainingCells;
    /** Full snapshot of every cell {@link #buildDigOrder} ever queued, kept for the reconciliation pass — see {@link #seekNextCell}. */
    private final List<Block> allCells;
    private final long totalCells;
    private long processedCells;
    private long clearedCells;
    private long deposited;
    private boolean awaitingReconciliation;
    private boolean reconciled;
    /** How far into {@link #allCells} {@link #reconcileRecentCells} has already re-checked. */
    private long reconciliationCursor;
    /**
     * Resettled cells found by {@link #reconcileRecentCells}, drained by {@link #seekNextCell}
     * ahead of {@link #remainingCells} — see that method's own doc for why this stays a
     * separate queue rather than feeding into {@code remainingCells}/{@code processedCells}
     * directly. In-memory only, doesn't survive a pause/resume — see the same doc for why
     * that's an accepted gap, not a correctness bug.
     */
    private final Deque<Block> reconciliationQueue = new ArrayDeque<>();

    private final Map<Material, Integer> carried;
    private int carriedTotal;
    private Location depositPoint;

    private Phase phase = Phase.SEEKING;
    private int hesitationTicks;
    private Block pendingCell;
    private int walkTicks;
    private boolean retriedWalk;
    private int noProgressTicks;
    /** One attempt per walk at the stuck-recovery teleport — see its own use in {@link #walkToPendingCell}. */
    private boolean recoveryAttempted;
    private double closestApproachSquared;
    private int digTicks;
    /**
     * Wherever the Helper was last confirmed standing right after actually finishing a
     * cell — see {@link #digPendingCell}. Guaranteed both safe (stood there without
     * incident) and connected to the dig (it's the dig's own path, not a guess), unlike
     * {@link #safeLandingAbove}'s search, which only confirms clearance, not
     * connectivity. Used as the stuck-recovery fallback in {@link #walkToPendingCell} when
     * that search can't confirm anywhere safe near the target itself — see that method's
     * own doc for why an unconfirmed teleport isn't an option any more. Initialized to the
     * Helper's starting position (fresh dispatch or restart-resume alike) so it's never
     * null.
     */
    private Location lastSafeLocation;

    /** Fluid source blocks currently plugged, awaiting {@link #tickBulkhead}'s settle timer — see {@code ClearJobTask}'s own field. */
    private final List<Block> bulkheadPlugs = new ArrayList<>();
    private int bulkheadTicks;
    /** Water anchors still waiting to land during the wave rollout — see {@code ClearJobTask}'s own field. */
    private final List<Block> bulkheadWaveAnchors = new ArrayList<>();
    private int bulkheadWaveIndex;
    private int bulkheadWaveStepTicks;

    private boolean paused;
    /** Set once at construction (fresh dispatch or restart-resume), never touched by a same-session pause/resume — same reasoning as ClearJobTask's own field. */
    private final long startedAtMillis = System.currentTimeMillis();

    private BukkitTask task;
    private int outlineTicks;
    private final Set<UUID> alertedMobs = new HashSet<>();
    /** Chunks currently held open via a plugin ticket — see {@link #refreshChunkTickets()}. */
    private final Set<Long> ticketedChunks = new HashSet<>();

    /** Fresh job, just dispatched. */
    QuarrymanJobTask(Plugin plugin, JobManager jobManager, HelperLevelService levelService, UUID playerId, NPC npc,
                      Entity npcEntity, EntityEquipment equipment, TextDisplay label, World world,
                      Material initialTool, int minX, int maxX, int minZ, int maxZ, int topY, int requestedDepth,
                      boolean stepsAlongX, int stepDirection, Deque<Block> remainingCells, JobStorage storage,
                      RegionOutline outline) {
        this(plugin, jobManager, levelService, playerId, npc, npcEntity, equipment, label, world, initialTool,
                minX, maxX, minZ, maxZ, topY, requestedDepth, stepsAlongX, stepDirection,
                remainingCells, new ArrayList<>(remainingCells), storage, outline, 0, 0, 0, new HashMap<>());
    }

    private QuarrymanJobTask(Plugin plugin, JobManager jobManager, HelperLevelService levelService, UUID playerId,
                              NPC npc, Entity npcEntity, EntityEquipment equipment, TextDisplay label, World world,
                              Material initialTool, int minX, int maxX, int minZ, int maxZ, int topY,
                              int requestedDepth, boolean stepsAlongX, int stepDirection, Deque<Block> remainingCells,
                              List<Block> allCells, JobStorage storage, RegionOutline outline, long processedCells,
                              long clearedCells, long deposited, Map<Material, Integer> carried) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.jobManager = jobManager;
        this.levelService = levelService;
        this.playerId = playerId;
        this.npc = npc;
        this.npcEntity = npcEntity;
        this.equipment = equipment;
        this.label = label;
        this.world = world;
        this.currentTool = initialTool;
        this.minX = minX;
        this.maxX = maxX;
        this.minZ = minZ;
        this.maxZ = maxZ;
        this.topY = topY;
        this.requestedDepth = requestedDepth;
        this.stepsAlongX = stepsAlongX;
        this.stepDirection = stepDirection;
        this.remainingCells = remainingCells;
        this.allCells = allCells;
        this.totalCells = allCells.size();
        this.processedCells = processedCells;
        this.clearedCells = clearedCells;
        this.deposited = deposited;
        this.storage = storage;
        this.outline = outline;
        this.carried = carried;
        this.carriedTotal = carried.values().stream().mapToInt(Integer::intValue).sum();
        this.lastSafeLocation = npcEntity.getLocation();
        this.reconciliationCursor = processedCells;
    }

    /**
     * Rebuilds a job from a saved snapshot after a restart. Regenerates the identical dig
     * order via {@link #buildDigOrder} — deterministic from the 8 saved parameters — and
     * fast-forwards past {@code state.processedCells} entries, rather than needing the
     * queue itself serialized. Always restarts at {@link Phase#SEEKING} — a stale
     * mid-walk/mid-dig target isn't worth trusting after the world may have changed
     * underneath it, the exact reasoning {@link ClearJobTask#resume} already documents;
     * this job's own determinism doesn't buy a safe shortcut around that specific risk.
     * Returns null if the world or NPC entity this job needs no longer resolves.
     */
    static QuarrymanJobTask resume(Plugin plugin, JobManager jobManager, HelperLevelService levelService,
                                    JobState state, NPC npc) {
        World world = Bukkit.getWorld(state.worldName);
        Entity npcEntity = npc.getEntity();
        if (world == null || npcEntity == null) {
            return null;
        }

        // Bulkhead itself doesn't need to survive a restart — resuming at Phase.SEEKING
        // and re-detecting fresh is fine, same as every other mid-action phase. But a
        // plug left over from a restart mid-wait would otherwise be a permanent stray
        // cobblestone/sponge block. Ported from ClearJobTask.resume() — see its own doc.
        for (String encoded : state.bulkheadPlugs) {
            Block plug = JobStorage.decodeBlock(world, encoded);
            Material plugType = plug.getType();
            if (plugType == BULKHEAD_PLUG_MATERIAL || plugType == Material.SPONGE || plugType == Material.WET_SPONGE) {
                plug.setType(Material.AIR);
                plugin.getLogger().info("[quarryman] Bulkhead: cleared stray plug at resume for NPC #"
                        + state.npcId + " at " + plug.getX() + "," + plug.getY() + "," + plug.getZ());
            }
        }

        Deque<Block> digOrder = buildDigOrder(world, state.minX, state.maxX, state.minZ, state.maxZ, state.topY,
                state.requestedDepth, state.stepsAlongX, state.stepDirection);
        List<Block> allCells = new ArrayList<>(digOrder);
        for (long i = 0; i < state.processedCells && !digOrder.isEmpty(); i++) {
            digOrder.poll();
        }

        // Purely a cosmetic placeholder for the few ticks before the resumed job reaches
        // its first block — resume() always restarts at Phase.SEEKING, so beginDigging()
        // corrects this to the real per-block tool before any real digging happens.
        Material initialTool = Material.IRON_PICKAXE;
        EntityEquipment equipment = ClearJobTask.equipTool(npcEntity, initialTool, BuilderNpcService.baseNameOf(npc),
                TaskType.fromTool(initialTool).toolNoun());
        TextDisplay label = ClearJobTask.spawnLabel(npcEntity.getLocation());
        int bottomY = state.topY - state.requestedDepth + 1;
        RegionOutline outline = new RegionOutline(world, state.minX, bottomY, state.minZ,
                state.maxX, state.topY, state.maxZ);

        JobStorage storage = null;
        if (state.storeInChest) {
            storage = new JobStorage(plugin, world, state.minX, state.maxX, bottomY, state.topY,
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

        Map<Material, Integer> carried = new HashMap<>();
        for (var entry : state.carried.entrySet()) {
            Material material = Material.matchMaterial(entry.getKey());
            if (material != null) {
                carried.put(material, entry.getValue());
            }
        }

        return new QuarrymanJobTask(plugin, jobManager, levelService, state.playerId, npc, npcEntity, equipment,
                label, world, initialTool, state.minX, state.maxX, state.minZ, state.maxZ, state.topY,
                state.requestedDepth, state.stepsAlongX, state.stepDirection, digOrder, allCells, storage, outline,
                state.processedCells, state.clearedCells, state.deposited, carried);
    }

    @Override
    public NPC npc() {
        return npc;
    }

    @Override
    public UUID playerId() {
        return playerId;
    }

    @Override
    public boolean isPaused() {
        return paused;
    }

    /** Rough 0-100 estimate of how far through the dig order this job is. */
    private int percentComplete() {
        return totalCells == 0 ? 100 : (int) (processedCells * 100 / totalCells);
    }

    /**
     * Rough remaining-time estimate, extrapolated from progress-per-elapsed-time so far —
     * same shape as {@link ClearJobTask#estimatedRemainingMillis}. Only meaningful once
     * some real progress has been made; returns {@code Long.MAX_VALUE} before that so a
     * caller can treat "unknown" distinctly from "almost done."
     */
    @Override
    public long estimatedRemainingMillis() {
        int percent = percentComplete();
        if (percent <= 0) {
            return Long.MAX_VALUE;
        }
        long elapsed = System.currentTimeMillis() - startedAtMillis;
        long estimatedTotal = elapsed * 100 / percent;
        return Math.max(0, estimatedTotal - elapsed);
    }

    /**
     * Builds the fixed dig order — Kyle's own redesign, 2026-08-20, replacing an earlier
     * two-fixed-bench-plus-ramp shape, then extended the same day to take an explicit
     * requested depth (Level/Coordinates picker), then corrected twice more after live
     * testing: first for the shrinking-footprint shape being wrong (fixed 2026-08-20 —
     * the footprint is the SAME size at every level, sliding one block per level rather
     * than shrinking), then for the stepping axis being hardcoded to Z (fixed 2026-08-21
     * — {@code stepsAlongX} is Kyle's own rule, cross-validated against 8 worked examples
     * across two differently-shaped marked areas: compare the sign of the click's own X
     * delta to its own Z delta, not either axis's span. Same sign steps along X; opposite
     * signs steps along Z — see where {@code JobExecutionService#dispatchQuarryman}
     * derives {@code stepsAlongX}/{@code stepDirection} for the actual comparison).
     *
     * <p>Layer {@code layer} (0-indexed from {@code topY} down) keeps the cross axis
     * ({@code [minZ, maxZ]} when stepping along X, {@code [minX, maxX]} when stepping
     * along Z) unchanged, and shifts the stepping axis's own range by
     * {@code layer * stepDirection} — so layer 0 is exactly the marked box, and every
     * layer below it is the identical size, just shifted one block further along the
     * stepping axis. Deliberately uncapped by the marked footprint's own size: Kyle's own
     * words, "the work area boundary only serves as [the] starting point of the
     * quarry... [it] takes regions of unlimited depth, down to bedrock if you ask" — a
     * footprint marked 2 blocks long in the stepping axis can still take a depth of 50,
     * sliding 50 blocks past its own original edge by the time it's done. The only
     * remaining cap is the physical one already enforced by the caller: not digging below
     * {@code world.getMinHeight()}.
     *
     * <p>Consecutive levels overlap heavily along the stepping axis (a 7-long footprint
     * at adjacent levels shares 6 of those 7 rows) — each shared row is still queued
     * exactly once per level, since the two levels dig it at different Y values, not the
     * same cell twice. Each level's leading edge reaches one block further than the level
     * above's own leading edge ever did, which is what makes this a walkable staircase
     * rather than a vertical-walled pit — the same "nosing" a real stair tread has over
     * the riser below it.
     */
    static Deque<Block> buildDigOrder(World world, int minX, int maxX, int minZ, int maxZ, int topY,
                                       int requestedDepth, boolean stepsAlongX, int stepDirection) {
        Deque<Block> cells = new ArrayDeque<>();
        boolean forward = true;
        for (int layer = 0; layer < requestedDepth; layer++) {
            int y = topY - layer;
            int shift = layer * stepDirection;
            if (stepsAlongX) {
                int layerMinX = minX + shift;
                int layerMaxX = maxX + shift;
                for (int x = layerMinX; x <= layerMaxX; x++) {
                    sweepRow(cells, world, x, y, minZ, maxZ, forward, true);
                    forward = !forward;
                }
            } else {
                int layerMinZ = minZ + shift;
                int layerMaxZ = maxZ + shift;
                for (int z = layerMinZ; z <= layerMaxZ; z++) {
                    sweepRow(cells, world, z, y, minX, maxX, forward, false);
                    forward = !forward;
                }
            }
        }

        return cells;
    }

    /** One row's full width across the cross axis, alternating sweep direction per row (serpentine) so the walk never has to run back across an already-finished row. */
    private static void sweepRow(Deque<Block> cells, World world, int fixedCoord, int y,
                                  int crossMin, int crossMax, boolean forward, boolean fixedIsX) {
        if (forward) {
            for (int c = crossMin; c <= crossMax; c++) {
                cells.add(fixedIsX ? world.getBlockAt(fixedCoord, y, c) : world.getBlockAt(c, y, fixedCoord));
            }
        } else {
            for (int c = crossMax; c >= crossMin; c--) {
                cells.add(fixedIsX ? world.getBlockAt(fixedCoord, y, c) : world.getBlockAt(c, y, fixedCoord));
            }
        }
    }

    @Override
    public void start() {
        npc.getNavigator().getDefaultParameters()
                .speedModifier(WALK_SPEED_MODIFIER)
                .range(PATHFINDING_RANGE)
                .fallDistance(MAX_TOLERATED_FALL)
                .stuckAction(SafeStuckAction.INSTANCE);
        paused = false;
        refreshChunkTickets();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 0L, 1L);
    }

    /** Stops ticking but keeps every in-memory field intact, so {@link #resumeTicking()} picks up mid-stride — same shape as {@code ClearJobTask}'s own. */
    @Override
    public void pause() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        npc.getNavigator().cancelNavigation();
        releaseChunkTickets();
        paused = true;
    }

    @Override
    public void resumeTicking() {
        if (paused) {
            start();
        }
    }

    /** Stops the job for good, mid-run — not a natural completion, so no "finished" summary. Safe to call from the cancel-by-chat path. */
    @Override
    public void cancelJob() {
        endJob();
        logger.info(BuilderNpcService.baseNameOf(npc) + "'s Quarryman job was cancelled ["
                + clearedCells + " cleared, " + deposited + " stored]");
    }

    /**
     * Keeps the chunk the Helper is standing in — plus wherever it's headed next — force
     * loaded, so the job survives nobody being nearby instead of dying the moment its
     * chunk would otherwise unload. Added 2026-08-21 alongside real persistence: a job
     * meant to survive being unattended across a restart should also survive being
     * unattended while merely running. Same shape as {@code ClearJobTask}'s own
     * {@code refreshChunkTickets()} — deliberately narrow (just the immediate work area),
     * routed through {@link JobManager} since Paper's ticket API is keyed by
     * (chunk, plugin) and another job could need the same chunk at the same time.
     */
    private void refreshChunkTickets() {
        Set<Long> desired = new HashSet<>();
        desired.add(chunkKey(npcEntity.getLocation()));
        if (pendingCell != null) {
            desired.add(chunkKey(pendingCell.getLocation()));
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

    private void tick() {
        if (!npcEntity.isValid()) {
            finish(BuilderNpcService.baseNameOf(npc) + " disappeared mid-job — quarrying stopped early.");
            return;
        }

        if (hesitationTicks > 0) {
            hesitationTicks--;
        } else {
            switch (phase) {
                case SEEKING -> seekNextCell();
                case WALKING -> walkToPendingCell();
                case DIGGING -> digPendingCell();
                case HAULING -> haulToChest();
                case BULKHEAD -> tickBulkhead();
            }
        }

        if ((phase == Phase.WALKING || phase == Phase.HAULING) && outlineTicks % WALK_CUE_PERIOD == 0) {
            world.spawnParticle(Particle.CLOUD, npcEntity.getLocation().add(0, 0.1, 0),
                    2, 0.15, 0.05, 0.15, 0.01);
        }
        if (outlineTicks % GLOW_TICK_PERIOD == 0) {
            outline.drawGlowNearby();
            refreshChunkTickets();
        }
        if (outlineTicks % TINT_TICK_PERIOD == 0) {
            outline.drawTintNearby(RegionOutline.WORKING_TINT, RegionOutline.tintSize(outlineTicks));
        }
        if (outlineTicks % MOB_ALERT_CHECK_PERIOD == 0) {
            checkForHostileMobs();
        }
        if (outlineTicks % RECONCILE_CHECK_PERIOD_TICKS == 0) {
            reconcileRecentCells();
        }
        outlineTicks++;
        updateLabel();
    }

    private void checkForHostileMobs() {
        for (Entity nearby : npcEntity.getNearbyEntities(MOB_ALERT_RADIUS, MOB_ALERT_RADIUS, MOB_ALERT_RADIUS)) {
            if (nearby instanceof Monster monster && alertedMobs.add(monster.getUniqueId())) {
                messagePlayer(Component.text(
                        BuilderNpcService.baseNameOf(npc) + ": Careful — there's a " + prettyName(monster.getType()) + " nearby!",
                        NamedTextColor.RED));
            }
        }
    }

    private static String prettyName(EntityType type) {
        String name = type.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    /**
     * Polls the next cell, skipping anything not actually diggable (already air, or a
     * cell an earlier, overlapping level already dug — see {@link #buildDigOrder}'s own
     * doc for why consecutive levels share rows). No fumble/error-rate roll here, unlike
     * {@link ClearJobTask}: a fumbled cell there gets revisited by the next sweep pass,
     * but a fumble here would just leave a permanent stray block — not worth it for a
     * small, deterministic shape where nothing should ever actually miss.
     *
     * <p>One thing can still leave a block undug: a gravity block (sand, gravel) from
     * outside the excavation — beside it, or resting above it — sliding into an
     * already-cleared cell after the Helper has already moved past it. The marked
     * footprint doesn't fence off what's beside or above the dig, and this isn't rare
     * enough to shrug off (confirmed live 2026-08-21, Kyle flagging it before it had
     * actually happened rather than after — same {@code ClearJobTask} precedent this
     * borrows from: its own final whole-region "reconciling" pass exists for the identical
     * reason). {@link #reconcileRecentCells} periodically requeues anything resettled into
     * {@link #reconciliationQueue}, drained here FIRST, ahead of {@link #remainingCells} —
     * handled next, not "eventually." Deliberately kept out of {@code processedCells}/
     * {@code remainingCells} entirely: those two drive {@link #resume}'s deterministic
     * fast-forward through {@link #allCells}'s own canonical order, and a resettled cell
     * isn't a new position in that order — bumping {@code processedCells} for it would
     * inflate the count past its true meaning, and on a pause/resume before the resettled
     * cell was actually redug, the fast-forward would silently skip past real, never-dug
     * cells later in the order. {@link #queueReconciliationCells}'s own end-of-job pass is
     * a still-needed safety net on top of this, not made redundant by it: this one only
     * ever re-checks cells once, in the small window since the last periodic check, so
     * anything resettled while genuinely mid-pause (this in-memory queue doesn't survive a
     * restart) has to wait for that final full sweep to be caught at all.
     */
    private void seekNextCell() {
        Block reconciled0 = reconciliationQueue.poll();
        if (reconciled0 != null) {
            if (!Target.ANY_EARTH.matches(reconciled0.getType())) {
                // Handled some other way already (e.g. digested by the time we got back to
                // it) — nothing to do, next tick's seekNextCell tries again.
                return;
            }
            pendingCell = reconciled0;
            walkTicks = 0;
            retriedWalk = false;
            noProgressTicks = 0;
            recoveryAttempted = false;
            closestApproachSquared = Double.MAX_VALUE;
            npc.getNavigator().setTarget(reconciled0.getLocation().add(0.5, 1.0, 0.5));
            phase = Phase.WALKING;
            return;
        }

        Block candidate;
        do {
            candidate = remainingCells.poll();
            if (candidate == null) {
                if (!reconciled) {
                    if (!awaitingReconciliation) {
                        awaitingReconciliation = true;
                        hesitationTicks = RECONCILE_SETTLE_TICKS;
                        return;
                    }
                    reconciled = true;
                    if (queueReconciliationCells()) {
                        return;
                    }
                }
                if (carriedTotal > 0) {
                    startHauling();
                } else {
                    finish(BuilderNpcService.baseNameOf(npc) + ": Quarry's done — " + clearedCells
                            + " block(s) dug." + storedSuffix());
                }
                return;
            }
            processedCells++;
        } while (!Target.ANY_EARTH.matches(candidate.getType()));

        pendingCell = candidate;
        walkTicks = 0;
        retriedWalk = false;
        noProgressTicks = 0;
        recoveryAttempted = false;
        closestApproachSquared = Double.MAX_VALUE;
        npc.getNavigator().setTarget(navigationTargetFor(candidate));
        phase = Phase.WALKING;
    }

    /**
     * The point Citizens should be told to walk to for {@code cell} — normally straight
     * above it (open air a player would stand in to dig it), but not when that point is
     * itself inside solid, un-dug rock: on a "leading edge" column (see
     * {@link #buildDigOrder}'s own doc), the block above the target hasn't been opened
     * either, since no shallower layer's window ever reached it, making that point a
     * physically impossible destination. Confirmed live 2026-08-22 — Kyle's screenshot
     * showed a plainly walkable approach to exactly this kind of row that Citizens still
     * reported {@code navigating=false} for, repeatedly, even immediately after a fresh
     * teleport onto solid ground: it wasn't struggling to get close, the destination
     * itself didn't exist. Falls back to {@link #lastSafeLocation} instead — always open
     * (Horace stood there without incident) and always close (dig cells are never more
     * than 1 block apart by construction) — and lets the ordinary {@code REACH_DISTANCE}
     * check in {@link #walkToPendingCell} (measured against the cell's own center, not
     * this nav target) trigger digging once Horace is genuinely near, rather than
     * requiring him to literally stand inside rock.
     */
    private Location navigationTargetFor(Block cell) {
        Block above = cell.getRelative(BlockFace.UP);
        if (above.getType() == Material.AIR || above.isPassable()) {
            return cell.getLocation().add(0.5, 1.0, 0.5);
        }
        return lastSafeLocation;
    }

    /**
     * Re-checks the small window of cells passed since the last call for anything solid
     * again (a gravity block that resettled after the Helper moved on), and, unlike the
     * end-of-job pass in {@link #queueReconciliationCells}, requeues any it finds into
     * {@link #reconciliationQueue} — handled next, not "eventually," matching what a
     * player watching actually expects (confirmed live 2026-08-21: the end-of-job-only
     * version reads as broken — a fallen block gets walked straight past and left for the
     * rest of a potentially long dig). {@code processedCells} only ever grows, so this
     * window never re-examines the same cell twice; see {@link #seekNextCell}'s own doc
     * for why the found cells go into a separate queue rather than back into
     * {@link #remainingCells} directly.
     *
     * <p>Stops one short of {@code processedCells} — {@code pendingCell} itself (whatever
     * is currently being walked to or dug) always sits at index {@code processedCells - 1},
     * since {@code processedCells} increments the instant a cell is POPPED in
     * {@link #seekNextCell}, not once it's actually cleared. Confirmed live 2026-08-22:
     * without this, a cell Horace simply hadn't reached yet (still solid, correctly)
     * got misread as "resettled" and duplicated into the reconciliation queue while the
     * real walk to it was still in progress.
     */
    private void reconcileRecentCells() {
        int upTo = (int) Math.min(processedCells - 1, allCells.size());
        int from = (int) Math.min(reconciliationCursor, upTo);
        for (int i = from; i < upTo; i++) {
            Block cell = allCells.get(i);
            if (Target.ANY_EARTH.matches(cell.getType())) {
                reconciliationQueue.add(cell);
                logger.info(BuilderNpcService.baseNameOf(npc) + ": a block resettled at "
                        + cell.getX() + "," + cell.getY() + "," + cell.getZ() + " — clearing it again.");
            }
        }
        reconciliationCursor = Math.max(0, upTo);
    }

    /** Re-checks every cell the dig order ever queued for anything solid again, queuing it for a normal dig. Returns whether it found anything. */
    private boolean queueReconciliationCells() {
        int requeued = 0;
        for (Block cell : allCells) {
            if (Target.ANY_EARTH.matches(cell.getType())) {
                remainingCells.add(cell);
                requeued++;
            }
        }
        if (requeued > 0) {
            logger.info(BuilderNpcService.baseNameOf(npc) + ": reconciliation pass found " + requeued
                    + " cell(s) resettled since being cleared — clearing them too.");
        }
        return requeued > 0;
    }

    private void walkToPendingCell() {
        if (pendingCell.getType() == Material.AIR) {
            phase = Phase.SEEKING;
            return;
        }

        Location current = npcEntity.getLocation();
        Location cellCenter = pendingCell.getLocation().add(0.5, 0.5, 0.5);

        // Straight-down shaft descent: same column, but far enough below that
        // REACH_DISTANCE would otherwise let the Helper dig several layers from one
        // stationary spot before ever moving down into the hole — confirmed live
        // 2026-08-20, a 6-block reach-then-stuck gap ("navigating=false"), because
        // Citizens' A* has no walkable floor to path across inside an open vertical
        // shaft, no matter how generous fallDistance is set. Teleporting straight down
        // through this column is safe with certainty (see safeLandingAbove for why it's
        // not simply "one block up") — the same "stop trusting pathfinding for a known
        // shape, move through it directly instead" call attemptScaffoldUp already made
        // for climbing up, just this is its descending counterpart.
        double horizontalOffsetSquared = square(current.getX() - cellCenter.getX())
                + square(current.getZ() - cellCenter.getZ());
        double verticalDrop = current.getY() - cellCenter.getY();
        if (horizontalOffsetSquared <= 1.0 && verticalDrop > MAX_TOLERATED_FALL) {
            Location landing = safeLandingAbove(pendingCell);
            if (landing != null) {
                npc.getNavigator().cancelNavigation();
                npc.teleport(landing, PlayerTeleportEvent.TeleportCause.PLUGIN);
                beginDigging();
                return;
            }
            // No confirmed-safe spot within range — reposition to lastSafeLocation
            // (guaranteed safe and connected, see that field's own doc) rather than leave
            // the Helper stuck in a bad column, then fall through to ordinary walking
            // rather than returning: this check has no patience of its own (re-fires every
            // tick the condition holds), so if the fallback spot somehow triggers it again
            // too, walkTicks below still has to keep counting toward a loud
            // WALK_TIMEOUT_TICKS abort instead of resetting forever.
            npc.getNavigator().cancelNavigation();
            npc.teleport(lastSafeLocation, PlayerTeleportEvent.TeleportCause.PLUGIN);
        }

        double distanceSquared = current.distanceSquared(cellCenter);
        if (distanceSquared <= REACH_DISTANCE * REACH_DISTANCE) {
            npc.getNavigator().cancelNavigation();
            beginDigging();
            return;
        }

        walkTicks++;

        // Tracks whether Citizens is actually closing the distance to pendingCell — same
        // closest-approach/noProgressTicks idiom ClearJobTask already uses, not scoped to
        // above- or below-grade: a genuine walk (however long, however it starts) keeps
        // this at zero as long as SOME progress keeps happening; it only accumulates once
        // Citizens has truly stalled, regardless of which direction that stall is in.
        if (distanceSquared < closestApproachSquared - PROGRESS_EPSILON) {
            closestApproachSquared = distanceSquared;
            noProgressTicks = 0;
        } else {
            noProgressTicks++;
        }

        // One free retry if Citizens never actually engaged shortly after being told to —
        // a transient pathfinding hiccup is cheap to rule out before waiting out the full
        // timeout on it. Not a real diagnosis (still no straight-line fallback, scaffold-
        // climb, or GhostDig), just a single re-ask.
        if (walkTicks == RETRY_GRACE_TICKS && !retriedWalk && !npc.getNavigator().isNavigating()) {
            retriedWalk = true;
            logger.info(BuilderNpcService.baseNameOf(npc) + ": navigation hadn't engaged after "
                    + RETRY_GRACE_TICKS + " ticks — retrying the same target once.");
            npc.getNavigator().setTarget(navigationTargetFor(pendingCell));
            return;
        }

        // Genuinely stuck — not closing the distance for STUCK_RECOVERY_NO_PROGRESS_TICKS.
        // Confirmed live 2026-08-20 in two different shapes: falling off the staircase
        // while running and landing below grade unable to climb back (jumping in place at
        // the bottom — the case this was first built for), and — after the sliding-window
        // shape landed the same night — the opposite direction: Citizens routing itself up
        // and completely out of the pit while trying to reach a cell close by, then never
        // finding its own way back down (real log line: "from 71,66,9 to 72,58,9,
        // navigating=false" — 1 block over, 8 blocks up, stuck at the rim; Kyle: "the next
        // block was just right in front of him" but Horace "would go up the quarry, stay up
        // top"). That case is close enough to the shaft-descent check above to look like it
        // should have caught it — but that check fires immediately, with no patience, so
        // it's deliberately narrow (near-exact same column) to avoid misfiring during
        // ordinary walks; this one has already waited out
        // STUCK_RECOVERY_NO_PROGRESS_TICKS, so it can afford to be the broader net. Citizens'
        // own stuck-recovery (SafeStuckAction, wired into start()) should catch cases like
        // this but apparently doesn't fire reliably (see SafeStuckAction's own doc).
        // Recovering here instead of waiting on that or the full 30-second timeout —
        // teleport into a confirmed-clear spot near pendingCell regardless of which
        // direction the Helper actually got stuck in.
        if (noProgressTicks > STUCK_RECOVERY_NO_PROGRESS_TICKS && !recoveryAttempted) {
            recoveryAttempted = true;
            // Extra detail goes to the log — current position and whether Citizens ever
            // actually engaged, same reasoning as the walk-timeout log below. Added
            // 2026-08-21 after a resumed job needed this recovery on nearly every single
            // cell for an entire session, which this detail should help explain if it
            // happens again rather than needing to guess a second time.
            logger.warning(BuilderNpcService.baseNameOf(npc) + ": no progress toward "
                    + pendingCell.getX() + "," + pendingCell.getY() + "," + pendingCell.getZ()
                    + " for " + STUCK_RECOVERY_NO_PROGRESS_TICKS + " ticks — recovering from a stuck path. From "
                    + current.getBlockX() + "," + current.getBlockY() + "," + current.getBlockZ()
                    + ", navigating=" + npc.getNavigator().isNavigating());
            Location landing = safeLandingAbove(pendingCell);
            if (landing != null) {
                npc.getNavigator().cancelNavigation();
                npc.teleport(landing, PlayerTeleportEvent.TeleportCause.PLUGIN);
                beginDigging();
                return;
            }
            // Nothing confirmed-clear within MAX_LANDING_SEARCH_HEIGHT — same "don't
            // gamble on an unverified spot" call SafeStuckAction already makes for its own
            // search. Falls back to lastSafeLocation instead of leaving the Helper
            // wherever the failed walk stranded it (confirmed live 2026-08-21: Citizens
            // climbing itself up and out of the pit while failing to path down, then
            // stopping there) — guaranteed safe and connected, even if it's a few cells
            // behind, so this resets walk-tracking and lets a fresh walk try from solid
            // ground rather than beginning digging immediately. recoveryAttempted already
            // makes this one-shot per walk, so resetting walkTicks here doesn't risk an
            // infinite loop — WALK_TIMEOUT_TICKS still aborts loudly if even the fresh
            // attempt can't reach pendingCell.
            logger.warning(BuilderNpcService.baseNameOf(npc)
                    + ": no confirmed-safe spot near " + pendingCell.getX() + "," + pendingCell.getY() + ","
                    + pendingCell.getZ() + " — falling back to the last spot known safe instead of guessing.");
            npc.getNavigator().cancelNavigation();
            npc.teleport(lastSafeLocation, PlayerTeleportEvent.TeleportCause.PLUGIN);
            walkTicks = 0;
            noProgressTicks = 0;
            closestApproachSquared = Double.MAX_VALUE;
            npc.getNavigator().setTarget(navigationTargetFor(pendingCell));
            return;
        }

        if (walkTicks > WALK_TIMEOUT_TICKS) {
            // Extra detail goes to the log, not the chat message — current position (from
            // the top of this same tick) and whether Citizens ever actually engaged
            // distinguish "never moved at all" (most likely a navigator-parameter/pathing
            // issue) from "started walking, then genuinely got stuck partway" (more
            // likely a real obstruction).
            logger.warning(BuilderNpcService.baseNameOf(npc) + ": walk timeout — from "
                    + current.getBlockX() + "," + current.getBlockY() + "," + current.getBlockZ()
                    + " to " + pendingCell.getX() + "," + pendingCell.getY() + "," + pendingCell.getZ()
                    + ", navigating=" + npc.getNavigator().isNavigating());
            abortJob(BuilderNpcService.baseNameOf(npc) + ": Something's blocking my way to "
                    + pendingCell.getX() + "," + pendingCell.getY() + "," + pendingCell.getZ()
                    + " and I can't work around it — stopping here rather than getting stuck.");
        }
    }

    /**
     * How far {@link #safeLandingAbove} will search upward before giving up and landing on
     * the cell itself. Deliberately NOT "however far it takes to find two clear blocks" —
     * confirmed live 2026-08-21 (the same session the two-block fix below shipped in) that
     * an unbounded search is itself a second, separate bug: on a "leading edge" column
     * (see this method's own doc) with no cave nearby, the first genuine two-block gap can
     * be the real, distant surface, or an unrelated cave dozens of blocks away — nothing
     * in {@code buildDigOrder}'s shape guarantees THAT spot is walkably connected back to
     * the dig, only that it's clear. Landing there strands the Helper somewhere Citizens
     * cannot path back down from through unbroken rock, so the very next cell (and
     * typically every cell after it) fails the exact same walk for the exact same reason,
     * each failure teleporting further away — a cascade, not a one-off. Live logs from
     * that session show it happening on fresh dispatches too, not just after a
     * restart-resume, so it isn't resume-specific. Bounding the search keeps a bad landing
     * within a couple of {@link #REACH_DISTANCE}s of the dig, so the cell-itself fallback
     * below — always walkably connected, since it's the dig's own known path — kicks in
     * for genuinely deep leading-edge columns instead of a teleport to nowhere.
     */
    private static final int MAX_LANDING_SEARCH_HEIGHT = 16;

    /**
     * Finds a genuinely clear spot to teleport onto directly above {@code cell}, for both
     * teleport-recovery cases in {@link #walkToPendingCell}. "One block above the cell" is
     * NOT always safe under {@link #buildDigOrder}'s sliding-window shape: only the Z rows
     * a shallower layer already happened to share get cleared above them by construction —
     * each layer's own newest row (the one row no shallower layer's window ever reached)
     * can have solid, never-dug rock stacked above it, as many blocks deep as the dig
     * currently is. Searches straight up for the first spot with two clear blocks —
     * feet AND head — instead of assuming one block is enough: this search walks through
     * real, unmodified world terrain above the excavation, not just the dig's own shape,
     * and natural stone is riddled with small one-block cave/ore pockets a single-block
     * check would happily land inside. Confirmed live 2026-08-21 — Horace suffocated
     * after an unusually deep quarry drove through exactly that gap; a single-block check
     * had already been landing Helpers in ordinary terrain safely for weeks, since a real
     * cave big enough to walk through also has two-block clearance, but a
     * naturally-generated one-block pocket doesn't, and this dig simply hadn't gone deep
     * enough into varied stone to hit one before. Bounded to {@link #MAX_LANDING_SEARCH_HEIGHT}
     * rather than the world ceiling — see that constant's own doc for why unbounded was a
     * second, separate bug — so it always terminates well short of anywhere truly
     * disconnected, same "confirm before you land on it" precedent {@link SafeStuckAction}
     * already sets for its own upward search.
     *
     * <p>Returns {@code null}, never an unverified spot, when nothing confirmed-clear turns
     * up within {@link #MAX_LANDING_SEARCH_HEIGHT}. An earlier version of this bound (same
     * night) fell back to landing one block above {@code cell} with NO clearance check at
     * all once the search ran out — reasoned as "shouldn't happen in practice" back when the
     * search was unbounded, but bounding it made that fallback the COMMON case for any
     * genuinely deep leading-edge column, and it killed Horace again within minutes (deep
     * rock directly above the cell too). Both callers in {@link #walkToPendingCell} already
     * know how to handle {@code null}: don't teleport, let the walk keep trying.
     */
    private Location safeLandingAbove(Block cell) {
        Block probe = cell;
        int maxSearch = Math.min(MAX_LANDING_SEARCH_HEIGHT, world.getMaxHeight() - cell.getY());
        for (int i = 0; i < maxSearch; i++) {
            probe = probe.getRelative(BlockFace.UP);
            if (probe.getType() == Material.AIR && probe.getRelative(BlockFace.UP).getType() == Material.AIR) {
                return probe.getLocation().add(0.5, 0, 0.5);
            }
        }
        return null;
    }

    private void beginDigging() {
        Material neededTool = BlockTool.bestToolFor(pendingCell.getType());
        if (neededTool != currentTool) {
            currentTool = neededTool;
            ClearJobTask.equipTool(npcEntity, currentTool, BuilderNpcService.baseNameOf(npc),
                    TaskType.fromTool(currentTool).toolNoun());
        }
        digTicks = 0;
        phase = Phase.DIGGING;
    }

    private int digTicksForPendingCell() {
        int vanillaTicks = VanillaTiming.durationFor(
                TaskType.QUARRY, new ItemStack(currentTool), pendingCell.getBlockData());
        return HelperTempo.digTicksFor(levelService.levelOf(npc), vanillaTicks);
    }

    private void digPendingCell() {
        if (pendingCell.getType() == Material.AIR) {
            phase = Phase.SEEKING;
            return;
        }

        faceBlock(pendingCell);
        if (digTicks % 4 == 0) {
            swingArm();
            world.spawnParticle(Particle.BLOCK, pendingCell.getLocation().add(0.5, 0.5, 0.5),
                    8, 0.25, 0.25, 0.25, pendingCell.getBlockData());
        }
        digTicks++;

        if (digTicks < digTicksForPendingCell()) {
            return;
        }

        world.playSound(pendingCell.getLocation(),
                pendingCell.getBlockData().getSoundGroup().getBreakSound(), 1.0f, 1.0f);
        world.spawnParticle(Particle.BLOCK, pendingCell.getLocation().add(0.5, 0.5, 0.5),
                16, 0.3, 0.3, 0.3, pendingCell.getBlockData());

        collectDrop(pendingCell);
        pendingCell.setType(Material.AIR);
        clearedCells++;
        lastSafeLocation = npcEntity.getLocation();

        // Phase D — fluid at scale. No level gate to check here, unlike ClearJobTask's own
        // canBreachLava(): Quarryman is already an L8+ ability, so a level-6+ Groundworker
        // is guaranteed. Checked every cell, not just leading-edge ones — a Quarry can
        // breach a lake from the side just as easily as from below.
        List<Block> waterBreach = detectWaterBreach(pendingCell);
        List<Block> lavaBreach = detectLavaBreach(pendingCell);
        if (!waterBreach.isEmpty() || !lavaBreach.isEmpty()) {
            beginBulkhead(waterBreach, lavaBreach);
            return;
        }

        if (clearedCells % TORCH_SPACING_CELLS == 0) {
            placeFaceTorch(pendingCell);
        }

        hesitationTicks = HelperTempo.hesitationTicksForDuty(levelService.dutyCycleOf(npc));

        if (carriedTotal >= CARRY_CAPACITY) {
            startHauling();
        } else {
            phase = Phase.SEEKING;
        }
    }

    /**
     * Ported from {@code ClearJobTask#detectWaterBreach} — see that method's own doc for
     * the full reasoning (spread-out anchors, flat-ring/vertical-accent split, why a
     * naive "just the 6 adjacent faces" version undersells real coverage). Unchanged
     * behavior, just retargeted at {@code cell} instead of {@code diggedBlock}.
     */
    private List<Block> detectWaterBreach(Block cell) {
        List<Block> flatRing = new ArrayList<>();
        List<Block> verticalAccents = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Deque<Block> frontier = new ArrayDeque<>();
        for (BlockFace face : ADJACENT_FACES) {
            Block neighbor = cell.getRelative(face);
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
                int dy = Math.abs(current.getY() - cell.getY());
                if (dy >= BULKHEAD_VERTICAL_DY_THRESHOLD) {
                    if (verticalAccents.size() < BULKHEAD_MAX_VERTICAL_SPONGES) {
                        verticalAccents.add(current);
                    }
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
                Comparator.<Block>comparingInt(anchor -> squaredDistance(anchor, cell)).reversed();
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

    /** Ported from {@code ClearJobTask#detectLavaBreach} — see that method's own doc. Unchanged behavior. */
    private List<Block> detectLavaBreach(Block cell) {
        List<Block> found = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Deque<Block> frontier = new ArrayDeque<>();
        for (BlockFace face : ADJACENT_FACES) {
            Block neighbor = cell.getRelative(face);
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

    /** Ported from {@code ClearJobTask#beginBulkhead} — see that method's own doc. Unchanged behavior. */
    private void beginBulkhead(List<Block> waterBreach, List<Block> lavaBreach) {
        npc.getNavigator().cancelNavigation();
        for (Block source : lavaBreach) {
            source.setType(BULKHEAD_PLUG_MATERIAL);
            bulkheadPlugs.add(source);
        }
        bulkheadWaveAnchors.clear();
        bulkheadWaveAnchors.addAll(waterBreach);
        bulkheadWaveIndex = 0;
        bulkheadWaveStepTicks = BULKHEAD_WAVE_TICKS_PER_STEP;
        bulkheadTicks = bulkheadWaveAnchors.isEmpty() ? BULKHEAD_SETTLE_TICKS : 0;
        phase = Phase.BULKHEAD;
        logger.info("[quarryman] Bulkhead: breach detected, " + waterBreach.size() + " sponge(s) queued and "
                + lavaBreach.size() + " lava source(s) plugged for " + BuilderNpcService.baseNameOf(npc)
                + (lavaBreach.size() >= BULKHEAD_MAX_PLUGS ? " (lava fill hit the cap — partial seal of a larger body)" : ""));
    }

    /** Ported from {@code ClearJobTask#tickBulkhead} — see that method's own doc. Unchanged behavior. */
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
            logger.info("[quarryman] Bulkhead: sponge " + bulkheadWaveIndex + "/" + bulkheadWaveAnchors.size()
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
        logger.info("[quarryman] Bulkhead: unplugged, resuming for " + BuilderNpcService.baseNameOf(npc));
        bulkheadWaveAnchors.clear();
        bulkheadWaveIndex = 0;
        phase = Phase.SEEKING;
        hesitationTicks = HelperTempo.hesitationTicksForDuty(levelService.dutyCycleOf(npc));
        if (carriedTotal >= CARRY_CAPACITY) {
            startHauling();
        }
    }

    /** Clears any Bulkhead plug blocks back to air — shared by every teardown path so none of them can strand one. */
    private void clearBulkheadPlugs() {
        for (Block plug : bulkheadPlugs) {
            plug.setType(Material.AIR);
        }
        bulkheadPlugs.clear();
    }

    /**
     * Phase D face-lighting. {@code cell} is freshly dug (just set to air) and already
     * confirmed no fluid breach — checked for real darkness (see
     * {@link #TORCH_LIGHT_THRESHOLD}) and, if dark, lit with a real, chest-withdrawn torch
     * (see {@link #TORCH_MATERIAL}'s own doc for why this one isn't free like a Bulkhead
     * plug). Prefers a wall torch on whichever adjacent solid face it finds first — a
     * quarry staircase always has at least one solid wall immediately beside the walking
     * path, by {@link #buildDigOrder}'s own shape — falling back to a standing torch on
     * the floor below if none turns up (a genuinely open, multi-cell-wide gap). Does
     * nothing, silently, if storage is missing or simply out of torches — lighting is a
     * quality improvement, never a reason to block the dig.
     */
    private void placeFaceTorch(Block cell) {
        if (storage == null || cell.getLightLevel() > TORCH_LIGHT_THRESHOLD) {
            return;
        }
        for (BlockFace face : HORIZONTAL_FACES) {
            Block wall = cell.getRelative(face);
            if (wall.getType().isSolid()) {
                if (storage.withdraw(TORCH_MATERIAL, 1) < 1) {
                    return;
                }
                cell.setType(WALL_TORCH_MATERIAL);
                if (cell.getBlockData() instanceof Directional directional) {
                    directional.setFacing(face.getOppositeFace());
                    cell.setBlockData(directional);
                }
                return;
            }
        }
        Block below = cell.getRelative(BlockFace.DOWN);
        if (below.getType().isSolid()) {
            if (storage.withdraw(TORCH_MATERIAL, 1) < 1) {
                return;
            }
            cell.setType(TORCH_MATERIAL);
        }
    }

    private void collectDrop(Block block) {
        if (storage == null) {
            return;
        }
        for (ItemStack drop : block.getDrops(new ItemStack(currentTool))) {
            int amount = drop.getAmount();
            carried.merge(drop.getType(), amount, Integer::sum);
            carriedTotal += amount;
        }
    }

    private void startHauling() {
        depositPoint = storage.depositPoint();
        if (depositPoint == null) {
            messagePlayer(Component.text(
                    "No room to place a storage chest nearby — " + BuilderNpcService.baseNameOf(npc)
                            + " is working without one.", NamedTextColor.RED));
            carried.clear();
            carriedTotal = 0;
            phase = Phase.SEEKING;
            return;
        }
        walkTicks = 0;
        noProgressTicks = 0;
        closestApproachSquared = Double.MAX_VALUE;
        npc.getNavigator().setTarget(depositPoint);
        phase = Phase.HAULING;
    }

    private void haulToChest() {
        double distanceSquared = npcEntity.getLocation().distanceSquared(depositPoint);
        if (distanceSquared <= REACH_DISTANCE * REACH_DISTANCE) {
            unload();
            return;
        }

        walkTicks++;

        // Same closest-approach/noProgressTicks idiom walkToPendingCell already uses.
        // Added 2026-08-21: a haul trip previously had no fast self-heal, unlike the dig
        // walk — only this same WALK_TIMEOUT_TICKS escalation below. unload() doesn't
        // actually require standing at the chest (it deposits from wherever, same as
        // ClearJobTask's own version), so once genuinely stuck there's nothing to gain by
        // waiting out the full 30-second timeout first.
        if (distanceSquared < closestApproachSquared - PROGRESS_EPSILON) {
            closestApproachSquared = distanceSquared;
            noProgressTicks = 0;
        } else {
            noProgressTicks++;
        }
        if (noProgressTicks > STUCK_RECOVERY_NO_PROGRESS_TICKS) {
            logger.warning(BuilderNpcService.baseNameOf(npc) + ": no progress toward the storage chest for "
                    + STUCK_RECOVERY_NO_PROGRESS_TICKS + " ticks — unloading from here rather than waiting it out. "
                    + "navigating=" + npc.getNavigator().isNavigating());
            unload();
            return;
        }

        if (walkTicks > WALK_TIMEOUT_TICKS) {
            // Same escalation as ClearJobTask: unload from wherever it got stuck rather
            // than wedging the job forever over an unreachable chest.
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

        // Deliberately no early finish() here even if remainingCells is already empty —
        // phase = SEEKING either way, so seekNextCell() is the one and only place that
        // decides what "nothing left to do" means (reconciliation pass, then finish).
        // Two separate finish paths would mean two separate reconciliation checks to keep
        // in sync — this keeps it to one.
        phase = Phase.SEEKING;
    }

    private String storedSuffix() {
        return storage == null ? "" : " " + deposited + " item(s) stored.";
    }

    private void swingArm() {
        if (npcEntity instanceof LivingEntity livingEntity) {
            livingEntity.swingMainHand();
        }
    }

    private void faceBlock(Block block) {
        npc.faceLocation(block.getLocation().add(0.5, 0.5, 0.5));
    }

    private static double square(double value) {
        return value * value;
    }

    private void updateLabel() {
        label.teleport(npcEntity.getLocation().add(0, LABEL_HEIGHT_OFFSET, 0));
        int percent = totalCells == 0 ? 100 : (int) Math.min(100, processedCells * 100 / totalCells);
        label.text(Component.text(percent + "%", NamedTextColor.YELLOW));
    }

    private void finish(String message) {
        endJob();
        // A finished job matters even if nobody's around to see it end — queue it for
        // next login rather than letting it vanish, same as ClearJobTask.finish() does.
        // Real now that this job is tracked by JobManager (added 2026-08-21) and has a
        // shared offline-message queue to use — previously this always vanished for an
        // offline player, a gap this class's own doc used to flag as accepted for Phase B.
        Player player = Bukkit.getPlayer(playerId);
        if (player != null) {
            player.sendMessage(Component.text(message, NamedTextColor.GREEN));
        } else {
            jobManager.queueOfflineNotification(playerId, message);
        }
        logger.info(message + " [quarryman, cleared=" + clearedCells + ", stored=" + deposited + "]");
    }

    private void abortJob(String message) {
        endJob();
        messagePlayer(Component.text(message, NamedTextColor.RED));
        logger.warning(message);
    }

    private void endJob() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        npc.getNavigator().cancelNavigation();
        releaseChunkTickets();
        clearBulkheadPlugs();
        bulkheadWaveAnchors.clear();
        bulkheadWaveIndex = 0;
        label.remove();
        if (equipment != null) {
            equipment.setItemInMainHand(null);
        }
        jobManager.onJobEnded(npc.getId());
    }

    /** Snapshots current progress for persistence — see {@link #resume} for the inverse. */
    @Override
    public JobState toJobState() {
        JobState state = new JobState();
        state.jobType = JobType.QUARRY;
        state.npcId = npc.getId();
        state.playerId = playerId;
        state.worldName = world.getName();
        state.minX = minX;
        state.maxX = maxX;
        state.minZ = minZ;
        state.maxZ = maxZ;
        state.topY = topY;
        state.requestedDepth = requestedDepth;
        state.stepsAlongX = stepsAlongX;
        state.stepDirection = stepDirection;
        state.storeInChest = storage != null;
        state.processedCells = processedCells;
        state.clearedCells = clearedCells;
        state.deposited = deposited;
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

    private void messagePlayer(Component message) {
        Player player = Bukkit.getPlayer(playerId);
        if (player != null) {
            player.sendMessage(message);
        }
    }
}
