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
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
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
 * <p>Deliberately standalone — NOT registered with {@link JobManager}. Concrete,
 * deliberate consequences: no shared concurrency ceiling with Clear jobs, no
 * pause/resume, and no survival across a server restart (a job in progress when the
 * server restarts is simply abandoned — the Helper stands idle wherever it was). See
 * the Quarryman Phase B plan for why: {@code JobManager}/{@code JobState} are
 * concretely typed to {@link ClearJobTask} today, and generalizing that is real work
 * explicitly scoped to a later phase, not this one. Cancel-by-chat still works —
 * {@link com.houseofel.builder.npc.HelperCommandListener} calls {@link #cancelJob()}
 * directly via {@link JobExecutionService}, independent of {@code JobManager}.
 *
 * <p>Fall-safety here is structural, not listener-based: {@link JobFallProtectionListener}
 * only covers NPCs {@code JobManager} is tracking, which this deliberately isn't. Instead,
 * every single step {@link #buildDigOrder} produces drops the Helper at most 1 block by
 * construction — a fact that holds regardless of {@link #MAX_TOLERATED_FALL}'s own value,
 * since it's the geometry, not the navigator setting, doing the real work there.
 * {@code MAX_TOLERATED_FALL} matches {@code ClearJobTask}'s own proven value (3), not a
 * tighter one: {@code fallDistance} governs Citizens' A* path search for the WHOLE job,
 * including the ordinary walk from wherever the Helper currently stands to the marked
 * site — confirmed live 2026-08-20 that setting it to 1 made Citizens silently refuse to
 * path across perfectly normal overworld terrain elevation on the way there, reading as
 * "stuck" with nothing actually blocking anything. 3 is still more than enough headroom
 * for the dig geometry's own 1-block steps; it just stops being tighter than the terrain
 * the Helper has to cross to ever reach them.
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
 * true (see {@link #buildDigOrder}'s own doc for why those columns exist).
 */
public final class QuarrymanJobTask {

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
    private static final int CARRY_CAPACITY = 4 * 64;

    private static final double LABEL_HEIGHT_OFFSET = 2.3;
    private static final int GLOW_TICK_PERIOD = 20;
    private static final int TINT_TICK_PERIOD = 5;
    private static final int WALK_CUE_PERIOD = 10;
    private static final double MOB_ALERT_RADIUS = 10.0;
    private static final int MOB_ALERT_CHECK_PERIOD = GLOW_TICK_PERIOD;

    private enum Phase { SEEKING, WALKING, DIGGING, HAULING }

    private final Plugin plugin;
    private final Logger logger;
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
    private final Runnable onEnded;

    private final Deque<Block> remainingCells;
    private final long totalCells;
    private long processedCells;
    private long clearedCells;
    private long deposited;

    private final Map<Material, Integer> carried = new HashMap<>();
    private int carriedTotal;
    private Location depositPoint;

    private Phase phase = Phase.SEEKING;
    private int hesitationTicks;
    private Block pendingCell;
    private int walkTicks;
    private boolean retriedWalk;
    private int noProgressTicks;
    private double closestApproachSquared;
    private int digTicks;

    private BukkitTask task;
    private int outlineTicks;
    private final Set<UUID> alertedMobs = new HashSet<>();

    QuarrymanJobTask(Plugin plugin, HelperLevelService levelService, UUID playerId, NPC npc, Entity npcEntity,
                      EntityEquipment equipment, TextDisplay label, World world, Material initialTool,
                      Deque<Block> remainingCells, JobStorage storage, RegionOutline outline, Runnable onEnded) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.levelService = levelService;
        this.playerId = playerId;
        this.npc = npc;
        this.npcEntity = npcEntity;
        this.equipment = equipment;
        this.label = label;
        this.world = world;
        this.currentTool = initialTool;
        this.remainingCells = remainingCells;
        this.totalCells = remainingCells.size();
        this.storage = storage;
        this.outline = outline;
        this.onEnded = onEnded;
    }

    UUID playerId() {
        return playerId;
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

    void start() {
        npc.getNavigator().getDefaultParameters()
                .speedModifier(WALK_SPEED_MODIFIER)
                .range(PATHFINDING_RANGE)
                .fallDistance(MAX_TOLERATED_FALL)
                .stuckAction(SafeStuckAction.INSTANCE);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 0L, 1L);
    }

    /** Stops the job for good, mid-run — not a natural completion, so no "finished" summary. Safe to call from the cancel-by-chat path. */
    void cancelJob() {
        endJob();
        logger.info(BuilderNpcService.baseNameOf(npc) + "'s Quarryman job was cancelled ["
                + clearedCells + " cleared, " + deposited + " stored]");
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
            }
        }

        if ((phase == Phase.WALKING || phase == Phase.HAULING) && outlineTicks % WALK_CUE_PERIOD == 0) {
            world.spawnParticle(Particle.CLOUD, npcEntity.getLocation().add(0, 0.1, 0),
                    2, 0.15, 0.05, 0.15, 0.01);
        }
        if (outlineTicks % GLOW_TICK_PERIOD == 0) {
            outline.drawGlowNearby();
        }
        if (outlineTicks % TINT_TICK_PERIOD == 0) {
            outline.drawTintNearby(RegionOutline.WORKING_TINT, RegionOutline.tintSize(outlineTicks));
        }
        if (outlineTicks % MOB_ALERT_CHECK_PERIOD == 0) {
            checkForHostileMobs();
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
     * Polls the next cell, skipping anything not actually diggable (already air, or —
     * since {@link #buildDigOrder} always includes every ramp column's cells in both the
     * ramp segment and the following bench's room segment — a cell the ramp already dug).
     * No fumble/error-rate roll here, unlike {@link ClearJobTask}: a fumbled cell there
     * gets revisited by the next sweep pass, but this job has no multi-pass concept, so a
     * fumble here would just leave a permanent stray block in what's supposed to be a
     * clean bench — not worth it for a small demo shape.
     */
    private void seekNextCell() {
        Block candidate;
        do {
            candidate = remainingCells.poll();
            if (candidate == null) {
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
        closestApproachSquared = Double.MAX_VALUE;
        npc.getNavigator().setTarget(candidate.getLocation().add(0.5, 1.0, 0.5));
        phase = Phase.WALKING;
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
            npc.getNavigator().cancelNavigation();
            npc.teleport(safeLandingAbove(pendingCell), PlayerTeleportEvent.TeleportCause.PLUGIN);
            beginDigging();
            return;
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
            npc.getNavigator().setTarget(pendingCell.getLocation().add(0.5, 1.0, 0.5));
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
        if (noProgressTicks > STUCK_RECOVERY_NO_PROGRESS_TICKS) {
            logger.warning(BuilderNpcService.baseNameOf(npc) + ": no progress toward "
                    + pendingCell.getX() + "," + pendingCell.getY() + "," + pendingCell.getZ()
                    + " for " + STUCK_RECOVERY_NO_PROGRESS_TICKS + " ticks — recovering from a stuck path.");
            npc.getNavigator().cancelNavigation();
            npc.teleport(safeLandingAbove(pendingCell), PlayerTeleportEvent.TeleportCause.PLUGIN);
            beginDigging();
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
     * Finds a genuinely clear spot to teleport onto directly above {@code cell}, for both
     * teleport-recovery cases in {@link #walkToPendingCell}. "One block above the cell" is
     * NOT always safe under {@link #buildDigOrder}'s sliding-window shape: only the Z rows
     * a shallower layer already happened to share get cleared above them by construction —
     * each layer's own newest row (the one row no shallower layer's window ever reached)
     * can have solid, never-dug rock stacked above it, as many blocks deep as the dig
     * currently is. Searches straight up for the first already-air block instead of
     * assuming one, same "confirm before you land on it" precedent {@link SafeStuckAction}
     * already sets for its own upward search — bounded by the world ceiling, so it always
     * terminates.
     */
    private Location safeLandingAbove(Block cell) {
        Block probe = cell;
        int maxSearch = world.getMaxHeight() - cell.getY();
        for (int i = 0; i < maxSearch; i++) {
            probe = probe.getRelative(BlockFace.UP);
            if (probe.getType() == Material.AIR) {
                return probe.getLocation().add(0.5, 0, 0.5);
            }
        }
        // Every block above is solid all the way to the world ceiling — shouldn't happen
        // in practice, but land on top of the cell itself rather than searching forever.
        return cell.getLocation().add(0.5, 1.0, 0.5);
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
        hesitationTicks = HelperTempo.hesitationTicksForDuty(levelService.dutyCycleOf(npc));

        if (carriedTotal >= CARRY_CAPACITY) {
            startHauling();
        } else {
            phase = Phase.SEEKING;
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

        if (remainingCells.isEmpty()) {
            finish(BuilderNpcService.baseNameOf(npc) + ": Both benches are done — " + clearedCells
                    + " block(s) dug." + storedSuffix());
            return;
        }
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
        // No offline-notification queueing, unlike ClearJobTask.finish() — this job isn't
        // tracked by JobManager, so it has no shared offline-message queue to use. A
        // player who logs off mid-job simply won't see this when they return; acceptable
        // for a small Phase B test job, worth revisiting if a later phase adds real
        // persistence.
        messagePlayer(Component.text(message, NamedTextColor.GREEN));
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
        label.remove();
        if (equipment != null) {
            equipment.setItemInMainHand(null);
        }
        onEnded.run();
    }

    private void messagePlayer(Component message) {
        Player player = Bukkit.getPlayer(playerId);
        if (player != null) {
            player.sendMessage(message);
        }
    }
}
