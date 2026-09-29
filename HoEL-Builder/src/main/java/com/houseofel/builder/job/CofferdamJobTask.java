package com.houseofel.builder.job;

import com.houseofel.builder.gui.TaskType;
import com.houseofel.builder.antigrind.FreshLedger;
import com.houseofel.builder.antigrind.RedundancyTracker;
import com.houseofel.builder.npc.Specialization;
import com.houseofel.builder.toil.TicketKind;
import org.bukkit.event.block.SpongeAbsorbEvent;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Door;
import com.houseofel.builder.npc.BuilderNpcService;
import com.houseofel.builder.npc.HelperLevelService;
import com.houseofel.builder.region.RegionOutline;
import com.houseofel.builder.timing.HelperTempo;
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
import org.bukkit.block.data.Levelled;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/** Builds and drains a sealed box, then frees the Helper and creates a seven-world-day
 * region watch. Only unfinished cancellation strikes/refunds placed cobblestone.
 */
public final class CofferdamJobTask implements JobTask {

    enum CofferdamPhase { BUILDING, DRAINING, EXITING, STRIKING }
    private enum WalkState { SEEKING, WALKING, ACTING }

    static final Material DAM_MATERIAL = Material.COBBLESTONE;
    private static final float PATHFINDING_RANGE = 100.0f;
    private static final int MAX_TOLERATED_FALL = 3;
    private static final double REACH_DISTANCE = 5.5;
    private static final double LABEL_HEIGHT_OFFSET = 2.3;
    private static final int GLOW_TICK_PERIOD = 20;
    private static final int TINT_TICK_PERIOD = 5;
    private static final int WALK_CUE_PERIOD = 10;
    private static final double PROGRESS_EPSILON = 0.05;
    private static final int NO_PROGRESS_TICKS = 20 * 3;
    private static final int WALK_TIMEOUT_TICKS = 20 * 30;
    private static final int PATH_GRACE_TICKS = 20;
    private static final int PLACE_DELAY_TICKS = 3;
    private static final Material BULKHEAD_PLUG_MATERIAL = Material.COBBLESTONE;
    private static final int BULKHEAD_SETTLE_TICKS = 60;
    private static final int BULKHEAD_SPONGE_SPACING = 5;
    private static final int BULKHEAD_WAVE_TICKS_PER_STEP = 4;
    private static final int COFFERDAM_MAX_SPONGES_PER_WAVE = 36;
    private static final int COFFERDAM_MAX_PLUGS = 64;

    private final Plugin plugin;
    private final Logger logger;
    private final JobManager jobManager;
    private final HelperLevelService levelService;
    private final RedundancyTracker redundancyTracker;
    private final FreshLedger freshLedger;
    private final JobState entranceState = new JobState();
    private final java.util.Map<Block,Integer> spongeCredits = new java.util.HashMap<>();
    private Block activeSponge;
    private int exitTicks;
    private boolean ended;
    private final UUID playerId;
    private final NPC npc;
    private final Entity npcEntity;
    private final EntityEquipment equipment;
    private final TextDisplay label;
    private final World world;
    private final int minX, maxX, minY, maxY, minZ, maxZ;
    private final RegionOutline outline;
    private final JobStorage storage;

    private CofferdamPhase cofferdamPhase;
    private WalkState walkState;
    private List<int[]> buildOrder;
    private int buildCursor;
    private final List<int[]> damBlocks;
    private int targetX, targetY, targetZ;
    private boolean paused;
    private BukkitTask task;
    private int walkTicks;
    private int noProgressTicks;
    private double closestApproachSquared;
    private int placeDelay;
    private int outlineTicks;
    private int strikeCursor;
    private boolean announcedHalf;
    private final List<Block> bulkheadPlugs = new ArrayList<>();
    private final List<Block> bulkheadWaveAnchors = new ArrayList<>();
    private int bulkheadWaveIndex;
    private int bulkheadWaveStepTicks;
    private int bulkheadTicks;
    private int drainPassCount;
    private Listener waterIntrusionListener;

    private final Set<Long> ticketedChunks = new HashSet<>();
    private final long startedAtMillis = System.currentTimeMillis();

    CofferdamJobTask(Plugin plugin, JobManager jobManager, HelperLevelService levelService, RedundancyTracker redundancyTracker, FreshLedger freshLedger,
                     UUID playerId, NPC npc, Entity npcEntity, EntityEquipment equipment,
                     TextDisplay label, World world,
                     int minX, int maxX, int minY, int maxY, int minZ, int maxZ,
                     RegionOutline outline, JobStorage storage) {
        this(plugin, jobManager, levelService, redundancyTracker, freshLedger, playerId, npc, npcEntity, equipment, label,
                world, minX, maxX, minY, maxY, minZ, maxZ, outline, storage,
                CofferdamPhase.BUILDING, 0, new ArrayList<>(), 0);
    }

