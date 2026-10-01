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
import com.houseofel.builder.toil.TicketKind;
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
import org.bukkit.block.DoubleChest;
import org.bukkit.block.Lidded;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Levelled;
import org.bukkit.block.data.type.Ladder;
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
 * Groundworker L16 Quarryman fork — Shaft Miner.
 * Digs a vertical shaft straight down, layer by layer, through the player-marked
 * footprint. Same phase machine and nearly identical mechanics to
 * {@link QuarrymanJobTask}: seek/walk/dig/haul/bulkhead/deposit/return. The key
 * difference is the dig geometry — no staircase offset, no stepping axis. Each
 * layer is the same footprint at the next Y level down. After clearing each layer,
 * the shaft is dressed with ladders (NW corner column), reinforced walls
 * (loose/non-solid perimeter blocks replaced with cobblestone or deepslate), and
 * wall torches every {@value #LANDING_INTERVAL} layers.
 */
public final class ShaftMinerJobTask implements JobTask {

    private static final double REACH_DISTANCE = 5.5;
    private static final float PATHFINDING_RANGE = 100.0f;
    private static final int MAX_TOLERATED_FALL = 3;
    private static final int WALK_TIMEOUT_TICKS = 20 * 30;
    private static final int RETRY_GRACE_TICKS = 20;
    private static final int STUCK_RECOVERY_NO_PROGRESS_TICKS = 20 * 3;
    private static final double PROGRESS_EPSILON = 0.05;
    private static final int GHOST_DIG_NO_PROGRESS_TICKS = STUCK_RECOVERY_NO_PROGRESS_TICKS * 2;
    private static final int RECONCILE_SETTLE_TICKS = 20 * 3;
    private static final int RECONCILE_CHECK_PERIOD_TICKS = 20 * 5;
    private static final int CARRY_CAPACITY = 4 * 64;
    private static final int GROUNDWORKER_TICKET_BLOCKS = 512;
    private static final int CREDIT_UNITS_PER_BLOCK = 4;
    private static final int UNLOAD_HOLD_TICKS = 20 * 4;
    private static final int RETURN_WALK_TIMEOUT_TICKS = 20 * 15;

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
    private static final BlockFace[] HORIZONTAL_FACES = {
        BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST,
    };
    private static final int MAX_LANDING_SEARCH_HEIGHT = 4;
    private static final int LANDING_INTERVAL = 8;
    private static final Set<Material> LOOSE_WALL_MATERIALS = Set.of(
        Material.DIRT, Material.COARSE_DIRT, Material.ROOTED_DIRT, Material.GRASS_BLOCK,
        Material.MYCELIUM, Material.PODZOL, Material.GRAVEL, Material.SAND, Material.RED_SAND,
        Material.CLAY, Material.MUD, Material.SOUL_SAND, Material.SOUL_SOIL,
        Material.DIRT_PATH, Material.FARMLAND
    );

    private static final double LABEL_HEIGHT_OFFSET = 2.3;
    private static final int GLOW_TICK_PERIOD = 20;
    private static final int TINT_TICK_PERIOD = 5;
    private static final int WALK_CUE_PERIOD = 10;
    private static final double MOB_ALERT_RADIUS = 10.0;
    private static final int MOB_ALERT_CHECK_PERIOD = GLOW_TICK_PERIOD;

    private enum Phase { SEEKING, WALKING, DIGGING, HAULING, BULKHEAD, DEPOSITING, RETURNING }

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
    private Material currentTool;
    private final JobStorage storage;
    private final RegionOutline outline;

    private final int minX;
    private final int maxX;
    private final int minZ;
    private final int maxZ;
    private final int topY;
    private final int requestedDepth;

    private final Deque<Block> remainingCells;
    private final List<Block> allCells;
    private final long totalCells;
    private long processedCells;
    private long clearedCells;
    private long deposited;
    private boolean awaitingReconciliation;
    private boolean reconciled;
    private long reconciliationCursor;
    private final Deque<Block> reconciliationQueue = new ArrayDeque<>();

    private final Map<Material, Integer> carried;
    private int carriedTotal;
    private int currentLayerY;
    private Location depositPoint;

    private Phase phase = Phase.SEEKING;
    private int hesitationTicks;
    private Block pendingCell;
    private int walkTicks;
    private boolean retriedWalk;
    private int noProgressTicks;
    private boolean recoveryAttempted;
    private boolean pendingIsCleanup;
    private int unreachableCleanupCells;
    private double closestApproachSquared;
    private int digTicks;
    private Location lastSafeLocation;

    private final List<Block> openChests = new ArrayList<>();
    private String pendingEndMessage;
    private boolean pendingEndIsAbort;
    private int unloadTicks;

    private final List<Block> bulkheadPlugs = new ArrayList<>();
    private int bulkheadTicks;
    private final List<Block> bulkheadWaveAnchors = new ArrayList<>();
    private int bulkheadWaveIndex;
    private int bulkheadWaveStepTicks;

    private boolean paused;
    private int waterBreachStreak;
    private boolean waterThisLayer;
    private final Map<String, Block> waterWallColumns = new HashMap<>();
    private final long startedAtMillis = System.currentTimeMillis();

    private BukkitTask task;
    private int outlineTicks;
    private final Set<UUID> alertedMobs = new HashSet<>();
    private final Set<Long> ticketedChunks = new HashSet<>();

    ShaftMinerJobTask(Plugin plugin, JobManager jobManager, HelperLevelService levelService,
                      RedundancyTracker redundancyTracker, FreshLedger freshLedger, UUID playerId, NPC npc,
                      Entity npcEntity, EntityEquipment equipment, TextDisplay label, World world,
                      Material initialTool, int minX, int maxX, int minZ, int maxZ, int topY, int requestedDepth,
                      Deque<Block> remainingCells, JobStorage storage, RegionOutline outline) {
        this(plugin, jobManager, levelService, redundancyTracker, freshLedger, playerId, npc, npcEntity, equipment,
                label, world, initialTool, minX, maxX, minZ, maxZ, topY, requestedDepth,
                remainingCells, new ArrayList<>(remainingCells), storage, outline, 0, 0, 0, new HashMap<>(), topY);
    }

    private ShaftMinerJobTask(Plugin plugin, JobManager jobManager, HelperLevelService levelService,
                              RedundancyTracker redundancyTracker, FreshLedger freshLedger, UUID playerId,
                              NPC npc, Entity npcEntity, EntityEquipment equipment, TextDisplay label, World world,
                              Material initialTool, int minX, int maxX, int minZ, int maxZ, int topY,
                              int requestedDepth, Deque<Block> remainingCells, List<Block> allCells,
                              JobStorage storage, RegionOutline outline, long processedCells,
                              long clearedCells, long deposited, Map<Material, Integer> carried,
                              int currentLayerY) {
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
        this.currentTool = initialTool;
        this.minX = minX;
        this.maxX = maxX;
        this.minZ = minZ;
        this.maxZ = maxZ;
        this.topY = topY;
        this.requestedDepth = requestedDepth;
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
        this.currentLayerY = currentLayerY;
    }

    static ShaftMinerJobTask resume(Plugin plugin, JobManager jobManager, HelperLevelService levelService,
                                     RedundancyTracker redundancyTracker, FreshLedger freshLedger,
                                     JobState state, NPC npc) {
        World world = Bukkit.getWorld(state.worldName);
        Entity npcEntity = npc.getEntity();
        if (world == null || npcEntity == null) {
            return null;
        }

        for (String encoded : state.bulkheadPlugs) {
            Block plug = JobStorage.decodeBlock(world, encoded);
            Material plugType = plug.getType();
            if (plugType == BULKHEAD_PLUG_MATERIAL || plugType == Material.SPONGE || plugType == Material.WET_SPONGE) {
                plug.setType(Material.AIR);
                plugin.getLogger().info("[shaft-miner] Bulkhead: cleared stray plug at resume for NPC #"
                        + state.npcId + " at " + plug.getX() + "," + plug.getY() + "," + plug.getZ());
            }
        }

        Deque<Block> digOrder = buildDigOrder(world, state.minX, state.maxX, state.minZ, state.maxZ,
                state.topY, state.requestedDepth);
        List<Block> allCells = new ArrayList<>(digOrder);
        for (long i = 0; i < state.processedCells && !digOrder.isEmpty(); i++) {
            digOrder.poll();
        }

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

        int currentLayerY = digOrder.isEmpty() ? state.topY : digOrder.peek().getY();
        return new ShaftMinerJobTask(plugin, jobManager, levelService, redundancyTracker, freshLedger,
                state.playerId, npc, npcEntity, equipment, label, world, initialTool,
                state.minX, state.maxX, state.minZ, state.maxZ, state.topY, state.requestedDepth,
                digOrder, allCells, storage, outline, state.processedCells, state.clearedCells,
                state.deposited, carried, currentLayerY);
    }

    @Override public NPC npc() { return npc; }
    @Override public UUID playerId() { return playerId; }
    @Override public boolean isPaused() { return paused; }

    private int percentComplete() {
        return totalCells == 0 ? 100 : (int) (processedCells * 100 / totalCells);
    }

    @Override
    public long estimatedRemainingMillis() {
        int percent = percentComplete();
        if (percent <= 0) return Long.MAX_VALUE;
        long elapsed = System.currentTimeMillis() - startedAtMillis;
        long estimatedTotal = elapsed * 100 / percent;
        return Math.max(0, estimatedTotal - elapsed);
    }

    /**
     * Vertical dig order — each layer sweeps the full footprint in serpentine,
     * then drops to the next Y level. Deterministic from its six parameters, so
     * {@code processedCells} is all that's needed for resume fast-forward.
     */
    static Deque<Block> buildDigOrder(World world, int minX, int maxX, int minZ, int maxZ,
                                       int topY, int requestedDepth) {
        Deque<Block> cells = new ArrayDeque<>();
        boolean forward = true;
        for (int layer = 0; layer < requestedDepth; layer++) {
            int y = topY - layer;
            for (int x = minX; x <= maxX; x++) {
                if (forward) {
                    for (int z = minZ; z <= maxZ; z++) {
                        cells.add(world.getBlockAt(x, y, z));
                    }
                } else {
                    for (int z = maxZ; z >= minZ; z--) {
                        cells.add(world.getBlockAt(x, y, z));
                    }
                }
                forward = !forward;
            }
        }
        return cells;
    }

    @Override
    public void start() {
        npc.getNavigator().getDefaultParameters()
                .speedModifier(HelperTempo.walkSpeedFor(levelService.levelOf(npc)))
                .range(PATHFINDING_RANGE)
                .fallDistance(MAX_TOLERATED_FALL)
                .stuckAction(SafeStuckAction.INSTANCE);
        paused = false;
        refreshChunkTickets();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 0L, 1L);
    }

    @Override
    public void pause() {
        if (task != null) { task.cancel(); task = null; }
        npc.getNavigator().cancelNavigation();
        releaseChunkTickets();
        paused = true;
    }

    @Override
    public void resumeTicking() {
        if (paused) start();
    }

    @Override
    public void cancelJob() {
        endJob();
        logger.info(BuilderNpcService.baseNameOf(npc) + "'s Shaft Miner job was cancelled ["
                + clearedCells + " cleared, " + deposited + " stored]");
    }

    // ── Chunk tickets ──────────────────────────────────────────────────────

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
            if (desired.contains(key)) return false;
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

    private static long chunkKey(Location loc) { return chunkKey(loc.getBlockX() >> 4, loc.getBlockZ() >> 4); }
    private static long chunkKey(int cx, int cz) { return (((long) cx) << 32) ^ (cz & 0xffffffffL); }
    private static int chunkX(long key) { return (int) (key >> 32); }
    private static int chunkZ(long key) { return (int) key; }

    // ── Main tick ──────────────────────────────────────────────────────────

    private void tick() {
        if (!npcEntity.isValid()) {
            finish(BuilderNpcService.baseNameOf(npc) + " disappeared mid-job — shaft mining stopped early.");
            return;
        }
        if (hesitationTicks > 0) {
            hesitationTicks--;
        } else {
            switch (phase) {
                case SEEKING    -> seekNextCell();
                case WALKING    -> walkToPendingCell();
                case DIGGING    -> digPendingCell();
                case HAULING    -> haulToChest();
                case BULKHEAD   -> tickBulkhead();
                case DEPOSITING -> tickUnload();
                case RETURNING  -> tickReturn();
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

    // ── Seek ───────────────────────────────────────────────────────────────

    private void seekNextCell() {
        Block reconciled0 = reconciliationQueue.poll();
        if (reconciled0 != null) {
            if (!Target.ANY_EARTH.matches(reconciled0.getType())) return;
            pendingCell = reconciled0;
            pendingIsCleanup = true;
            resetWalkState();
            npc.getNavigator().setTarget(navigationTargetFor(reconciled0));
            phase = Phase.WALKING;
            return;
        }

        Block candidate;
        do {
            candidate = remainingCells.poll();
            if (candidate == null) {
                if (!awaitingReconciliation && !reconciled) {
                    dressRemainingLayers();
                }
                if (!reconciled) {
                    if (!awaitingReconciliation) {
                        awaitingReconciliation = true;
                        hesitationTicks = RECONCILE_SETTLE_TICKS;
                        return;
                    }
                    reconciled = true;
                    if (queueReconciliationCells()) return;
                }
                if (carriedTotal > 0) {
                    startHauling();
                } else {
                    endWithReturn(BuilderNpcService.baseNameOf(npc) + ": Shaft's done — " + clearedCells
                            + " block(s) dug." + storedSuffix() + leftoverSuffix(), false);
                }
                return;
            }
            processedCells++;
        } while (!Target.ANY_EARTH.matches(candidate.getType()));

        if (!reconciled && candidate.getY() != currentLayerY) {
            for (int y = currentLayerY; y > candidate.getY(); y--) {
                dressCompletedLayer(y);
            }
            currentLayerY = candidate.getY();
            if (!waterThisLayer) {
                waterBreachStreak = 0;
                waterWallColumns.clear();
            }
            waterThisLayer = false;
        }

        pendingCell = candidate;
        pendingIsCleanup = reconciled;
        resetWalkState();
        npc.getNavigator().setTarget(navigationTargetFor(candidate));
        phase = Phase.WALKING;
    }

    private void resetWalkState() {
        walkTicks = 0;
        retriedWalk = false;
        noProgressTicks = 0;
        recoveryAttempted = false;
        closestApproachSquared = Double.MAX_VALUE;
    }

    private Location navigationTargetFor(Block cell) {
        Block above = cell.getRelative(BlockFace.UP);
        if (above.getType() == Material.AIR || above.isPassable()) {
            return cell.getLocation().add(0.5, 1.0, 0.5);
        }
        Location beside = safeStandingBeside(cell);
        return beside != null ? beside : lastSafeLocation;
    }

    private Location safeStandingBeside(Block cell) {
        for (BlockFace face : HORIZONTAL_FACES) {
            Block candidate = cell.getRelative(face);
            if (isStandable(candidate)) {
                return candidate.getLocation().add(0.5, 0, 0.5);
            }
        }
        return null;
    }

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

    // ── Shaft infrastructure ─────────────────────────────────────────────

    // Re-dresses every layer, not just the unfinished ones: a layer can miss its
    // ladder or torches if water was running through the spot when it was dressed.
    private void dressRemainingLayers() {
        int bottomY = topY - requestedDepth + 1;
        for (int y = topY; y >= bottomY; y--) {
            dressCompletedLayer(y);
        }
    }

    private void dressCompletedLayer(int y) {
        reinforceWalls(y);
        placeLadder(y);
        if (isLandingLayer(y)) {
            placeLighting(y);
        }
        // After the landing torches: where a landing torch already holds this cell, it stays.
        if (isLadderLightLayer(topY - y)) {
            placeWallTorch(minX + 1, y, minZ, BlockFace.SOUTH);
        }
    }

    static final int LADDER_LIGHT_INTERVAL = 4;

    static boolean isLadderLightLayer(int depth) {
        return depth > 0 && depth % LADDER_LIGHT_INTERVAL == 0;
    }

    private void reinforceWalls(int y) {
        Material reinforcement = y < 0 ? Material.DEEPSLATE : Material.COBBLESTONE;
        for (int x = minX; x <= maxX; x++) {
            reinforceIfNeeded(x, y, minZ - 1, reinforcement);
            reinforceIfNeeded(x, y, maxZ + 1, reinforcement);
        }
        for (int z = minZ; z <= maxZ; z++) {
            reinforceIfNeeded(minX - 1, y, z, reinforcement);
            reinforceIfNeeded(maxX + 1, y, z, reinforcement);
        }
    }

    private void reinforceIfNeeded(int x, int y, int z, Material replacement) {
        Block wall = world.getBlockAt(x, y, z);
        Material type = wall.getType();
        if (!type.isSolid() || LOOSE_WALL_MATERIALS.contains(type)) {
            wall.setType(replacement);
        }
    }

    // A ladder faces away from its supporting wall; the support here is the north wall.
    static final BlockFace LADDER_FACING = BlockFace.SOUTH;

    private void placeLadder(int y) {
        Block pos = world.getBlockAt(minX, y, minZ);
        boolean misfacedLadder = pos.getType() == Material.LADDER
                && pos.getBlockData() instanceof Directional existing
                && existing.getFacing() != LADDER_FACING;
        boolean flowingWater = pos.getType() == Material.WATER
                && pos.getBlockData() instanceof Levelled water && water.getLevel() != 0;
        boolean sourceWater = pos.getType() == Material.WATER && !flowingWater;
        if (pos.getType() != Material.AIR && !misfacedLadder && !flowingWater && !sourceWater) return;
        Block wall = pos.getRelative(LADDER_FACING.getOppositeFace());
        if (!wall.getType().isSolid()) return;
        Ladder data = (Ladder) Material.LADDER.createBlockData();
        data.setFacing(LADDER_FACING);
        data.setWaterlogged(sourceWater);
        pos.setBlockData(data);
    }

    private boolean isLandingLayer(int y) {
        int depth = topY - y;
        return depth > 0 && depth % LANDING_INTERVAL == 0;
    }

    record TorchSpot(int x, int z, BlockFace facing) { }

    /** Shafts at least 3 wide both ways light all four walls; narrower ones keep the south and east walls. */
    static java.util.List<TorchSpot> landingTorchSpots(int minX, int maxX, int minZ, int maxZ) {
        int midX = (minX + maxX) / 2;
        int midZ = (minZ + maxZ) / 2;
        java.util.List<TorchSpot> spots = new java.util.ArrayList<>();
        spots.add(new TorchSpot(midX, maxZ, BlockFace.NORTH));
        if (maxX > minX) spots.add(new TorchSpot(maxX, midZ, BlockFace.WEST));
        if (maxX - minX >= 2 && maxZ - minZ >= 2) {
            spots.add(new TorchSpot(midX, minZ, BlockFace.SOUTH));
            spots.add(new TorchSpot(minX, midZ, BlockFace.EAST));
        }
        return spots;
    }

    private void placeLighting(int y) {
        for (TorchSpot spot : landingTorchSpots(minX, maxX, minZ, maxZ)) {
            placeWallTorch(spot.x(), y, spot.z(), spot.facing());
        }
    }

    private void placeWallTorch(int x, int y, int z, BlockFace facing) {
        Block pos = world.getBlockAt(x, y, z);
        if (pos.getType() != Material.AIR) return;
        Block wall = pos.getRelative(facing.getOppositeFace());
        if (!wall.getType().isSolid()) return;
        Directional data = (Directional) Material.WALL_TORCH.createBlockData();
        data.setFacing(facing);
        pos.setBlockData(data);
    }

    // ── Walk ───────────────────────────────────────────────────────────────

    private void walkToPendingCell() {
        if (pendingCell.getType() == Material.AIR) {
            phase = Phase.SEEKING;
            return;
        }

        Location current = npcEntity.getLocation();
        Location cellCenter = pendingCell.getLocation().add(0.5, 0.5, 0.5);

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

        if (distanceSquared < closestApproachSquared - PROGRESS_EPSILON) {
            closestApproachSquared = distanceSquared;
            noProgressTicks = 0;
        } else {
            noProgressTicks++;
        }

        if (walkTicks == RETRY_GRACE_TICKS && !retriedWalk && !npc.getNavigator().isNavigating()) {
            retriedWalk = true;
            npc.getNavigator().setTarget(navigationTargetFor(pendingCell));
            return;
        }

        if (noProgressTicks > STUCK_RECOVERY_NO_PROGRESS_TICKS && !recoveryAttempted) {
            recoveryAttempted = true;
            logger.warning(BuilderNpcService.baseNameOf(npc) + ": no progress toward "
                    + pendingCell.getX() + "," + pendingCell.getY() + "," + pendingCell.getZ()
                    + " for " + STUCK_RECOVERY_NO_PROGRESS_TICKS + " ticks — recovering.");
            Location landing = safeStandingBeside(pendingCell);
            if (landing == null) landing = safeLandingAbove(pendingCell);
            if (landing != null) {
                npc.getNavigator().cancelNavigation();
                npc.teleport(landing, PlayerTeleportEvent.TeleportCause.PLUGIN);
                beginDigging();
                return;
            }
            npc.getNavigator().cancelNavigation();
            npc.teleport(lastSafeLocation, PlayerTeleportEvent.TeleportCause.PLUGIN);
            walkTicks = 0;
            noProgressTicks = 0;
            closestApproachSquared = Double.MAX_VALUE;
            npc.getNavigator().setTarget(navigationTargetFor(pendingCell));
            return;
        }

        if (recoveryAttempted && noProgressTicks > GHOST_DIG_NO_PROGRESS_TICKS) {
            logger.info(BuilderNpcService.baseNameOf(npc) + ": can't find a path to "
                    + pendingCell.getX() + "," + pendingCell.getY() + "," + pendingCell.getZ()
                    + " — digging it from here instead.");
            npc.getNavigator().cancelNavigation();
            beginDigging();
            return;
        }

        if (walkTicks > WALK_TIMEOUT_TICKS) {
            logger.warning(BuilderNpcService.baseNameOf(npc) + ": walk timeout at "
                    + current.getBlockX() + "," + current.getBlockY() + "," + current.getBlockZ()
                    + " toward " + pendingCell.getX() + "," + pendingCell.getY() + "," + pendingCell.getZ());
            if (pendingIsCleanup) {
                unreachableCleanupCells++;
                phase = Phase.SEEKING;
                return;
            }
            endWithReturn(BuilderNpcService.baseNameOf(npc) + ": Something's blocking my way to "
                    + pendingCell.getX() + "," + pendingCell.getY() + "," + pendingCell.getZ()
                    + " — stopping here rather than getting stuck.", true);
        }
    }

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

    // ── Dig ────────────────────────────────────────────────────────────────

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
                TaskType.SHAFT_MINER, new ItemStack(currentTool), pendingCell.getBlockData());
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

        if (digTicks < digTicksForPendingCell()) return;

        world.playSound(pendingCell.getLocation(),
                pendingCell.getBlockData().getSoundGroup().getBreakSound(), 1.0f, 1.0f);
        world.spawnParticle(Particle.BLOCK, pendingCell.getLocation().add(0.5, 0.5, 0.5),
                16, 0.3, 0.3, 0.3, pendingCell.getBlockData());

        collectDrop(pendingCell);
        awardGroundworkerProgress(creditUnitsFor(pendingCell));
        pendingCell.setType(Material.AIR);
        clearedCells++;
        lastSafeLocation = npcEntity.getLocation();

        List<Block> waterBreach = detectWaterBreach(pendingCell);
        List<Block> lavaBreach = detectLavaBreach(pendingCell);
        if (!waterBreach.isEmpty() && respondToPersistentWater(pendingCell.getY())) return;
        if (!waterBreach.isEmpty() || !lavaBreach.isEmpty()) {
            beginBulkhead(waterBreach, lavaBreach);
            return;
        }

        hesitationTicks = HelperTempo.hesitationTicksForDuty(levelService.dutyCycleOf(npc));
        if (carriedTotal >= CARRY_CAPACITY) {
            startHauling();
        } else {
            phase = Phase.SEEKING;
        }
    }

    // ── Persistent water: wall off the inflow (Kyle, 2026-10-01) ────────────

    static final int SPONGE_WAVES_BEFORE_WALLING = 3;
    enum WaterResponse { SPONGE_ONLY, WALL_ENTRIES, RAISE_WALLS, ASK_OWNER }

    /** Water breaches counted since the last layer that stayed dry. */
    static WaterResponse waterResponseFor(int breachStreak) {
        int beyond = breachStreak - SPONGE_WAVES_BEFORE_WALLING;
        if (beyond <= 0) return WaterResponse.SPONGE_ONLY;
        if (beyond == 1) return WaterResponse.WALL_ENTRIES;
        if (beyond == 2) return WaterResponse.RAISE_WALLS;
        return WaterResponse.ASK_OWNER;
    }

    /** Returns true when the job paused instead of running a sponge wave. */
    private boolean respondToPersistentWater(int dugY) {
        waterBreachStreak++;
        waterThisLayer = true;
        switch (waterResponseFor(waterBreachStreak)) {
            case SPONGE_ONLY -> { }
            case WALL_ENTRIES -> wallWaterEntries(dugY);
            case RAISE_WALLS -> {
                wallWaterEntries(dugY);
                raiseWaterWalls();
            }
            case ASK_OWNER -> {
                String name = BuilderNpcService.baseNameOf(npc);
                String message = name + ": There's too much water coming in for me to hold back. Can you help seal it? "
                        + "Say \"" + name + " continue\" when it's done, or \"" + name + " stop\" to end the job.";
                logger.info("[shaft-miner] " + name + " paused: water still entering after walling");
                waterBreachStreak = 0;
                jobManager.pause(npc.getId(), playerId);
                notifyOwner(message);
                return true;
            }
        }
        return false;
    }

    // Only the edge cells actually carrying water get a block; the rest of the rim is untouched.
    private void wallWaterEntries(int dugY) {
        int walled = 0;
        for (Block entry : perimeterCells(dugY, topY + 1)) {
            if (entry.getType() != Material.WATER) continue;
            entry.setType(entry.getY() < 0 ? Material.DEEPSLATE : Material.COBBLESTONE);
            waterWallColumns.merge(entry.getX() + "," + entry.getZ(), entry, (a, b) -> a.getY() >= b.getY() ? a : b);
            walled++;
        }
        logger.info("[shaft-miner] " + BuilderNpcService.baseNameOf(npc) + " walled " + walled + " water entry block(s)");
    }

    private void raiseWaterWalls() {
        int raised = 0;
        for (Map.Entry<String, Block> column : waterWallColumns.entrySet()) {
            Block above = column.getValue().getRelative(BlockFace.UP);
            if (above.getType().isSolid()) continue;
            above.setType(above.getY() < 0 ? Material.DEEPSLATE : Material.COBBLESTONE);
            column.setValue(above);
            raised++;
        }
        logger.info("[shaft-miner] " + BuilderNpcService.baseNameOf(npc) + " raised " + raised + " water wall(s) by one block");
    }

    /** Cells directly outside each shaft edge (corners excluded), from fromY up to toY. */
    private List<Block> perimeterCells(int fromY, int toY) {
        List<Block> cells = new ArrayList<>();
        for (int y = fromY; y <= toY; y++) {
            for (int x = minX; x <= maxX; x++) {
                cells.add(world.getBlockAt(x, y, minZ - 1));
                cells.add(world.getBlockAt(x, y, maxZ + 1));
            }
            for (int z = minZ; z <= maxZ; z++) {
                cells.add(world.getBlockAt(minX - 1, y, z));
                cells.add(world.getBlockAt(maxX + 1, y, z));
            }
        }
        return cells;
    }

    private void notifyOwner(String message) {
        Player player = Bukkit.getPlayer(playerId);
        if (player != null) {
            player.sendMessage(Component.text(message, NamedTextColor.YELLOW));
        } else {
            jobManager.queueOfflineNotification(playerId, message);
        }
    }

    // ── Bulkhead (fluid handling) ──────────────────────────────────────────

    private List<Block> detectWaterBreach(Block cell) {
        List<Block> flatRing = new ArrayList<>();
        List<Block> verticalAccents = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Deque<Block> frontier = new ArrayDeque<>();
        for (BlockFace face : ADJACENT_FACES) {
            Block neighbor = cell.getRelative(face);
            if (neighbor.getType() == Material.WATER) frontier.add(neighbor);
        }
        int explored = 0;
        while (!frontier.isEmpty() && explored < BULKHEAD_WATER_EXPLORE_CELLS
                && flatRing.size() + verticalAccents.size() < BULKHEAD_MAX_SPONGES) {
            Block current = frontier.poll();
            if (!visited.add(JobStorage.encodeBlock(current))) continue;
            explored++;
            if (isFarEnoughFromAnchors(current, flatRing) && isFarEnoughFromAnchors(current, verticalAccents)) {
                int dy = Math.abs(current.getY() - cell.getY());
                if (dy >= BULKHEAD_VERTICAL_DY_THRESHOLD) {
                    if (verticalAccents.size() < BULKHEAD_MAX_VERTICAL_SPONGES) verticalAccents.add(current);
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
        Comparator<Block> byDist = Comparator.<Block>comparingInt(a -> squaredDistance(a, cell)).reversed();
        flatRing.sort(byDist);
        verticalAccents.sort(byDist);
        List<Block> ordered = new ArrayList<>(flatRing);
        ordered.addAll(verticalAccents);
        return ordered;
    }

    private static boolean isFarEnoughFromAnchors(Block candidate, List<Block> anchors) {
        int minDist = BULKHEAD_SPONGE_SPACING_BLOCKS * BULKHEAD_SPONGE_SPACING_BLOCKS;
        for (Block anchor : anchors) {
            if (squaredDistance(candidate, anchor) < minDist) return false;
        }
        return true;
    }

    private static int squaredDistance(Block a, Block b) {
        int dx = a.getX() - b.getX(), dy = a.getY() - b.getY(), dz = a.getZ() - b.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    private List<Block> detectLavaBreach(Block cell) {
        List<Block> found = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Deque<Block> frontier = new ArrayDeque<>();
        for (BlockFace face : ADJACENT_FACES) {
            Block neighbor = cell.getRelative(face);
            if (isLavaSource(neighbor)) frontier.add(neighbor);
        }
        while (!frontier.isEmpty() && found.size() < BULKHEAD_MAX_PLUGS) {
            Block current = frontier.poll();
            if (!visited.add(JobStorage.encodeBlock(current))) continue;
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
        logger.info("[shaft-miner] Bulkhead: breach detected, " + waterBreach.size() + " sponge(s) queued and "
                + lavaBreach.size() + " lava source(s) plugged for " + BuilderNpcService.baseNameOf(npc));
    }

    private void tickBulkhead() {
        if (bulkheadWaveIndex < bulkheadWaveAnchors.size()) {
            bulkheadWaveStepTicks--;
            if (bulkheadWaveStepTicks > 0) return;
            Block anchor = bulkheadWaveAnchors.get(bulkheadWaveIndex);
            anchor.setType(Material.SPONGE);
            bulkheadPlugs.add(anchor);
            world.spawnParticle(Particle.SPLASH, anchor.getLocation().add(0.5, 0.5, 0.5),
                    12, 0.3, 0.3, 0.3, 0.05);
            world.playSound(anchor.getLocation(), Sound.BLOCK_SPONGE_PLACE, 1.0f, 1.0f);
            bulkheadWaveIndex++;
            bulkheadWaveStepTicks = BULKHEAD_WAVE_TICKS_PER_STEP;
            if (bulkheadWaveIndex == bulkheadWaveAnchors.size()) {
                bulkheadTicks = BULKHEAD_SETTLE_TICKS;
            }
            return;
        }
        bulkheadTicks--;
        if (bulkheadTicks > 0) return;
        clearBulkheadPlugs();
        bulkheadWaveAnchors.clear();
        bulkheadWaveIndex = 0;
        phase = Phase.SEEKING;
        hesitationTicks = HelperTempo.hesitationTicksForDuty(levelService.dutyCycleOf(npc));
        if (carriedTotal >= CARRY_CAPACITY) startHauling();
    }

    private void clearBulkheadPlugs() {
        for (Block plug : bulkheadPlugs) plug.setType(Material.AIR);
        bulkheadPlugs.clear();
    }

    // ── XP / anti-grind ────────────────────────────────────────────────────

    private void awardGroundworkerProgress(int creditUnits) {
        if (creditUnits <= 0 || levelService.specializationOf(npc) != Specialization.GROUNDWORKER) return;
        List<TicketAwardResult> results = levelService.awardProgress(
                npc, TicketKind.GROUNDWORKER_CLEAR_512, GROUNDWORKER_TICKET_BLOCKS * CREDIT_UNITS_PER_BLOCK, creditUnits);
        for (TicketAwardResult result : results) {
            for (String line : result.announcementLines()) {
                messagePlayer(Component.text(line, NamedTextColor.GREEN));
            }
        }
    }

    private int creditUnitsFor(Block block) {
        long now = System.currentTimeMillis();
        TaskFingerprint fingerprint = new TaskFingerprint(TaskType.SHAFT_MINER, block.getWorld().getName(),
                block.getX(), block.getY(), block.getZ(), ClearJobTask.canonicalMaterialClass(block));
        RedundancyTracker.CreditTier tier = redundancyTracker.check(npc.getUniqueId(), fingerprint, now);
        if (freshLedger.isFresh(block, now)) return 0;
        return switch (tier) {
            case ZERO -> 0;
            case REDUCED -> 1;
            case FULL -> CREDIT_UNITS_PER_BLOCK;
        };
    }

    private void collectDrop(Block block) {
        if (storage == null) return;
        for (ItemStack drop : block.getDrops(new ItemStack(currentTool))) {
            int amount = drop.getAmount();
            carried.merge(drop.getType(), amount, Integer::sum);
            carriedTotal += amount;
        }
    }

    // ── Haul / deposit ─────────────────────────────────────────────────────

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
        if (distanceSquared <= REACH_DISTANCE * REACH_DISTANCE) { unload(); return; }

        walkTicks++;
        if (distanceSquared < closestApproachSquared - PROGRESS_EPSILON) {
            closestApproachSquared = distanceSquared;
            noProgressTicks = 0;
        } else {
            noProgressTicks++;
        }
        if (noProgressTicks > STUCK_RECOVERY_NO_PROGRESS_TICKS) {
            teleportToChestAndUnload();
            return;
        }
        if (walkTicks > WALK_TIMEOUT_TICKS) {
            teleportToChestAndUnload();
        }
    }

    private void teleportToChestAndUnload() {
        Location standing = safeSpotBeside(depositPoint.getBlock());
        if (standing != null) {
            npc.getNavigator().cancelNavigation();
            npc.teleport(standing, PlayerTeleportEvent.TeleportCause.PLUGIN);
            lastSafeLocation = standing;
        }
        unload();
    }

    private Location safeSpotBeside(Block chest) {
        for (BlockFace face : HORIZONTAL_FACES) {
            Block candidate = chest.getRelative(face);
            if (isStandable(candidate)) return candidate.getLocation().add(0.5, 0, 0.5);
        }
        Block above = chest.getRelative(BlockFace.UP);
        return isStandable(above) ? above.getLocation().add(0.5, 0, 0.5) : null;
    }

    private boolean isStandable(Block feet) {
        return feet.getType() == Material.AIR
                && feet.getRelative(BlockFace.UP).getType() == Material.AIR
                && feet.getRelative(BlockFace.DOWN).getType().isSolid();
    }

    private void unload() {
        npc.getNavigator().cancelNavigation();
        npc.faceLocation(depositPoint);
        openChestLid(depositPoint.getBlock());
        unloadTicks = UNLOAD_HOLD_TICKS;
        phase = Phase.DEPOSITING;
    }

    private void tickUnload() {
        if (unloadTicks % 10 == 0) {
            world.spawnParticle(Particle.HAPPY_VILLAGER, depositPoint.clone().add(0, 0.6, 0),
                    3, 0.25, 0.25, 0.25, 0.01);
        }
        unloadTicks--;
        if (unloadTicks <= 0) finishUnload();
    }

    private void finishUnload() {
        Map<Material, Integer> leftover = storage.deposit(carried);
        int stored = carriedTotal - leftover.values().stream().mapToInt(Integer::intValue).sum();
        deposited += stored;

        carried.clear();
        carried.putAll(leftover);
        carriedTotal = leftover.values().stream().mapToInt(Integer::intValue).sum();

        world.playSound(depositPoint, Sound.ENTITY_ITEM_PICKUP, 0.8f, 0.8f);
        closeChestLid();

        if (!leftover.isEmpty()) {
            messagePlayer(Component.text(
                    "Storage is full and there's no room to expand — " + BuilderNpcService.baseNameOf(npc)
                            + " is dropping the rest.", NamedTextColor.RED));
            carried.clear();
            carriedTotal = 0;
        }
        phase = Phase.SEEKING;
    }

    private void openChestLid(Block chest) {
        closeChestLid();
        for (Block half : chestHalves(chest)) {
            if (half.getState() instanceof Lidded lidded) {
                lidded.open();
                openChests.add(half);
            }
        }
        world.playSound(depositPoint, Sound.BLOCK_CHEST_OPEN, 0.8f, 1.0f);
    }

    private List<Block> chestHalves(Block chest) {
        List<Block> halves = new ArrayList<>();
        if (chest.getState() instanceof org.bukkit.block.Chest state
                && state.getInventory().getHolder() instanceof DoubleChest doubleChest) {
            if (doubleChest.getLeftSide() instanceof org.bukkit.block.Chest left) halves.add(left.getBlock());
            if (doubleChest.getRightSide() instanceof org.bukkit.block.Chest right) halves.add(right.getBlock());
        }
        if (halves.isEmpty()) halves.add(chest);
        return halves;
    }

    private void closeChestLid() {
        if (openChests.isEmpty()) return;
        for (Block half : openChests) {
            if (half.getState() instanceof Lidded lidded) lidded.close();
        }
        world.playSound(openChests.get(0).getLocation(), Sound.BLOCK_CHEST_CLOSE, 0.8f, 1.0f);
        openChests.clear();
    }

    // ── Return to surface ──────────────────────────────────────────────────

    private void endWithReturn(String message, boolean isAbort) {
        pendingEndMessage = message;
        pendingEndIsAbort = isAbort;
        if (!npcEntity.isValid() || storage == null || depositPoint == null
                || npcEntity.getLocation().distanceSquared(depositPoint) <= REACH_DISTANCE * REACH_DISTANCE) {
            completePendingEnd();
            return;
        }
        walkTicks = 0;
        noProgressTicks = 0;
        closestApproachSquared = Double.MAX_VALUE;
        npc.getNavigator().setTarget(depositPoint);
        phase = Phase.RETURNING;
    }

    private void tickReturn() {
        double distanceSquared = npcEntity.getLocation().distanceSquared(depositPoint);
        if (distanceSquared <= REACH_DISTANCE * REACH_DISTANCE) { completePendingEnd(); return; }

        walkTicks++;
        if (distanceSquared < closestApproachSquared - PROGRESS_EPSILON) {
            closestApproachSquared = distanceSquared;
            noProgressTicks = 0;
        } else {
            noProgressTicks++;
        }

        boolean stalled = noProgressTicks > GHOST_DIG_NO_PROGRESS_TICKS;
        if (!stalled && walkTicks <= RETURN_WALK_TIMEOUT_TICKS) return;

        messagePlayer(Component.text(BuilderNpcService.baseNameOf(npc)
                + ": I can't seem to find my way back up — see you back at the storage chest!",
                NamedTextColor.YELLOW));
        npc.getNavigator().cancelNavigation();
        Location spot = safeSpotBeside(depositPoint.getBlock());
        if (spot != null) {
            npc.teleport(spot, PlayerTeleportEvent.TeleportCause.PLUGIN);
        }
        completePendingEnd();
    }

    private void completePendingEnd() {
        String message = pendingEndMessage;
        pendingEndMessage = null;
        if (pendingEndIsAbort) abortJob(message); else finish(message);
    }

    // ── Finish / teardown ──────────────────────────────────────────────────

    private void finish(String message) {
        endJob();
        Player player = Bukkit.getPlayer(playerId);
        if (player != null) {
            player.sendMessage(Component.text(message, NamedTextColor.GREEN));
        } else {
            jobManager.queueOfflineNotification(playerId, message);
        }
        logger.info(message + " [shaft-miner, cleared=" + clearedCells + ", stored=" + deposited + "]");
    }

    private void abortJob(String message) {
        endJob();
        messagePlayer(Component.text(message, NamedTextColor.RED));
        logger.warning(message);
    }

    private void endJob() {
        if (task != null) { task.cancel(); task = null; }
        npc.getNavigator().cancelNavigation();
        releaseChunkTickets();
        closeChestLid();
        clearBulkheadPlugs();
        bulkheadWaveAnchors.clear();
        bulkheadWaveIndex = 0;
        label.remove();
        if (equipment != null) equipment.setItemInMainHand(null);
        jobManager.onJobEnded(npc.getId());
    }

    // ── Persistence ────────────────────────────────────────────────────────

    @Override
    public JobState toJobState() {
        JobState state = new JobState();
        state.jobType = JobType.SHAFT_MINER;
        state.npcId = npc.getId();
        state.playerId = playerId;
        state.worldName = world.getName();
        state.minX = minX;
        state.maxX = maxX;
        state.minZ = minZ;
        state.maxZ = maxZ;
        state.topY = topY;
        state.requestedDepth = requestedDepth;
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
            for (Block block : storage.chests()) state.chests.add(JobStorage.encodeBlock(block));
            for (long key : storage.occupiedColumns()) state.occupiedColumns.add(JobStorage.encodeColumn(key));
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

    // ── Helpers ─────────────────────────────────────────────────────────────

    private String leftoverSuffix() {
        return unreachableCleanupCells == 0 ? ""
                : " (" + unreachableCleanupCells + " block(s) settled back in somewhere I couldn't climb to.)";
    }

    private String storedSuffix() {
        return storage == null ? "" : " " + deposited + " item(s) stored.";
    }

    private void swingArm() {
        if (npcEntity instanceof LivingEntity livingEntity) livingEntity.swingMainHand();
    }

    private void faceBlock(Block block) {
        npc.faceLocation(block.getLocation().add(0.5, 0.5, 0.5));
    }

    private static double square(double value) { return value * value; }

    private void updateLabel() {
        label.teleport(npcEntity.getLocation().add(0, LABEL_HEIGHT_OFFSET, 0));
        int percent = totalCells == 0 ? 100 : (int) Math.min(100, processedCells * 100 / totalCells);
        label.text(Component.text(percent + "%", NamedTextColor.YELLOW));
    }

    private void messagePlayer(Component message) {
        Player player = Bukkit.getPlayer(playerId);
        if (player != null) player.sendMessage(message);
    }
}
