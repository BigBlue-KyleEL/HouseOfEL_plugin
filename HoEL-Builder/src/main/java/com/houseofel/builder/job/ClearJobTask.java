package com.houseofel.builder.job;

import com.houseofel.builder.gui.Target;
import com.houseofel.builder.region.RegionOutline;
import net.citizensnpcs.api.ai.TeleportStuckAction;
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
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Silverfish;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
     * Ticks spent digging one block once in range. ~3 ticks is close to a real player
     * breaking dirt with an iron shovel.
     */
    private static final int DIG_TICKS = 3;
    /**
     * Movement speed while working. Vanilla sprinting is ~5.6 blocks/sec against
     * ~4.3 walking, so ~1.3x reads as a player jogging between blocks.
     */
    private static final float WALK_SPEED_MODIFIER = 1.3f;
    /** Pathfinding range in blocks. Citizens defaults to 25 and caps at 100. */
    private static final float PATHFINDING_RANGE = 100.0f;
    /** Grace period for the navigator to actually start moving before we call it a failed path. */
    private static final int PATH_GRACE_TICKS = 20;
    /**
     * Fallback working distance. If the NPC walked as close as it could but still can't
     * stand beside the block, it works from here rather than leaving a gap in the job.
     */
    private static final double MAX_WORK_DISTANCE = 12.0;
    /**
     * From this sweep onward the working distance is unbounded. By now the NPC has
     * genuinely walked at this block twice and failed, so it finishes the job from
     * wherever it stands rather than leaving stragglers behind.
     */
    private static final int UNLIMITED_REACH_FROM_PASS = 3;
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

    private enum Phase { SEEKING, WALKING, DIGGING, HAULING, GRIEF_WAIT }

    /** How long a grief ping waits for a response before defaulting to leaving the block alone. */
    private static final int GRIEF_WAIT_TICKS = 20 * 20;

    private final Plugin plugin;
    private final Logger logger;
    private final JobManager jobManager;
    private final PlayerPlacementTracker placementTracker;
    private final UUID playerId;
    private final NPC npc;
    private final Entity npcEntity;
    private final EntityEquipment equipment;
    private final TextDisplay label;
    private final World world;
    private final Target target;
    private final Material tool;
    private final boolean surfaceOnly;
    private final boolean storeInChest;
    private final boolean griefPlayerPlaced;
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
    private long processedCells;
    private long clearedCells;
    private long deposited;
    private long clearedThisPass;
    private long skippedThisPass;
    private long skippedNoPath;
    private long skippedTimeout;
    private int passNumber;
    private Block pendingBlock;
    private int walkTicks;
    private int noProgressTicks;
    private double closestApproachSquared;
    private int digTicks;
    private boolean usedStraightLine;
    private boolean jobComplete;
    private boolean paused;
    /** Guards the 50%/85% progress pings so each fires exactly once per job. */
    private boolean announced50;
    private boolean announced85;
    private BukkitTask task;
    private int griefWaitTicks;
    private boolean griefSkipRequested;
    /** Positions a grief ping already resolved — permanently excluded so a later pass never re-pings them. */
    private final Set<String> griefSkippedBlocks = new HashSet<>();
    /** Hostile mobs already alerted about, so the same one doesn't re-trigger every scan. */
    private final Set<UUID> alertedMobs = new HashSet<>();
    /** Chunks currently held open via a plugin ticket — see {@link #refreshChunkTickets()}. */
    private final Set<Long> ticketedChunks = new HashSet<>();
    /**
     * Set once at construction (fresh dispatch or restart-resume) and never touched by a
     * same-session pause/resume, so {@link #estimatedRemainingMillis()} measures against
     * the job's true start rather than resetting its baseline every time it's paused.
     */
    private final long startedAtMillis = System.currentTimeMillis();

    /** Fresh job, just dispatched. */
    ClearJobTask(Plugin plugin, JobManager jobManager, PlayerPlacementTracker placementTracker, Player player,
                 NPC npc, Entity npcEntity, EntityEquipment equipment, TextDisplay label, World world,
                 Target target, Material tool, int minX, int maxX, int minY, int maxY, int minZ, int maxZ,
                 int spanX, int spanZ, long totalCells, JobStorage storage, boolean storeInChest,
                 boolean surfaceOnly, boolean griefPlayerPlaced, RegionOutline outline) {
        this(plugin, jobManager, placementTracker, player.getUniqueId(), npc, npcEntity, equipment, label, world,
                target, tool, minX, maxX, minY, maxY, minZ, maxZ, spanX, spanZ, totalCells, storage,
                storeInChest, surfaceOnly, griefPlayerPlaced, outline,
                Phase.SEEKING, 0, 0, 0, 1, 0, 0, 0, 0, new HashMap<>());
    }

    private ClearJobTask(Plugin plugin, JobManager jobManager, PlayerPlacementTracker placementTracker,
                          UUID playerId, NPC npc, Entity npcEntity,
                          EntityEquipment equipment, TextDisplay label, World world, Target target, Material tool,
                          int minX, int maxX, int minY, int maxY, int minZ, int maxZ,
                          int spanX, int spanZ, long totalCells, JobStorage storage,
                          boolean storeInChest, boolean surfaceOnly, boolean griefPlayerPlaced,
                          RegionOutline outline,
                          Phase phase, long processedCells, long clearedCells, long deposited, int passNumber,
                          long clearedThisPass, long skippedThisPass, long skippedNoPath, long skippedTimeout,
                          Map<Material, Integer> carried) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.jobManager = jobManager;
        this.placementTracker = placementTracker;
        this.playerId = playerId;
        this.npc = npc;
        this.npcEntity = npcEntity;
        this.equipment = equipment;
        this.label = label;
        this.world = world;
        this.target = target;
        this.tool = tool;
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
        this.griefPlayerPlaced = griefPlayerPlaced;
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
    static ClearJobTask resume(Plugin plugin, JobManager jobManager, PlayerPlacementTracker placementTracker,
                                JobState state, NPC npc) {
        World world = Bukkit.getWorld(state.worldName);
        Entity npcEntity = npc.getEntity();
        if (world == null || npcEntity == null) {
            return null;
        }
        Target target;
        Material tool;
        try {
            target = Target.valueOf(state.target);
            tool = Material.valueOf(state.tool);
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Couldn't resume job for NPC #" + state.npcId
                    + " — unrecognised target/tool: " + e.getMessage());
            return null;
        }

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

        EntityEquipment equipment = equipTool(npcEntity, tool);
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

        return new ClearJobTask(plugin, jobManager, placementTracker, state.playerId, npc, npcEntity, equipment,
                label, world, target, tool, state.minX, state.maxX, state.minY, state.maxY, state.minZ, state.maxZ,
                spanX, spanZ, totalCells, storage, state.storeInChest, state.surfaceOnly, state.griefPlayerPlaced,
                outline, Phase.SEEKING, state.processedCells, state.clearedCells, state.deposited, state.passNumber,
                state.clearedThisPass, state.skippedThisPass, state.skippedNoPath, state.skippedTimeout, carried);
    }

    static TextDisplay spawnLabel(Location npcLocation) {
        Location labelLocation = npcLocation.clone().add(0, LABEL_HEIGHT_OFFSET, 0);
        TextDisplay label = labelLocation.getWorld().spawn(labelLocation, TextDisplay.class);
        label.setBillboard(Display.Billboard.CENTER);
        label.text(Component.text("0%", NamedTextColor.YELLOW));
        return label;
    }

    /** Returns the entity's equipment if it can hold one, so the job can clear it when done. */
    static EntityEquipment equipTool(Entity npcEntity, Material tool) {
        if (!(npcEntity instanceof LivingEntity livingEntity)) {
            return null;
        }
        EntityEquipment equipment = livingEntity.getEquipment();
        if (equipment != null) {
            equipment.setItemInMainHand(new ItemStack(tool));
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
        npc.getNavigator().getDefaultParameters()
                .speedModifier(WALK_SPEED_MODIFIER)
                .range(PATHFINDING_RANGE)
                .stuckAction(TeleportStuckAction.INSTANCE);
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
        label.remove();
        if (equipment != null) {
            equipment.setItemInMainHand(null);
        }
        jobManager.onJobEnded(npc.getId());
        logger.info(npc.getName() + "'s job was cancelled ["
                + clearedCells + " cleared, " + deposited + " stored]");
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
        state.tool = tool.name();
        state.surfaceOnly = surfaceOnly;
        state.storeInChest = storeInChest;
        state.griefPlayerPlaced = griefPlayerPlaced;
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
            finish(npc.getName() + " disappeared mid-job — clearing stopped early.");
            return;
        }

        switch (phase) {
            case SEEKING -> seekNextBlock();
            case WALKING -> walkToPendingBlock();
            case DIGGING -> digPendingBlock();
            case HAULING -> haulToChest();
            case GRIEF_WAIT -> waitForGriefResponse();
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
                    npc.getName() + ": Making good progress — about halfway through the "
                            + target.label() + " now.",
                    NamedTextColor.YELLOW));
        }
        if (!announced85 && percent >= 85) {
            announced85 = true;
            messagePlayer(Component.text(
                    npc.getName() + ": Nearly there — just a bit more " + target.label() + " to go.",
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
                        npc.getName() + ": Careful — there's a " + prettyName(monster.getType()) + " nearby!",
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
                npc.getName() + ": Whoa — that wasn't just stone! Silverfish!", NamedTextColor.RED));
    }

    private static String prettyName(EntityType type) {
        String name = type.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    /** Scans forward for the next clearable block and starts walking to it. */
    private void seekNextBlock() {
        long scanLimit = Math.min(processedCells + MAX_CELLS_SCANNED_PER_TICK, totalCells);
        while (processedCells < scanLimit) {
            Block candidate = blockAt(processedCells);
            processedCells++;
            if (isClearable(candidate)) {
                pendingBlock = candidate;
                walkTicks = 0;
                noProgressTicks = 0;
                closestApproachSquared = Double.MAX_VALUE;
                usedStraightLine = false;
                npc.getNavigator().setTarget(candidate.getLocation().add(0.5, 1.0, 0.5));
                phase = Phase.WALKING;
                return;
            }
        }

        if (processedCells < totalCells) {
            return;
        }

        // A block skipped this pass (unreachable, or buried under one that was) can
        // become clearable once its neighbours go, so sweep again until a pass both
        // clears nothing and skips nothing. Counting skips matters: a pass that only
        // skipped would otherwise end the job before the unlimited-reach sweep.
        if ((clearedThisPass > 0 || skippedThisPass > 0) && passNumber < MAX_PASSES) {
            passNumber++;
            clearedThisPass = 0;
            skippedThisPass = 0;
            processedCells = 0;
            return;
        }

        // Take the last part-load back before knocking off.
        if (storage != null && carriedTotal > 0) {
            startHauling();
            jobComplete = true;
            return;
        }

        finish(npc.getName() + ": All done — cleared " + clearedCells + " " + target.label()
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

        // The pathfinder gave up (or never found a route). Before writing the block
        // off, try walking straight at it — most "unreachable" blocks on an open
        // site are perfectly walkable, the A* search just wouldn't commit to them.
        if (walkTicks > PATH_GRACE_TICKS && !npc.getNavigator().isNavigating()) {
            if (!usedStraightLine) {
                usedStraightLine = true;
                walkTicks = 0;
                noProgressTicks = 0;
                closestApproachSquared = Double.MAX_VALUE;
                npc.getNavigator().setStraightLineTarget(
                        pendingBlock.getLocation().add(0.5, 1.0, 0.5));
                return;
            }
            skippedNoPath++;
            skippedThisPass++;
            abandonPendingBlock();
            return;
        }

        if (noProgressTicks > NO_PROGRESS_TICKS || walkTicks > WALK_TIMEOUT_TICKS) {
            // It stopped making headway — usually a pit it dug or terrain in the way.
            // Rather than leave a hole in the job, let it work from where it stands,
            // and drop the distance cap entirely once it has tried honestly for a
            // couple of full sweeps.
            boolean withinWorkingRange = passNumber >= UNLIMITED_REACH_FROM_PASS
                    || distanceSquared <= MAX_WORK_DISTANCE * MAX_WORK_DISTANCE;
            if (withinWorkingRange) {
                npc.getNavigator().cancelNavigation();
                beginDigging();
                return;
            }
            skippedTimeout++;
            skippedThisPass++;
            abandonPendingBlock();
        }
    }

    /**
     * Starts digging pendingBlock — unless it looks like a player's build and the
     * grief checkbox is off, in which case it pings the player and waits instead.
     */
    private void beginDigging() {
        if (!griefPlayerPlaced && placementTracker.isPlayerPlaced(pendingBlock)) {
            griefWaitTicks = 0;
            griefSkipRequested = false;
            phase = Phase.GRIEF_WAIT;
            Location loc = pendingBlock.getLocation();
            messagePlayer(Component.text(npc.getName() + ": Hold on — (" + loc.getBlockX() + ", "
                    + loc.getBlockY() + ", " + loc.getBlockZ() + ") looks like it might be yours. Say "
                    + "\"leave it\" within 20 seconds if you want me to skip it.", NamedTextColor.GOLD));
            return;
        }
        digTicks = 0;
        phase = Phase.DIGGING;
    }

    /**
     * Waits for a "leave it" reply before touching a suspected player-placed block.
     * Silence isn't treated as permission — the block is skipped either way, since
     * getting this wrong destroys something real while getting it "wrong" the other
     * way just costs a few extra seconds of walking.
     */
    private void waitForGriefResponse() {
        if (griefSkipRequested) {
            // No message here — the chat listener that set this flag already sent its
            // own flavor response, same as every other job-control trigger.
            griefSkippedBlocks.add(blockKey(pendingBlock));
            abandonPendingBlock();
            return;
        }
        griefWaitTicks++;
        if (griefWaitTicks >= GRIEF_WAIT_TICKS) {
            messagePlayer(Component.text(
                    npc.getName() + ": No word back — I'll leave that one alone.", NamedTextColor.YELLOW));
            griefSkippedBlocks.add(blockKey(pendingBlock));
            abandonPendingBlock();
        }
    }

    /** Called by {@link JobManager} when the job's owner says "leave it" during a grief-wait. */
    void requestGriefSkip() {
        griefSkipRequested = true;
    }

    boolean isAwaitingGriefResponse() {
        return phase == Phase.GRIEF_WAIT;
    }

    private static String blockKey(Block block) {
        return block.getX() + "," + block.getY() + "," + block.getZ();
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

        if (digTicks < DIG_TICKS) {
            return;
        }

        world.playSound(pendingBlock.getLocation(),
                pendingBlock.getBlockData().getSoundGroup().getBreakSound(), 1.0f, 1.0f);
        world.spawnParticle(Particle.BLOCK, pendingBlock.getLocation().add(0.5, 0.5, 0.5),
                16, 0.3, 0.3, 0.3, pendingBlock.getBlockData());

        boolean wasInfested = Target.isInfested(pendingBlock.getType());
        collectDrops(pendingBlock);
        pendingBlock.setType(Material.AIR);
        // Bypasses BlockBreakEvent the same way the silverfish spawn does, so the
        // placement tracker needs telling directly that this position is clear now.
        placementTracker.forget(pendingBlock);
        if (wasInfested) {
            spawnSurpriseSilverfish(pendingBlock.getLocation());
        }
        clearedCells++;
        clearedThisPass++;
        abandonPendingBlock();

        if (storage != null && carriedTotal >= CARRY_CAPACITY) {
            startHauling();
        }
    }

    /** Picks up what the block would really drop for this tool, so grass yields dirt. */
    private void collectDrops(Block block) {
        if (storage == null) {
            return;
        }
        for (ItemStack drop : block.getDrops(new ItemStack(tool))) {
            int amount = drop.getAmount() * TEST_DROP_MULTIPLIER;
            carried.merge(drop.getType(), amount, Integer::sum);
            carriedTotal += amount;
        }
    }

    private void startHauling() {
        depositPoint = storage.depositPoint();
        if (depositPoint == null) {
            // Nowhere to build a chest — carry on working rather than stalling the job.
            messagePlayer(Component.text(
                    "No room to place a storage chest nearby — " + npc.getName()
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
                    "Storage is full and there's no room to expand — " + npc.getName()
                            + " is dropping the rest.", NamedTextColor.RED));
            carried.clear();
            carriedTotal = 0;
        }

        usedStraightLine = false;

        if (jobComplete) {
            finish(npc.getName() + " finished clearing — " + clearedCells + " " + target.label()
                    + " block(s) cleared." + storedSuffix());
            return;
        }
        phase = Phase.SEEKING;
    }

    private String storedSuffix() {
        return storage == null ? "" : " " + deposited + " item(s) stored.";
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
        // A grief-ping that resolved to "leave it" is permanent for this job — otherwise
        // a later pass would just walk back over and re-ping about the same block.
        if (griefSkippedBlocks.contains(blockKey(block))) {
            return false;
        }
        // Surface mode leaves buried material alone. With it off the job hollows the
        // region out instead — still safe to path, since clearing a full Y layer before
        // descending means the NPC always has open space above the layer it's working.
        return !surfaceOnly
                || world.getBlockAt(block.getX(), block.getY() + 1, block.getZ()).isPassable();
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
                + ", timeout-skips=" + skippedTimeout + "]");
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