    private CofferdamJobTask(Plugin plugin, JobManager jobManager, HelperLevelService levelService, RedundancyTracker redundancyTracker, FreshLedger freshLedger,
                              UUID playerId, NPC npc, Entity npcEntity, EntityEquipment equipment,
                              TextDisplay label, World world,
                              int minX, int maxX, int minY, int maxY, int minZ, int maxZ,
                              RegionOutline outline, JobStorage storage,
                              CofferdamPhase cofferdamPhase, int buildCursor,
                              List<int[]> damBlocks, int strikeCursor) {
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
        label.setPersistent(false);
        this.world = world;
        this.minX = minX;
        this.maxX = maxX;
        this.minY = minY;
        this.maxY = maxY;
        this.minZ = minZ;
        this.maxZ = maxZ;
        this.outline = outline;
        this.storage = storage;
        entranceState.minX=minX; entranceState.maxX=maxX;
        entranceState.minY=minY; entranceState.maxY=maxY;
        entranceState.minZ=minZ; entranceState.maxZ=maxZ;
        entranceState.cofferdamId=UUID.randomUUID().toString();
        this.cofferdamPhase = cofferdamPhase;
        this.buildCursor = buildCursor;
        this.damBlocks = damBlocks;
        this.strikeCursor = strikeCursor;
        this.walkState = WalkState.SEEKING;
        this.buildOrder = computeBuildOrder(minX, maxX, minY, maxY, minZ, maxZ);
        this.announcedHalf = buildOrder.isEmpty() || buildCursor * 2 >= buildOrder.size();
    }

