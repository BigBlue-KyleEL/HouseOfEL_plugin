package com.houseofel.builder.job;

import com.houseofel.builder.gui.TaskType;
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
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Builds a sealed dam wall around a marked underwater region, drains the interior,
 * and maintains it dry until the player cancels. On cancel, the NPC strikes (removes)
 * the dam and returns blocks to the chest.
 *
 * <p>Phases: BUILDING (place dam walls) → DRAINING (sponge the interior) →
 * MAINTAINING (periodic scan, fix breaches) → STRIKING (remove dam on cancel).
 *
 * <p>Dam blocks come from the station chest ({@link JobStorage#withdraw}) and are
 * returned on strike ({@link JobStorage#deposit}).
 */
public final class CofferdamJobTask implements JobTask {

    enum CofferdamPhase { BUILDING, DRAINING, MAINTAINING, STRIKING }
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
    private static final int MAINTENANCE_SCAN_TICKS = 20 * 20;

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
    private int maintenanceTicks;
    private int outlineTicks;
    private int strikeCursor;
    private boolean announcedHalf;

    private final Set<Long> ticketedChunks = new HashSet<>();
    private final long startedAtMillis = System.currentTimeMillis();

    CofferdamJobTask(Plugin plugin, JobManager jobManager, HelperLevelService levelService,
                     UUID playerId, NPC npc, Entity npcEntity, EntityEquipment equipment,
                     TextDisplay label, World world,
                     int minX, int maxX, int minY, int maxY, int minZ, int maxZ,
                     RegionOutline outline, JobStorage storage) {
        this(plugin, jobManager, levelService, playerId, npc, npcEntity, equipment, label,
                world, minX, maxX, minY, maxY, minZ, maxZ, outline, storage,
                CofferdamPhase.BUILDING, 0, new ArrayList<>(), 0);
    }

    private CofferdamJobTask(Plugin plugin, JobManager jobManager, HelperLevelService levelService,
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
        this.playerId = playerId;
        this.npc = npc;
        this.npcEntity = npcEntity;
        this.equipment = equipment;
        this.label = label;
        this.world = world;
        this.minX = minX;
        this.maxX = maxX;
        this.minY = minY;
        this.maxY = maxY;
        this.minZ = minZ;
        this.maxZ = maxZ;
        this.outline = outline;
        this.storage = storage;
        this.cofferdamPhase = cofferdamPhase;
        this.buildCursor = buildCursor;
        this.damBlocks = damBlocks;
        this.strikeCursor = strikeCursor;
        this.walkState = WalkState.SEEKING;
        this.buildOrder = computeBuildOrder(minX, maxX, minY, maxY, minZ, maxZ);
        this.announcedHalf = buildOrder.isEmpty() || buildCursor * 2 >= buildOrder.size();
    }

    static CofferdamJobTask resume(Plugin plugin, JobManager jobManager,
                                    HelperLevelService levelService, JobState state, NPC npc) {
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

        return new CofferdamJobTask(plugin, jobManager, levelService, state.playerId,
                npc, npcEntity, equipment, label, world,
                state.minX, state.maxX, state.minY, state.maxY, state.minZ, state.maxZ,
                outline, storage, phase, state.buildCursor, damBlocks, state.strikeCursor);
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
        if (cofferdamPhase == CofferdamPhase.MAINTAINING) {
            return Long.MAX_VALUE;
        }
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
        refreshChunkTickets();
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

    @Override
    public void cancelJob() {
        if (cofferdamPhase == CofferdamPhase.MAINTAINING
                || cofferdamPhase == CofferdamPhase.DRAINING) {
            cofferdamPhase = CofferdamPhase.STRIKING;
            strikeCursor = damBlocks.size() - 1;
            walkState = WalkState.SEEKING;
            messagePlayer(Component.text(
                    BuilderNpcService.baseNameOf(npc)
                            + ": Right, I'll pull the dam down and put the blocks back.",
                    NamedTextColor.YELLOW));
            return;
        }
        teardown();
        logger.info(BuilderNpcService.baseNameOf(npc) + "'s cofferdam job was cancelled ["
                + damBlocks.size() + " dam blocks placed]");
    }

    @Override
    public JobState toJobState() {
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
                case MAINTAINING -> tickMaintaining();
                case STRIKING -> tickStriking();
            }
        }

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
            if (!block.getType().isSolid()) {
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
        if (block.getType().isSolid()) {
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
        messagePlayer(Component.text(
                BuilderNpcService.baseNameOf(npc)
                        + ": Dam walls are sealed. Draining the interior now.",
                NamedTextColor.GREEN));
        cofferdamPhase = CofferdamPhase.DRAINING;
        walkState = WalkState.SEEKING;
    }

    // ── DRAINING phase (stub — full implementation in Phase C) ──────────

    private void tickDraining() {
        drainInterior();
        cofferdamPhase = CofferdamPhase.MAINTAINING;
        maintenanceTicks = 0;
        messagePlayer(Component.text(
                BuilderNpcService.baseNameOf(npc)
                        + ": The area is dry. I'll keep it that way — cancel the job when "
                        + "you're done working inside.",
                NamedTextColor.GREEN));
        logger.info(BuilderNpcService.baseNameOf(npc)
                + "'s cofferdam entered maintenance [damBlocks=" + damBlocks.size() + "]");
    }

    private void drainInterior() {
        for (int x = minX + 1; x < maxX; x++) {
            for (int z = minZ + 1; z < maxZ; z++) {
                for (int y = maxY - 1; y > minY; y--) {
                    Block block = world.getBlockAt(x, y, z);
                    Material type = block.getType();
                    if (type == Material.WATER || type == Material.SEAGRASS
                            || type == Material.TALL_SEAGRASS || type == Material.KELP
                            || type == Material.KELP_PLANT) {
                        block.setType(Material.AIR);
                    }
                }
            }
        }
    }

    // ── MAINTAINING phase ──────────────────────────────────────────────────

    private void tickMaintaining() {
        maintenanceTicks++;
        if (maintenanceTicks < MAINTENANCE_SCAN_TICKS) {
            return;
        }
        maintenanceTicks = 0;

        int repaired = 0;
        for (int[] pos : damBlocks) {
            Block block = world.getBlockAt(pos[0], pos[1], pos[2]);
            if (!block.getType().isSolid()) {
                int withdrawn = storage.withdraw(DAM_MATERIAL, 1);
                if (withdrawn > 0) {
                    block.setType(DAM_MATERIAL);
                    repaired++;
                }
            }
        }

        int drained = 0;
        for (int x = minX + 1; x < maxX; x++) {
            for (int z = minZ + 1; z < maxZ; z++) {
                for (int y = maxY - 1; y > minY; y--) {
                    Block block = world.getBlockAt(x, y, z);
                    if (block.getType() == Material.WATER) {
                        block.setType(Material.AIR);
                        drained++;
                    }
                }
            }
        }

        if (repaired > 0 || drained > 0) {
            logger.info(BuilderNpcService.baseNameOf(npc)
                    + "'s cofferdam maintenance: repaired " + repaired + " dam blocks, drained "
                    + drained + " water blocks");
        }
    }

    // ── STRIKING phase ─────────────────────────────────────────────────────

    private void tickStriking() {
        switch (walkState) {
            case SEEKING -> seekNextStrikePosition();
            case WALKING -> walkToTarget();
            case ACTING -> removeDamBlock();
        }
    }

    private void seekNextStrikePosition() {
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
            case DRAINING -> text = "Draining...";
            case MAINTAINING -> text = "Maintaining";
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
        if (task != null) {
            task.cancel();
            task = null;
        }
        npc.getNavigator().cancelNavigation();
        releaseChunkTickets();
        label.remove();
        if (equipment != null) {
            equipment.setItemInMainHand(null);
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