    static CofferdamJobTask resume(Plugin plugin, JobManager jobManager,
                                    HelperLevelService levelService, RedundancyTracker redundancyTracker, FreshLedger freshLedger, JobState state, NPC npc) {
        World world = Bukkit.getWorld(state.worldName);
        Entity npcEntity = npc.getEntity();
        if (world == null || npcEntity == null) {
            return null;
        }

        CofferdamPhase phase;
        try {
            phase = CofferdamPhase.valueOf(state.cofferdamPhase);
        } catch (IllegalArgumentException | NullPointerException e) {
            phase = CofferdamPhase.BUILDING;
        }

        EntityEquipment equipment = ClearJobTask.equipTool(npcEntity, Material.IRON_SHOVEL,
                BuilderNpcService.baseNameOf(npc), TaskType.COFFERDAM.toolNoun());
        TextDisplay label = ClearJobTask.spawnLabel(npcEntity.getLocation());
        RegionOutline outline = new RegionOutline(world, state.minX, state.minY, state.minZ,
                state.maxX, state.maxY, state.maxZ);

        JobStorage storage = new JobStorage(plugin, world,
                state.minX, state.maxX, state.minY, state.maxY, state.minZ, state.maxZ);
        restoreStorage(storage, state, world);

        List<int[]> damBlocks = new ArrayList<>();
        for (String encoded : state.damBlockPositions) {
            String[] parts = encoded.split(",");
            damBlocks.add(new int[]{
                    Integer.parseInt(parts[0]),
                    Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2])
            });
        }

        CofferdamJobTask task = new CofferdamJobTask(plugin, jobManager, levelService, redundancyTracker, freshLedger, state.playerId,
                npc, npcEntity, equipment, label, world,
                state.minX, state.maxX, state.minY, state.maxY, state.minZ, state.maxZ,
                outline, storage, phase, state.buildCursor, damBlocks, state.strikeCursor);
        if (state.cofferdamId!=null) task.entranceState.cofferdamId=state.cofferdamId;
        task.entranceState.cofferdamFacing=state.cofferdamFacing;
        task.entranceState.cofferdamDoor=state.cofferdamDoor;
        for (String encoded:state.bulkheadPlugs) {
            Block block=JobStorage.decodeBlock(world,encoded);
            if (block.getType()==Material.SPONGE || block.getType()==Material.WET_SPONGE
                    || block.getType()==BULKHEAD_PLUG_MATERIAL) task.bulkheadPlugs.add(block);
        }
        task.clearBulkheadPlugs();
        return task;
    }

    private static void restoreStorage(JobStorage storage, JobState state, World world) {
        List<Block> savedChests = new ArrayList<>();
        for (String s : state.chests) {
            savedChests.add(JobStorage.decodeBlock(world, s));
        }
        Set<Long> savedColumns = new HashSet<>();
        for (String s : state.occupiedColumns) {
            savedColumns.add(JobStorage.decodeColumn(s));
        }
        Block savedAnchor = state.anchor != null ? JobStorage.decodeBlock(world, state.anchor) : null;
        Block savedLastCubeAnchor = state.lastCubeAnchor != null
                ? JobStorage.decodeBlock(world, state.lastCubeAnchor) : null;
        Block[] savedRowFoot = new Block[2];
        if (state.rowFoot0 != null) savedRowFoot[0] = JobStorage.decodeBlock(world, state.rowFoot0);
        if (state.rowFoot1 != null) savedRowFoot[1] = JobStorage.decodeBlock(world, state.rowFoot1);
        storage.restore(savedChests, savedColumns, savedAnchor, savedLastCubeAnchor,
                savedRowFoot, state.cubeUnitIndex, state.rowSign, state.columnSign);
    }

    @Override
    public NPC npc() { return npc; }

    @Override
    public UUID playerId() { return playerId; }

    @Override
    public boolean isPaused() { return paused; }

    @Override
    public long estimatedRemainingMillis() {
        if (buildOrder.isEmpty() || buildCursor <= 0) {
            return Long.MAX_VALUE;
        }
        long elapsed = System.currentTimeMillis() - startedAtMillis;
        int total = buildOrder.size();
        long estimatedTotal = elapsed * total / buildCursor;
        return Math.max(0, estimatedTotal - elapsed);
    }

    @Override
    public void start() {
        npc.getNavigator().getDefaultParameters()
                .speedModifier(HelperTempo.walkSpeedFor(levelService.levelOf(npc)))
                .range(PATHFINDING_RANGE)
                .fallDistance(MAX_TOLERATED_FALL)
                .stuckAction(SafeStuckAction.INSTANCE);
        paused = false;
        if (npcEntity instanceof LivingEntity living) {
            living.addPotionEffect(new PotionEffect(
                    PotionEffectType.WATER_BREATHING, PotionEffect.INFINITE_DURATION,
                    0, false, false));
        }
        refreshChunkTickets();
        if (waterIntrusionListener==null && (cofferdamPhase==CofferdamPhase.DRAINING
                || cofferdamPhase==CofferdamPhase.EXITING)) registerWaterIntrusionListener();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 0L, 1L);
    }

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

    void configureEntrance(String facing, Material door) {
        entranceState.cofferdamFacing=facing;
        entranceState.cofferdamDoor=door.name();
    }

    @Override
    public void cancelJob() {
        if (cofferdamPhase==CofferdamPhase.STRIKING) return;
        flushSpongeCredit();
        clearBulkheadPlugs();
        bulkheadWaveAnchors.clear();
        unregisterWaterIntrusionListener();
        removeEntrance();
        cofferdamPhase=CofferdamPhase.STRIKING;
        strikeCursor=damBlocks.size()-1;
        walkState=WalkState.SEEKING;
        npc.getNavigator().cancelNavigation();
        if (paused) start();
    }

    private void removeEntrance() {
        if (cofferdamPhase==CofferdamPhase.BUILDING) return;
        if (!CofferdamGeometry.hasEntrance(entranceState)) return;
        int[] p=CofferdamGeometry.door(entranceState);
        BlockFace face=BlockFace.valueOf(entranceState.cofferdamFacing);
        for (int dy=0;dy<2;dy++) {
            Block b=world.getBlockAt(p[0],p[1]+dy,p[2]);
            if (b.getType()==Material.valueOf(entranceState.cofferdamDoor)) b.setType(Material.AIR,false);
        }
        Block lantern=world.getBlockAt(p[0]+face.getModX(),p[1]+2,p[2]+face.getModZ());
        Block wall=lantern.getRelative(BlockFace.UP);
        if (lantern.getType()==Material.LANTERN) lantern.setType(Material.AIR,false);
        if (wall.getType()==Material.COBBLESTONE_WALL) wall.setType(Material.AIR,false);
    }

    @Override
    public JobState toJobState() {
        flushSpongeCredit();
        JobState state = new JobState();
        state.jobType = JobType.COFFERDAM;
        state.npcId = npc.getId();
        state.playerId = playerId;
        state.worldName = world.getName();
        state.minX = minX;
        state.maxX = maxX;
        state.minY = minY;
        state.maxY = maxY;
        state.minZ = minZ;
        state.maxZ = maxZ;
        state.storeInChest = true;
        state.cofferdamPhase = cofferdamPhase.name();
        state.cofferdamId=entranceState.cofferdamId;
        state.cofferdamFacing=entranceState.cofferdamFacing;
        state.cofferdamDoor=entranceState.cofferdamDoor;
        state.cofferdamHelperUuid=npc.getUniqueId().toString();
        for (Block plug:bulkheadPlugs) state.bulkheadPlugs.add(JobStorage.encodeBlock(plug));
        state.buildCursor = buildCursor;
        state.strikeCursor = strikeCursor;
        state.damBlockPositions = new ArrayList<>();
        for (int[] pos : damBlocks) {
            state.damBlockPositions.add(pos[0] + "," + pos[1] + "," + pos[2]);
        }
        if (storage != null) {
            for (Block chest : storage.chests()) {
                state.chests.add(JobStorage.encodeBlock(chest));
            }
            for (long col : storage.occupiedColumns()) {
                state.occupiedColumns.add(JobStorage.encodeColumn(col));
            }
            state.anchor = storage.anchor() != null ? JobStorage.encodeBlock(storage.anchor()) : null;
            state.lastCubeAnchor = storage.lastCubeAnchor() != null
                    ? JobStorage.encodeBlock(storage.lastCubeAnchor()) : null;
            state.rowFoot0 = storage.rowFoot()[0] != null
                    ? JobStorage.encodeBlock(storage.rowFoot()[0]) : null;
            state.rowFoot1 = storage.rowFoot()[1] != null
                    ? JobStorage.encodeBlock(storage.rowFoot()[1]) : null;
            state.cubeUnitIndex = storage.cubeUnitIndex();
            state.rowSign = storage.rowSign();
            state.columnSign = storage.columnSign();
        }
        return state;
    }

    // ── Tick loop ───────────────────────────────────────────────────────────

    private void tick() {
        flushSpongeCredit();
        if (!npcEntity.isValid()) {
            finish(BuilderNpcService.baseNameOf(npc)
                    + " disappeared mid-job — cofferdam stopped early.");
            return;
        }

        if (placeDelay > 0) {
            placeDelay--;
        } else {
            switch (cofferdamPhase) {
                case BUILDING -> tickBuilding();
                case DRAINING -> tickDraining();
                case EXITING -> tickExiting();
                case STRIKING -> tickStriking();
            }
        }

        if (ended) return;
        if (walkState == WalkState.WALKING && outlineTicks % WALK_CUE_PERIOD == 0) {
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
        outlineTicks++;
        updateLabel();
    }

    // ── BUILDING phase ─────────────────────────────────────────────────────

    private void tickBuilding() {
        switch (walkState) {
            case SEEKING -> seekNextBuildPosition();
            case WALKING -> walkToTarget();
            case ACTING -> placeDamBlock();
        }
    }

    private void seekNextBuildPosition() {
        while (buildCursor < buildOrder.size()) {
            int[] pos = buildOrder.get(buildCursor);
            Block block = world.getBlockAt(pos[0], pos[1], pos[2]);
            if (!CofferdamGeometry.isDoor(entranceState,pos[0],pos[1],pos[2])
                    && !block.getType().isOccluding()) {
                targetX = pos[0];
                targetY = pos[1];
                targetZ = pos[2];
                startWalkingToTarget();
                return;
            }
            buildCursor++;
        }
        onBuildingComplete();
    }

    private void startWalkingToTarget() {
        Location target = new Location(world, targetX + 0.5, targetY + 1, targetZ + 0.5);
        npc.getNavigator().setTarget(target);
        walkState = WalkState.WALKING;
        walkTicks = 0;
        noProgressTicks = 0;
        closestApproachSquared = Double.MAX_VALUE;
    }

    private void walkToTarget() {
        Location targetLoc = new Location(world, targetX + 0.5, targetY + 1, targetZ + 0.5);
        double distSq = npcEntity.getLocation().distanceSquared(targetLoc);

        if (distSq <= REACH_DISTANCE * REACH_DISTANCE) {
            walkState = WalkState.ACTING;
            npc.getNavigator().cancelNavigation();
            return;
        }

        if (distSq < closestApproachSquared - PROGRESS_EPSILON) {
            closestApproachSquared = distSq;
            noProgressTicks = 0;
        } else {
            noProgressTicks++;
        }

        walkTicks++;

        if (noProgressTicks > NO_PROGRESS_TICKS || walkTicks > WALK_TIMEOUT_TICKS) {
            walkState = WalkState.ACTING;
            npc.getNavigator().cancelNavigation();
            return;
        }

        if (walkTicks > PATH_GRACE_TICKS && !npc.getNavigator().isNavigating()) {
            npc.getNavigator().setTarget(targetLoc);
        }
    }

    private void placeDamBlock() {
        Block block = world.getBlockAt(targetX, targetY, targetZ);
        if (block.getType().isOccluding() || CofferdamGeometry.isDoor(entranceState,targetX,targetY,targetZ)) {
            buildCursor++;
            walkState = WalkState.SEEKING;
            return;
        }

        int withdrawn = storage.withdraw(DAM_MATERIAL, 1);
        if (withdrawn == 0) {
            messagePlayer(Component.text(
                    BuilderNpcService.baseNameOf(npc)
                            + ": I'm out of cobblestone — put some in the chest and I'll keep going.",
                    NamedTextColor.YELLOW));
            placeDelay = 100;
            return;
        }

        block.setType(DAM_MATERIAL);
        damBlocks.add(new int[]{targetX, targetY, targetZ});
        world.playSound(block.getLocation(), Sound.BLOCK_STONE_PLACE, 0.7f, 1.0f);
        world.spawnParticle(Particle.BLOCK, block.getLocation().add(0.5, 0.5, 0.5),
                6, 0.25, 0.25, 0.25, block.getBlockData());
        escapeIfTrapped();

        placeDelay = PLACE_DELAY_TICKS;
        buildCursor++;
        walkState = WalkState.SEEKING;

        if (!announcedHalf && buildCursor * 2 >= buildOrder.size()) {
            announcedHalf = true;
            messagePlayer(Component.text(
                    BuilderNpcService.baseNameOf(npc)
                            + ": About halfway done with the dam walls.",
                    NamedTextColor.YELLOW));
        }
    }

    private void onBuildingComplete() {
        CofferdamGeometry.entrance(world,entranceState);
        if (waterIntrusionListener==null) registerWaterIntrusionListener();
        messagePlayer(Component.text(
                BuilderNpcService.baseNameOf(npc)
                        + ": Dam walls are sealed. Draining the interior now.",
                NamedTextColor.GREEN));
        cofferdamPhase = CofferdamPhase.DRAINING;
        walkState = WalkState.SEEKING;
    }

    // ── DRAINING phase — scaled Bulkhead sponge wave ─────────────────────

    private void tickDraining() {
        if (bulkheadWaveIndex < bulkheadWaveAnchors.size()) {
            bulkheadWaveStepTicks--;
            if (bulkheadWaveStepTicks > 0) return;

            Block anchor = bulkheadWaveAnchors.get(bulkheadWaveIndex);
            if (!anchor.getType().isSolid()) {
                int anchorCredit=CofferdamWork.wet(anchor)?creditUnitsFor(anchor):0;
                activeSponge=anchor;
                anchor.setType(Material.SPONGE);
                activeSponge=null;
                awardProgress(anchorCredit);
                bulkheadPlugs.add(anchor);
                world.spawnParticle(Particle.SPLASH, anchor.getLocation().add(0.5, 0.5, 0.5),
                        12, 0.3, 0.3, 0.3, 0.05);
                world.playSound(anchor.getLocation(), Sound.BLOCK_SPONGE_PLACE, 1.0f, 1.0f);
            }
            bulkheadWaveIndex++;
            bulkheadWaveStepTicks = BULKHEAD_WAVE_TICKS_PER_STEP;

            if (bulkheadWaveIndex == bulkheadWaveAnchors.size()) {
                bulkheadTicks = BULKHEAD_SETTLE_TICKS;
            }
            return;
        }

        if (bulkheadTicks > 0) {
            bulkheadTicks--;
            return;
        }

        if (!bulkheadPlugs.isEmpty()) {
            clearBulkheadPlugs();
            bulkheadWaveAnchors.clear();
            bulkheadWaveIndex = 0;
            logger.info(BuilderNpcService.baseNameOf(npc)
                    + "'s cofferdam drain pass " + drainPassCount
                    + " complete, checking for remaining water");
        }

        List<Block> waterAnchors = selectDrainAnchors();
        List<Block> lavaPlugs = detectInteriorLava();

        if (waterAnchors.isEmpty() && lavaPlugs.isEmpty()) {
            int swept = drainInterior();
            logger.info("[cofferdam-debug] drain sweep removed " + swept + " blocks, "
                    + "interior range x[" + (minX + 1) + ".." + (maxX - 1)
                    + "] y[" + (minY + 1) + ".." + (maxY - 1)
                    + "] z[" + (minZ + 1) + ".." + (maxZ - 1) + "]");
            flushSpongeCredit();
            cofferdamPhase = CofferdamPhase.EXITING;
            exitTicks=0;
            npc.getNavigator().cancelNavigation();
            return;
        }

        drainPassCount++;
        beginDrainWave(waterAnchors, lavaPlugs);
    }

    private void beginDrainWave(List<Block> waterAnchors, List<Block> lavaPlugs) {
        for (Block source : lavaPlugs) {
            source.setType(BULKHEAD_PLUG_MATERIAL);
            bulkheadPlugs.add(source);
        }
        bulkheadWaveAnchors.clear();
        bulkheadWaveAnchors.addAll(waterAnchors);
        bulkheadWaveIndex = 0;
        bulkheadWaveStepTicks = BULKHEAD_WAVE_TICKS_PER_STEP;
        bulkheadTicks = bulkheadWaveAnchors.isEmpty() ? BULKHEAD_SETTLE_TICKS : 0;
        logger.info(BuilderNpcService.baseNameOf(npc) + "'s cofferdam drain pass "
                + drainPassCount + ": " + waterAnchors.size() + " sponge(s) queued, "
                + lavaPlugs.size() + " lava source(s) plugged");
    }

    private List<Block> selectDrainAnchors() {
        List<Block> anchors = new ArrayList<>();
        int spacingSq = BULKHEAD_SPONGE_SPACING * BULKHEAD_SPONGE_SPACING;
        int waterCount = 0;
        int totalScanned = 0;
        for (int y = maxY - 1; y > minY; y--) {
            for (int x = minX + 1; x < maxX; x++) {
                for (int z = minZ + 1; z < maxZ; z++) {
                    totalScanned++;
                    Block block = world.getBlockAt(x, y, z);
                    Material type = block.getType();
                    if (type != Material.WATER) continue;
                    waterCount++;
                    boolean farEnough = true;
                    for (Block anchor : anchors) {
                        int dx = block.getX() - anchor.getX();
                        int dy = block.getY() - anchor.getY();
                        int dz = block.getZ() - anchor.getZ();
                        if (dx * dx + dy * dy + dz * dz < spacingSq) {
                            farEnough = false;
                            break;
                        }
                    }
                    if (farEnough) {
                        anchors.add(block);
                        if (anchors.size() >= COFFERDAM_MAX_SPONGES_PER_WAVE) {
                            logger.info("[cofferdam-debug] selectDrainAnchors scanned="
                                    + totalScanned + " waterFound=" + waterCount
                                    + " anchorsSelected=" + anchors.size());
                            return anchors;
                        }
                    }
                }
            }
        }
        logger.info("[cofferdam-debug] selectDrainAnchors scanned=" + totalScanned
                + " waterFound=" + waterCount + " anchorsSelected=" + anchors.size());
        return anchors;
    }

    private List<Block> detectInteriorLava() {
        List<Block> lava = new ArrayList<>();
        for (int y = maxY - 1; y > minY; y--) {
            for (int x = minX + 1; x < maxX; x++) {
                for (int z = minZ + 1; z < maxZ; z++) {
                    Block block = world.getBlockAt(x, y, z);
                    if (block.getType() == Material.LAVA
                            && block.getBlockData() instanceof Levelled levelled
                            && levelled.getLevel() == 0) {
                        lava.add(block);
                        if (lava.size() >= COFFERDAM_MAX_PLUGS) {
                            return lava;
                        }
                    }
                }
            }
        }
        return lava;
    }

    private void clearBulkheadPlugs() {
        for (Block plug : bulkheadPlugs) {
            if (plug.getType()==Material.SPONGE || plug.getType()==Material.WET_SPONGE
                    || plug.getType()==BULKHEAD_PLUG_MATERIAL) plug.setType(Material.AIR);
        }
        bulkheadPlugs.clear();
    }

    private int drainInterior() {
        int removed = 0;
        int scanned = 0;
        java.util.Map<Material, Integer> typeCounts = new java.util.EnumMap<>(Material.class);
        for (int x = minX + 1; x < maxX; x++) {
            for (int z = minZ + 1; z < maxZ; z++) {
                for (int y = maxY - 1; y > minY; y--) {
                    scanned++;
                    Block block = world.getBlockAt(x, y, z);
                    Material type = block.getType();
                    typeCounts.merge(type, 1, Integer::sum);
                    if (type == Material.WATER || type == Material.SEAGRASS
                            || type == Material.TALL_SEAGRASS || type == Material.KELP
                            || type == Material.KELP_PLANT) {
                        int credit=creditUnitsFor(block);
                        block.setType(Material.AIR, false);
                        awardProgress(credit);
                        removed++;
                    } else if (block.getBlockData() instanceof Waterlogged wl
                            && wl.isWaterlogged()) {
                        int credit=creditUnitsFor(block);
                        wl.setWaterlogged(false);
                        block.setBlockData(wl, false);
                        awardProgress(credit);
                        removed++;
                    }
                }
            }
        }
        logger.info("[cofferdam-debug] drainInterior scanned=" + scanned
                + " removed=" + removed + " types=" + typeCounts);
        return removed;
    }

    // ── Water intrusion listener (Phase D) ────────────────────────────────

    private void registerWaterIntrusionListener() {
        waterIntrusionListener = new Listener() {
            @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
            public void onAbsorb(SpongeAbsorbEvent event) {
                if (activeSponge==null || !event.getBlock().equals(activeSponge)) return;
                for (var state:event.getBlocks()) {
                    Block b=state.getBlock();
                    if (CofferdamGeometry.interior(entranceState,b.getX(),b.getY(),b.getZ())
                            && CofferdamWork.wet(b)) spongeCredits.computeIfAbsent(b,CofferdamJobTask.this::creditUnitsFor);
                }
            }
            @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
            public void onBlockFromTo(BlockFromToEvent event) {
                Block to = event.getToBlock();
                if (to.getWorld() != world) return;
                int x = to.getX(), y = to.getY(), z = to.getZ();
                if (x > minX && x < maxX && y > minY && y < maxY && z > minZ && z < maxZ) {
                    Material fromType = event.getBlock().getType();
                    if (fromType == Material.WATER || fromType == Material.LAVA) {
                        event.setCancelled(true);
                    }
                }
            }
        };
        Bukkit.getPluginManager().registerEvents(waterIntrusionListener, plugin);
        logger.info(BuilderNpcService.baseNameOf(npc)
                + "'s cofferdam water intrusion listener registered");
    }

    private void unregisterWaterIntrusionListener() {
        if (waterIntrusionListener != null) {
            HandlerList.unregisterAll(waterIntrusionListener);
            waterIntrusionListener = null;
        }
    }

    // Work credit and completion.

    private int creditUnitsFor(Block block) {
        return CofferdamWork.credit(block,npc.getUniqueId(),redundancyTracker,freshLedger);
    }

    private void awardProgress(int units) {
        if (units<=0 || levelService.specializationOf(npc)!=Specialization.GROUNDWORKER) return;
        for (var result:levelService.awardProgress(npc,TicketKind.GROUNDWORKER_CLEAR_512,512*4,units))
            for (String line:result.announcementLines()) messagePlayer(Component.text(line,NamedTextColor.GREEN));
    }

    private void flushSpongeCredit() {
        for (var entry:spongeCredits.entrySet())
            if (!CofferdamWork.wet(entry.getKey())) awardProgress(entry.getValue());
        spongeCredits.clear();
    }

    private void tickExiting() {
        Location current=npcEntity.getLocation();
        if (!CofferdamGeometry.interior(entranceState,current.getBlockX(),current.getBlockY(),current.getBlockZ())
                && !world.getBlockAt(current).getType().isSolid()
                && !world.getBlockAt(current).getRelative(BlockFace.UP).getType().isSolid()
                && world.getBlockAt(current).getType()!=Material.LAVA) {
            completeDam(); return;
        }
        if (exitTicks++==0 && CofferdamGeometry.hasEntrance(entranceState)) {
            int[] p=CofferdamGeometry.door(entranceState);
            BlockFace face=BlockFace.valueOf(entranceState.cofferdamFacing);
            for (int dy=0;dy<2;dy++) {
                Block b=world.getBlockAt(p[0],p[1]+dy,p[2]);
                if (b.getBlockData() instanceof Door door) { door.setOpen(true); b.setBlockData(door,false); }
            }
            npc.getNavigator().setTarget(new Location(world,p[0]+face.getModX()+0.5,p[1],p[2]+face.getModZ()+0.5));
        }
        if (exitTicks<100) return;
        Location safe=findSafeExit();
        if (safe!=null && npcEntity.teleport(safe)) completeDam();
        else if (exitTicks%200==0) messagePlayer(Component.text(
                "The dam is dry, but I need a safe landing outside to leave it.",NamedTextColor.YELLOW));
    }

    private Location findSafeExit() {
        for (Block chest:storage.chests()) {
            for (BlockFace face:new BlockFace[]{BlockFace.NORTH,BlockFace.EAST,BlockFace.SOUTH,BlockFace.WEST,BlockFace.UP}) {
                Block feet=chest.getRelative(face);
                if (safeExit(feet)) return feet.getLocation().add(0.5,0,0.5);
            }
        }
        for (int x=minX-2;x<=maxX+2;x++) for (int z=minZ-2;z<=maxZ+2;z++) {
            Block feet=world.getBlockAt(x,maxY+1,z);
            if (safeExit(feet)) return feet.getLocation().add(0.5,0,0.5);
        }
        // A fully submerged dam may have no dry landing nearby. Use a verified safe
        // world-spawn landing rather than trapping the Helper in an endless exit job.
        Location spawn=world.getSpawnLocation();
        for (int radius=0;radius<=16;radius++) {
            for (int dx=-radius;dx<=radius;dx++) for (int dz=-radius;dz<=radius;dz++) {
                if (Math.max(Math.abs(dx),Math.abs(dz))!=radius) continue;
                Block feet=world.getHighestBlockAt(spawn.getBlockX()+dx,spawn.getBlockZ()+dz).getRelative(BlockFace.UP);
                if (safeExit(feet)) return feet.getLocation().add(0.5,0,0.5);
            }
        }
        return null;
    }
    private boolean safeExit(Block feet) {
        return !CofferdamGeometry.interior(entranceState,feet.getX(),feet.getY(),feet.getZ())
                && feet.getType()==Material.AIR && feet.getRelative(BlockFace.UP).getType()==Material.AIR
                && feet.getRelative(BlockFace.DOWN).getType().isSolid();
    }
    private void completeDam() {
        if (CofferdamGeometry.hasEntrance(entranceState)) {
            int[] p=CofferdamGeometry.door(entranceState);
            for (int dy=0;dy<2;dy++) {
                Block block=world.getBlockAt(p[0],p[1]+dy,p[2]);
                if (block.getBlockData() instanceof Door door) { door.setOpen(false); block.setBlockData(door,false); }
            }
        }
        jobManager.watchCofferdam(toJobState());
        finish(BuilderNpcService.baseNameOf(npc)+": The dam is dry. I'm free for another job; its seven-day watch has begun.");
    }

    private void tickStriking() {
        switch (walkState) {
            case SEEKING -> seekNextStrikePosition();
            case WALKING -> walkToTarget();
            case ACTING -> removeDamBlock();
        }
    }

    private void seekNextStrikePosition() {
        strikeCursor=Math.min(strikeCursor,damBlocks.size()-1);
        if (strikeCursor < 0) {
            onStrikeComplete();
            return;
        }
        int[] pos = damBlocks.get(strikeCursor);
        targetX = pos[0];
        targetY = pos[1];
        targetZ = pos[2];
        startWalkingToTarget();
    }

    private void removeDamBlock() {
        Block block = world.getBlockAt(targetX, targetY, targetZ);
        if (block.getType() == DAM_MATERIAL) {
            world.spawnParticle(Particle.BLOCK, block.getLocation().add(0.5, 0.5, 0.5),
                    6, 0.25, 0.25, 0.25, block.getBlockData());
            block.setType(Material.AIR);
            world.playSound(block.getLocation(), Sound.BLOCK_STONE_BREAK, 0.7f, 1.0f);
            storage.deposit(java.util.Map.of(DAM_MATERIAL, 1));
        }
        strikeCursor--;
        placeDelay = PLACE_DELAY_TICKS;
        walkState = WalkState.SEEKING;
    }

    private void onStrikeComplete() {
        finish(BuilderNpcService.baseNameOf(npc)
                + ": Dam's down and blocks are back in the chest. All done.");
    }

    // ── Geometry ────────────────────────────────────────────────────────────

    static List<int[]> computeBuildOrder(int minX, int maxX, int minY, int maxY,
                                          int minZ, int maxZ) {
        List<int[]> order = new ArrayList<>();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                order.add(new int[]{x, minY, z});
            }
        }
        for (int y = minY + 1; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                order.add(new int[]{x, y, minZ});
            }
        }
        for (int y = minY + 1; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                order.add(new int[]{x, y, maxZ});
            }
        }
        for (int y = minY + 1; y <= maxY; y++) {
            for (int z = minZ + 1; z < maxZ; z++) {
                order.add(new int[]{minX, y, z});
            }
        }
        for (int y = minY + 1; y <= maxY; y++) {
            for (int z = minZ + 1; z < maxZ; z++) {
                order.add(new int[]{maxX, y, z});
            }
        }
        // Ceiling — interior of top face; perimeter at y=maxY already covered by walls
        for (int x = minX + 1; x < maxX; x++) {
            for (int z = minZ + 1; z < maxZ; z++) {
                order.add(new int[]{x, maxY, z});
            }
        }
        return order;
    }

    // ── Shared helpers ──────────────────────────────────────────────────────

    private void updateLabel() {
        String text;
        switch (cofferdamPhase) {
            case BUILDING -> {
                int total = buildOrder.size();
                int pct = total <= 0 ? 0 : (int) (buildCursor * 100L / total);
                text = "Dam " + pct + "%";
            }
            case DRAINING -> {
                if (!bulkheadWaveAnchors.isEmpty()) {
                    int placed = Math.min(bulkheadWaveIndex, bulkheadWaveAnchors.size());
                    text = "Draining " + placed + "/" + bulkheadWaveAnchors.size();
                } else {
                    text = "Draining...";
                }
            }
            case EXITING -> text = "Leaving dam";
            case STRIKING -> {
                int total = damBlocks.size();
                int removed = total - strikeCursor - 1;
                int pct = total <= 0 ? 0 : (int) (removed * 100L / total);
                text = "Strike " + pct + "%";
            }
            default -> text = "";
        }
        label.text(Component.text(text, NamedTextColor.YELLOW));
        Location npcLoc = npcEntity.getLocation();
        label.teleport(npcLoc.add(0, LABEL_HEIGHT_OFFSET, 0));
    }

    private void finish(String message) {
        teardown();
        Player player = Bukkit.getPlayer(playerId);
        if (player != null) {
            player.sendMessage(Component.text(message, NamedTextColor.GREEN));
        } else {
            jobManager.queueOfflineNotification(playerId, message);
        }
        logger.info(message + " [damBlocks=" + damBlocks.size() + "]");
        jobManager.onJobEnded(npc.getId());
    }

    private void teardown() {
        ended=true;
        flushSpongeCredit();
        if (task != null) {
            task.cancel();
            task = null;
        }
        npc.getNavigator().cancelNavigation();
        clearBulkheadPlugs();
        bulkheadWaveAnchors.clear();
        unregisterWaterIntrusionListener();
        if (npcEntity instanceof LivingEntity living) {
            living.removePotionEffect(PotionEffectType.WATER_BREATHING);
        }
        releaseChunkTickets();
        label.remove();
        if (equipment != null) {
            equipment.setItemInMainHand(null);
        }
    }

    private void escapeIfTrapped() {
        Location loc = npcEntity.getLocation();
        Block feet = world.getBlockAt(loc);
        Block head = world.getBlockAt(loc.getBlockX(), loc.getBlockY() + 1, loc.getBlockZ());
        if (!feet.getType().isSolid() && !head.getType().isSolid()) {
            return;
        }
        for (int r = 1; r <= 3; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    for (int dy = -1; dy <= 2; dy++) {
                        int fx = loc.getBlockX() + dx;
                        int fy = loc.getBlockY() + dy;
                        int fz = loc.getBlockZ() + dz;
                        Block cf = world.getBlockAt(fx, fy, fz);
                        Block ch = world.getBlockAt(fx, fy + 1, fz);
                        if (!cf.getType().isSolid() && !ch.getType().isSolid()) {
                            npcEntity.teleport(new Location(world,
                                    fx + 0.5, fy, fz + 0.5,
                                    loc.getYaw(), loc.getPitch()));
                            return;
                        }
                    }
                }
            }
        }
    }

    private void messagePlayer(Component message) {
        Player player = Bukkit.getPlayer(playerId);
        if (player != null) {
            player.sendMessage(message);
        }
    }

    // ── Chunk tickets ───────────────────────────────────────────────────────

    private void refreshChunkTickets() {
        Set<Long> desired = new HashSet<>();
        desired.add(chunkKey(npcEntity.getLocation()));
        if (CofferdamGeometry.hasEntrance(entranceState)) {
            int[] p=CofferdamGeometry.door(entranceState);
            BlockFace face=BlockFace.valueOf(entranceState.cofferdamFacing);
            desired.add(chunkKey(p[0]>>4,p[2]>>4));
            desired.add(chunkKey((p[0]+face.getModX())>>4,(p[2]+face.getModZ())>>4));
        }
        if (walkState == WalkState.WALKING || walkState == WalkState.ACTING) {
            desired.add(chunkKey(targetX >> 4, targetZ >> 4));
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

    private static int chunkX(long key) { return (int) (key >> 32); }
    private static int chunkZ(long key) { return (int) key; }
}
