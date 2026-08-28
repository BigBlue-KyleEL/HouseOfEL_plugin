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
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Runs one Landscaping job for one Helper — walks to each column in the marked area and
 * fills it with terrain. Material is conjured (the Helper just produces blocks), so there
 * is no chest or inventory management.
 *
 * <p>Three modes: Fill (solid-fill with dirt/grass), Slope (additive noise-based hills),
 * Redesign (destructive biome-aware reshaping — both fills and clears).
 */
public final class LandscaperJobTask implements JobTask {

    private enum Phase { SEEKING, WALKING, PLACING }

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
    private static final int PLACE_DELAY_TICKS = 0; // TODO: revert to 3 after testing
    private static final double NOISE_SCALE = 0.06;
    private static final int NOISE_OCTAVES = 3;

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
    private final LandscapeMode mode;
    private final LandscapeBiome landscapeBiome;
    private final int minX, maxX, minY, maxY, minZ, maxZ;
    private final int spanX, spanZ;
    private final RegionOutline outline;
    private final long noiseSeed;
    private final int gradientAX, gradientAZ;

    private int columnTargetHeight;
    private boolean inTreePhase;
    private List<int[]> treePositions;
    private int treeCursor;
    private Phase phase;
    private int columnCursor;
    private int placingY;
    private int totalPlaced;
    private int hesitationTicks;
    private boolean paused;
    private BukkitTask task;
    private int outlineTicks;
    private boolean announced50;
    private boolean announced85;
    private final long startedAtMillis = System.currentTimeMillis();

    private int placeDelay;
    private int walkTicks;
    private int noProgressTicks;
    private double closestApproachSquared;
    private int targetColumnX, targetColumnZ;
    private int[][] redesignHeightmap;
    private double lastTickHealth = -1;
    private int regenCounter;
    private int retreatTicks;
    private boolean floatingIsland;
    private int[][] islandBottom;

    private final Set<Long> ticketedChunks = new HashSet<>();

    /** Fresh job, just dispatched. */
    LandscaperJobTask(Plugin plugin, JobManager jobManager, HelperLevelService levelService,
                       Player player, NPC npc, Entity npcEntity, EntityEquipment equipment,
                       TextDisplay label, World world, LandscapeMode mode, LandscapeBiome landscapeBiome,
                       int minX, int maxX, int minY, int maxY, int minZ, int maxZ,
                       int spanX, int spanZ, RegionOutline outline,
                       int gradientAX, int gradientAZ) {
        this(plugin, jobManager, levelService, player.getUniqueId(), npc, npcEntity, equipment,
                label, world, mode, landscapeBiome, minX, maxX, minY, maxY, minZ, maxZ, spanX, spanZ, outline,
                Phase.SEEKING, 0, 0, -1, gradientAX, gradientAZ);
    }

    private LandscaperJobTask(Plugin plugin, JobManager jobManager, HelperLevelService levelService,
                               UUID playerId, NPC npc, Entity npcEntity, EntityEquipment equipment,
                               TextDisplay label, World world, LandscapeMode mode, LandscapeBiome landscapeBiome,
                               int minX, int maxX, int minY, int maxY, int minZ, int maxZ,
                               int spanX, int spanZ, RegionOutline outline,
                               Phase phase, int columnCursor, int totalPlaced, int treeCursorInit,
                               int gradientAX, int gradientAZ) {
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
        this.mode = mode;
        this.landscapeBiome = landscapeBiome;
        this.minX = minX;
        this.maxX = maxX;
        this.minY = minY;
        this.maxY = maxY;
        this.minZ = minZ;
        this.maxZ = maxZ;
        this.spanX = spanX;
        this.spanZ = spanZ;
        this.outline = outline;
        this.noiseSeed = (long) minX * 341873128712L + (long) minZ * 132897987541L + (long) minY * 49979687L;
        this.gradientAX = gradientAX;
        this.gradientAZ = gradientAZ;
        this.phase = phase;
        this.columnCursor = columnCursor;
        this.totalPlaced = totalPlaced;
        this.treeCursor = treeCursorInit;
        this.announced50 = percentComplete() >= 50;
        this.announced85 = percentComplete() >= 85;
    }

    static LandscaperJobTask resume(Plugin plugin, JobManager jobManager, HelperLevelService levelService,
                                     JobState state, NPC npc) {
        World world = Bukkit.getWorld(state.worldName);
        Entity npcEntity = npc.getEntity();
        if (world == null || npcEntity == null) {
            return null;
        }
        LandscapeMode mode;
        try {
            mode = LandscapeMode.valueOf(state.landscapeMode);
        } catch (IllegalArgumentException | NullPointerException e) {
            mode = LandscapeMode.FILL;
        }
        LandscapeBiome biome = null;
        if (state.landscapeBiome != null) {
            try {
                biome = LandscapeBiome.valueOf(state.landscapeBiome);
            } catch (IllegalArgumentException ignored) {}
        }

        int spanX = state.maxX - state.minX + 1;
        int spanZ = state.maxZ - state.minZ + 1;

        EntityEquipment equipment = ClearJobTask.equipTool(npcEntity, Material.IRON_SHOVEL,
                BuilderNpcService.baseNameOf(npc), TaskType.LANDSCAPE.toolNoun());
        TextDisplay label = ClearJobTask.spawnLabel(npcEntity.getLocation());
        RegionOutline outline = new RegionOutline(world, state.minX, state.minY, state.minZ,
                state.maxX, state.maxY, state.maxZ);

        LandscaperJobTask task = new LandscaperJobTask(plugin, jobManager, levelService, state.playerId, npc,
                npcEntity, equipment, label, world, mode, biome, state.minX, state.maxX, state.minY,
                state.maxY, state.minZ, state.maxZ, spanX, spanZ, outline,
                Phase.SEEKING, state.landscapeColumn, state.landscapePlaced, state.landscapeTreeCursor,
                state.gradientAX, state.gradientAZ);
        task.floatingIsland = state.floatingIsland;
        return task;
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

    int percentComplete() {
        int totalColumns = spanX * spanZ;
        return totalColumns <= 0 ? 0 : (int) (columnCursor * 100L / totalColumns);
    }

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
        logger.info(BuilderNpcService.baseNameOf(npc) + "'s landscaping job was cancelled ["
                + totalPlaced + " placed]");
    }

    @Override
    public JobState toJobState() {
        JobState state = new JobState();
        state.jobType = JobType.LANDSCAPE;
        state.npcId = npc.getId();
        state.playerId = playerId;
        state.worldName = world.getName();
        state.minX = minX;
        state.maxX = maxX;
        state.minY = minY;
        state.maxY = maxY;
        state.minZ = minZ;
        state.maxZ = maxZ;
        state.landscapeMode = mode.name();
        state.landscapeBiome = landscapeBiome != null ? landscapeBiome.name() : null;
        state.landscapeColumn = columnCursor;
        state.landscapePlaced = totalPlaced;
        state.landscapeTreeCursor = treeCursor;
        state.gradientAX = gradientAX;
        state.gradientAZ = gradientAZ;
        state.floatingIsland = floatingIsland;
        return state;
    }

    private void tick() {
        if (!npcEntity.isValid()) {
            finish(BuilderNpcService.baseNameOf(npc) + " disappeared mid-job — landscaping stopped early.");
            return;
        }

        if (npcEntity instanceof LivingEntity living) {
            double health = living.getHealth();
            if (lastTickHealth >= 0 && health < lastTickHealth) {
                npc.getNavigator().cancelNavigation();
                Location npcLoc = npcEntity.getLocation();
                double awayX = npcLoc.getX() - (targetColumnX + 0.5);
                double awayZ = npcLoc.getZ() - (targetColumnZ + 0.5);
                double len = Math.sqrt(awayX * awayX + awayZ * awayZ);
                if (len > 0.01) { awayX /= len; awayZ /= len; }
                else { awayX = 1; awayZ = 0; }
                npc.getNavigator().setTarget(new Location(world,
                        npcLoc.getX() + awayX * 5, npcLoc.getY(), npcLoc.getZ() + awayZ * 5));
                retreatTicks = 60;
                regenCounter = 0;
            }
            lastTickHealth = health;
            if (health < living.getMaxHealth() && retreatTicks <= 0) {
                regenCounter++;
                if (regenCounter >= 80) {
                    living.setHealth(Math.min(living.getMaxHealth(), health + 1));
                    regenCounter = 0;
                }
            }
        }

        if (retreatTicks > 0) {
            retreatTicks--;
        } else if (hesitationTicks > 0) {
            hesitationTicks--;
        } else {
            switch (phase) {
                case SEEKING -> seekNextColumn();
                case WALKING -> walkToColumn();
                case PLACING -> placeNextBlock();
            }
        }

        if (phase == Phase.WALKING && outlineTicks % WALK_CUE_PERIOD == 0) {
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
        announceMilestones();
        outlineTicks++;
        updateLabel();
    }

    private void seekNextColumn() {
        if (inTreePhase) {
            seekNextTree();
            return;
        }
        int totalColumns = spanX * spanZ;
        while (columnCursor < totalColumns) {
            int x = minX + (columnCursor % spanX);
            int z = minZ + (columnCursor / spanX);
            if (columnNeedsWork(x, z)) {
                targetColumnX = x;
                targetColumnZ = z;
                startWalkingToColumn();
                return;
            }
            columnCursor++;
        }
        if (mode == LandscapeMode.REDESIGN && landscapeBiome != null && !inTreePhase) {
            beginTreePhase();
            return;
        }
        finish(BuilderNpcService.baseNameOf(npc) + ": All done — placed " + totalPlaced + " block(s). "
                + "The area's filled up and ready to go.");
    }

    private boolean columnNeedsWork(int x, int z) {
        if (mode == LandscapeMode.SLOPE) {
            int target = computeSlopeTarget(x, z);
            for (int y = minY; y <= target; y++) {
                if (!world.getBlockAt(x, y, z).getType().isSolid()) return true;
            }
            return false;
        }
        if (mode == LandscapeMode.REDESIGN) {
            int target = computeRedesignTarget(x, z);
            int bottom = floatingIsland && islandBottom != null
                    ? islandBottom[x - minX][z - minZ] : minY;
            for (int y = minY; y <= maxY; y++) {
                if (y < bottom) {
                    if (world.getBlockAt(x, y, z).getType().isSolid()) return true;
                } else if (y <= target) {
                    Material desired = materialForRedesign(x, y, z, target);
                    if (world.getBlockAt(x, y, z).getType() != desired) return true;
                } else if (y == target + 1) {
                    Material deco = decorationForBiome(x, z);
                    Material current = world.getBlockAt(x, y, z).getType();
                    if (deco != null && current != deco) return true;
                    if (deco == null && current.isSolid()) return true;
                } else {
                    if (world.getBlockAt(x, y, z).getType().isSolid()) return true;
                }
            }
            return false;
        }
        for (int y = minY; y <= maxY; y++) {
            if (!world.getBlockAt(x, y, z).getType().isSolid()) return true;
        }
        return false;
    }

    private void startWalkingToColumn() {
        if (npcEntity.getLocation().getY() < minY) {
            beginPlacing();
            return;
        }
        int surfaceY = highestSolidY(targetColumnX, targetColumnZ);
        Location target = new Location(world, targetColumnX + 0.5, surfaceY + 1, targetColumnZ + 0.5);
        npc.getNavigator().setTarget(target);
        phase = Phase.WALKING;
        walkTicks = 0;
        noProgressTicks = 0;
        closestApproachSquared = Double.MAX_VALUE;
    }

    private int highestSolidY(int x, int z) {
        for (int y = maxY; y >= minY; y--) {
            if (world.getBlockAt(x, y, z).getType().isSolid()) {
                return y;
            }
        }
        return minY > 0 ? minY - 1 : minY;
    }

    private void walkToColumn() {
        int surfaceY = highestSolidY(targetColumnX, targetColumnZ);
        Location columnPos = new Location(world, targetColumnX + 0.5, surfaceY + 1, targetColumnZ + 0.5);
        double distSq = npcEntity.getLocation().distanceSquared(columnPos);

        if (distSq <= REACH_DISTANCE * REACH_DISTANCE) {
            beginPlacing();
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
            beginPlacing();
            return;
        }

        if (walkTicks > PATH_GRACE_TICKS && !npc.getNavigator().isNavigating()) {
            npc.getNavigator().setTarget(columnPos);
        }
    }

    private void relocateToCompletedColumn() {
        int bestX = -1, bestZ = -1, bestSurface = -1;
        double bestDist = Double.MAX_VALUE;
        for (int i = 0; i < columnCursor && i < spanX * spanZ; i++) {
            int cx = minX + (i % spanX);
            int cz = minZ + (i / spanX);
            int surface = highestSolidY(cx, cz);
            if (surface < minY) continue;
            double dx = cx - targetColumnX;
            double dz = cz - targetColumnZ;
            double dist = dx * dx + dz * dz;
            if (dist < bestDist) {
                bestDist = dist;
                bestX = cx;
                bestZ = cz;
                bestSurface = surface;
            }
        }
        if (bestX >= 0) {
            int safeY = bestSurface + 1;
            while (world.getBlockAt(bestX, safeY, bestZ).getType().isSolid()
                    || world.getBlockAt(bestX, safeY + 1, bestZ).getType().isSolid()) {
                safeY++;
            }
            npcEntity.teleport(new Location(world, bestX + 0.5, safeY, bestZ + 0.5,
                    npcEntity.getLocation().getYaw(), npcEntity.getLocation().getPitch()));
        }
    }

    private void beginPlacing() {
        npc.getNavigator().cancelNavigation();
        if (npcEntity.getLocation().getY() < minY) {
            relocateToCompletedColumn();
        }
        if (inTreePhase) {
            plantTree();
            treeCursor++;
            hesitationTicks = HelperTempo.hesitationTicksFor(levelService.levelOf(npc));
            phase = Phase.SEEKING;
            return;
        }
        placingY = minY;
        if (mode == LandscapeMode.SLOPE) {
            columnTargetHeight = computeSlopeTarget(targetColumnX, targetColumnZ);
        } else if (mode == LandscapeMode.REDESIGN) {
            columnTargetHeight = computeRedesignTarget(targetColumnX, targetColumnZ);
        }
        phase = Phase.PLACING;
    }

    private void placeNextBlock() {
        if (placeDelay > 0) {
            placeDelay--;
            return;
        }

        int columnTop = (mode == LandscapeMode.SLOPE) ? columnTargetHeight : maxY;
        if (placingY > columnTop) {
            columnCursor++;
            hesitationTicks = HelperTempo.hesitationTicksFor(levelService.levelOf(npc));
            phase = Phase.SEEKING;
            return;
        }

        Block block = world.getBlockAt(targetColumnX, placingY, targetColumnZ);
        if (mode == LandscapeMode.SLOPE) {
            if (!block.getType().isSolid()) {
                placeBlock(block, materialForSlope(targetColumnX, placingY, targetColumnZ, columnTargetHeight));
            }
        } else if (mode == LandscapeMode.REDESIGN) {
            int bottom = floatingIsland && islandBottom != null
                    ? islandBottom[targetColumnX - minX][targetColumnZ - minZ] : minY;
            if (placingY < bottom) {
                if (block.getType().isSolid()) {
                    clearBlock(block);
                }
            } else if (placingY <= columnTargetHeight) {
                Material desired = materialForRedesign(targetColumnX, placingY, targetColumnZ, columnTargetHeight);
                if (block.getType() != desired) {
                    placeBlock(block, desired);
                }
            } else if (placingY == columnTargetHeight + 1) {
                Material deco = decorationForBiome(targetColumnX, targetColumnZ);
                if (deco != null && block.getType() != deco) {
                    block.setType(deco);
                    totalPlaced++;
                    placeDelay = PLACE_DELAY_TICKS;
                } else if (deco == null && block.getType().isSolid()) {
                    clearBlock(block);
                }
            } else if (block.getType().isSolid()) {
                clearBlock(block);
            }
        } else {
            if (!block.getType().isSolid()) {
                placeBlock(block, (placingY == maxY) ? Material.GRASS_BLOCK : Material.DIRT);
            }
        }
        placingY++;
    }

    private void placeBlock(Block block, Material material) {
        block.setType(material);
        totalPlaced++;
        placeDelay = PLACE_DELAY_TICKS;
        world.playSound(block.getLocation(), Sound.BLOCK_ROOTED_DIRT_PLACE, 0.7f, 1.0f);
        world.spawnParticle(Particle.BLOCK, block.getLocation().add(0.5, 0.5, 0.5),
                6, 0.25, 0.25, 0.25, block.getBlockData());

        Location npcLoc = npcEntity.getLocation();
        double dx = npcLoc.getX() - (targetColumnX + 0.5);
        double dz = npcLoc.getZ() - (targetColumnZ + 0.5);
        if (dx * dx + dz * dz < 2.25 && npcLoc.getY() < placingY + 2) {
            int safeY = placingY + 1;
            int npcBlockX = npcLoc.getBlockX();
            int npcBlockZ = npcLoc.getBlockZ();
            while (world.getBlockAt(npcBlockX, safeY, npcBlockZ).getType().isSolid()
                    || world.getBlockAt(npcBlockX, safeY + 1, npcBlockZ).getType().isSolid()) {
                safeY++;
                if (safeY > maxY + 10) break;
            }
            npcEntity.teleport(new Location(world, npcLoc.getX(), safeY, npcLoc.getZ(),
                    npcLoc.getYaw(), npcLoc.getPitch()));
        }
    }

    private void clearBlock(Block block) {
        world.spawnParticle(Particle.BLOCK, block.getLocation().add(0.5, 0.5, 0.5),
                6, 0.25, 0.25, 0.25, block.getBlockData());
        block.setType(Material.AIR);
        totalPlaced++;
        placeDelay = PLACE_DELAY_TICKS;
        world.playSound(block.getLocation(), Sound.BLOCK_ROOTED_DIRT_BREAK, 0.7f, 1.0f);
    }

    private void announceMilestones() {
        int percent = percentComplete();
        if (!announced50 && percent >= 50) {
            announced50 = true;
            messagePlayer(Component.text(
                    BuilderNpcService.baseNameOf(npc) + ": Making good progress — about halfway through the landscaping now.",
                    NamedTextColor.YELLOW));
        }
        if (!announced85 && percent >= 85) {
            announced85 = true;
            messagePlayer(Component.text(
                    BuilderNpcService.baseNameOf(npc) + ": Nearly there — just a bit more to fill in.",
                    NamedTextColor.YELLOW));
        }
    }

    private void updateLabel() {
        label.text(Component.text(percentComplete() + "%", NamedTextColor.YELLOW));
        Location npcLoc = npcEntity.getLocation();
        label.teleport(npcLoc.add(0, LABEL_HEIGHT_OFFSET, 0));
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
        Player player = Bukkit.getPlayer(playerId);
        if (player != null) {
            player.sendMessage(Component.text(message, NamedTextColor.GREEN));
        } else {
            jobManager.queueOfflineNotification(playerId, message);
        }
        logger.info(message + " [placed=" + totalPlaced + ", columns=" + columnCursor + "]");
        jobManager.onJobEnded(npc.getId());
    }

    private void messagePlayer(Component message) {
        Player player = Bukkit.getPlayer(playerId);
        if (player != null) {
            player.sendMessage(message);
        }
    }

    private int computeSlopeTarget(int x, int z) {
        int ground = highestSolidY(x, z);
        double t = gradientFraction(x, z);
        double noise = octaveNoise(x * NOISE_SCALE, z * NOISE_SCALE);
        double noiseVar = (noise + 1.0) / 2.0 * 0.3;
        int spanY = maxY - minY;
        int hillHeight = (int) ((t + noiseVar) * spanY);
        return Math.min(maxY, ground + hillHeight);
    }

    private double octaveNoise(double x, double z) {
        double value = 0;
        double amplitude = 1.0;
        double frequency = 1.0;
        double maxAmplitude = 0;
        for (int i = 0; i < NOISE_OCTAVES; i++) {
            value += smoothNoise(x * frequency, z * frequency) * amplitude;
            maxAmplitude += amplitude;
            amplitude *= 0.5;
            frequency *= 2.0;
        }
        return value / maxAmplitude;
    }

    private double smoothNoise(double x, double z) {
        int ix = (int) Math.floor(x);
        int iz = (int) Math.floor(z);
        double fx = x - ix;
        double fz = z - iz;
        fx = fx * fx * (3 - 2 * fx);
        fz = fz * fz * (3 - 2 * fz);
        double a = noiseHash(ix, iz);
        double b = noiseHash(ix + 1, iz);
        double c = noiseHash(ix, iz + 1);
        double d = noiseHash(ix + 1, iz + 1);
        return lerp(lerp(a, b, fx), lerp(c, d, fx), fz);
    }

    private double noiseHash(int x, int z) {
        long h = x * 374761393L + z * 668265263L + noiseSeed;
        h = (h ^ (h >> 13)) * 1274126177L;
        h = h ^ (h >> 16);
        return (h & 0x7fffffffL) / (double) 0x7fffffffL * 2.0 - 1.0;
    }

    private static double lerp(double a, double b, double t) {
        return a + t * (b - a);
    }

    private Material materialForSlope(int x, int y, int z, int targetHeight) {
        return BiomePalette.materialAt(landscapeBiome, x, y, z, targetHeight, noiseSeed);
    }

    private boolean blockVariation(int x, int y, int z, double chance) {
        long h = x * 374761393L + y * 668265263L + z * 1274126177L + noiseSeed;
        h = (h ^ (h >> 13)) * 1103515245L;
        return ((h & 0x7fffffffL) / (double) 0x7fffffffL) < chance;
    }

    private int computeRedesignTarget(int x, int z) {
        if (redesignHeightmap == null) {
            redesignHeightmap = buildRedesignHeightmap();
        }
        return redesignHeightmap[x - minX][z - minZ];
    }

    private int[][] buildRedesignHeightmap() {
        int[] edgeNorth = new int[spanX];
        int[] edgeSouth = new int[spanX];
        int[] edgeWest = new int[spanZ];
        int[] edgeEast = new int[spanZ];
        int solidCount = 0;
        for (int i = 0; i < spanX; i++) {
            edgeNorth[i] = sampleEdgeHeight(minX + i, minZ - 1);
            edgeSouth[i] = sampleEdgeHeight(minX + i, maxZ + 1);
            if (edgeNorth[i] > minY) solidCount++;
            if (edgeSouth[i] > minY) solidCount++;
        }
        for (int j = 0; j < spanZ; j++) {
            edgeWest[j] = sampleEdgeHeight(minX - 1, minZ + j);
            edgeEast[j] = sampleEdgeHeight(maxX + 1, minZ + j);
            if (edgeWest[j] > minY) solidCount++;
            if (edgeEast[j] > minY) solidCount++;
        }

        int totalEdgeSamples = 2 * spanX + 2 * spanZ;
        if (!floatingIsland && solidCount * 4 < totalEdgeSamples) {
            floatingIsland = true;
        }
        if (floatingIsland) {
            return buildFloatingIslandHeightmap();
        }

        int[][] hm = new int[spanX][spanZ];
        int cornerNW = sampleEdgeHeight(minX - 1, minZ - 1);
        int cornerNE = sampleEdgeHeight(maxX + 1, minZ - 1);
        int cornerSW = sampleEdgeHeight(minX - 1, maxZ + 1);
        int cornerSE = sampleEdgeHeight(maxX + 1, maxZ + 1);

        for (int i = 0; i < spanX; i++) {
            double u = spanX > 1 ? (double) i / (spanX - 1) : 0.5;
            for (int j = 0; j < spanZ; j++) {
                double v = spanZ > 1 ? (double) j / (spanZ - 1) : 0.5;
                double h = (1 - v) * edgeNorth[i] + v * edgeSouth[i]
                         + (1 - u) * edgeWest[j] + u * edgeEast[j]
                         - (1 - u) * (1 - v) * cornerNW
                         - u * (1 - v) * cornerNE
                         - (1 - u) * v * cornerSW
                         - u * v * cornerSE;
                hm[i][j] = (int) Math.round(h);
            }
        }

        for (int pass = 0; pass < 3; pass++) {
            int[][] smoothed = new int[spanX][spanZ];
            for (int i = 0; i < spanX; i++) {
                System.arraycopy(hm[i], 0, smoothed[i], 0, spanZ);
            }
            for (int i = 1; i < spanX - 1; i++) {
                for (int j = 1; j < spanZ - 1; j++) {
                    double avg = (hm[i - 1][j] + hm[i + 1][j] + hm[i][j - 1] + hm[i][j + 1]) / 4.0;
                    double diff = hm[i][j] - avg;
                    if (Math.abs(diff) > 2) {
                        smoothed[i][j] = (int) Math.round(hm[i][j] - diff * 0.3);
                    }
                }
            }
            hm = smoothed;
        }

        for (int i = 0; i < spanX; i++) {
            int x = minX + i;
            for (int j = 0; j < spanZ; j++) {
                int z = minZ + j;
                double noise = octaveNoise(x * NOISE_SCALE, z * NOISE_SCALE);
                hm[i][j] += (int) (noise * 2);
                hm[i][j] = Math.max(minY, Math.min(maxY, hm[i][j]));
            }
        }

        return hm;
    }

    private int[][] buildFloatingIslandHeightmap() {
        int[][] hm = new int[spanX][spanZ];
        islandBottom = new int[spanX][spanZ];
        int spanY = maxY - minY;
        double centerX = (spanX - 1) / 2.0;
        double centerZ = (spanZ - 1) / 2.0;
        int surfaceBase = minY + (int) (spanY * 0.70);
        int surfaceAmplitude = Math.max(3, (int) (spanY * 0.30));
        int deepestBottom = minY + 3;

        for (int i = 0; i < spanX; i++) {
            int x = minX + i;
            for (int j = 0; j < spanZ; j++) {
                int z = minZ + j;

                double surfNoise = octaveNoise(x * NOISE_SCALE, z * NOISE_SCALE);
                double detailNoise = octaveNoise(x * NOISE_SCALE * 2.5, z * NOISE_SCALE * 2.5) * 0.3;
                int surfaceY = surfaceBase + (int) ((surfNoise + detailNoise) * surfaceAmplitude);
                surfaceY = Math.max(minY + 4, Math.min(maxY, surfaceY));
                hm[i][j] = surfaceY;

                double dx = centerX > 0 ? Math.abs(i - centerX) / centerX : 0;
                double dz = centerZ > 0 ? Math.abs(j - centerZ) / centerZ : 0;
                double rawEdge = Math.sqrt(dx * dx + dz * dz);
                double outlineNoise = octaveNoise((x + 1000) * 0.1, (z + 1000) * 0.1);
                double edgeFrac = Math.min(1.0, rawEdge / Math.max(0.5, 1.0 + outlineNoise * 0.3));
                double edgeCurve = edgeFrac * edgeFrac;
                int depthRange = surfaceY - 1 - deepestBottom;
                int bottom = deepestBottom + (int) (depthRange * edgeCurve);

                double shapeNoise = octaveNoise((x + 3000) * NOISE_SCALE * 1.5, (z + 3000) * NOISE_SCALE * 1.5);
                bottom += (int) (shapeNoise * depthRange * 0.3);

                double jagNoise = octaveNoise((x + 500) * NOISE_SCALE * 3, (z + 500) * NOISE_SCALE * 3);
                bottom += (int) (jagNoise * depthRange * 0.1);

                islandBottom[i][j] = Math.max(minY, Math.min(surfaceY - 1, bottom));
            }
        }
        return hm;
    }

    private int sampleEdgeHeight(int x, int z) {
        for (int y = maxY + 10; y >= minY; y--) {
            if (world.getBlockAt(x, y, z).getType().isSolid()) {
                return Math.max(minY, Math.min(maxY, y));
            }
        }
        return minY;
    }

    private double gradientFraction(int x, int z) {
        int oppX = (gradientAX == minX) ? maxX : minX;
        int oppZ = (gradientAZ == minZ) ? maxZ : minZ;
        double dx = oppX - gradientAX;
        double dz = oppZ - gradientAZ;
        double lenSq = dx * dx + dz * dz;
        if (lenSq == 0) return 0.5;
        double t = ((x - gradientAX) * dx + (z - gradientAZ) * dz) / lenSq;
        return Math.max(0.0, Math.min(1.0, t));
    }

    private Material materialForRedesign(int x, int y, int z, int targetHeight) {
        Material mat = BiomePalette.materialAt(landscapeBiome, x, y, z, targetHeight, noiseSeed);
        if (floatingIsland && islandBottom != null && y == islandBottom[x - minX][z - minZ]) {
            mat = supportBlock(mat);
        }
        return mat;
    }

    private static Material supportBlock(Material mat) {
        return switch (mat) {
            case SAND -> Material.SANDSTONE;
            case RED_SAND -> Material.RED_SANDSTONE;
            case GRAVEL -> Material.COBBLESTONE;
            default -> mat;
        };
    }

    private Material decorationForBiome(int x, int z) {
        if (landscapeBiome == null) return null;
        long h = x * 341873128712L + z * 132897987541L + noiseSeed + 31337L;
        h = (h ^ (h >> 13)) * 1274126177L;
        double v = (h & 0x7fffffffL) / (double) 0x7fffffffL;
        return switch (landscapeBiome) {
            case PLAINS -> {
                if (v < 0.25) yield Material.SHORT_GRASS;
                if (v < 0.30) yield Material.POPPY;
                if (v < 0.34) yield Material.DANDELION;
                if (v < 0.37) yield Material.CORNFLOWER;
                if (v < 0.39) yield Material.AZURE_BLUET;
                if (v < 0.41) yield Material.PINK_TULIP;
                if (v < 0.43) yield Material.RED_TULIP;
                if (v < 0.44) yield Material.ORANGE_TULIP;
                if (v < 0.45) yield Material.WHITE_TULIP;
                if (v < 0.47) yield Material.OXEYE_DAISY;
                if (v < 0.48) yield Material.ALLIUM;
                yield null;
            }
            case DESERT -> {
                if (v < 0.04) yield Material.DEAD_BUSH;
                if (v < 0.07) yield Material.CACTUS;
                yield null;
            }
            case BADLANDS -> {
                if (v < 0.03) yield Material.DEAD_BUSH;
                if (v < 0.05) yield Material.CACTUS;
                yield null;
            }
            case SNOWY -> {
                if (v < 0.70) yield Material.SNOW;
                yield null;
            }
            case TAIGA -> {
                if (v < 0.18) yield Material.FERN;
                if (v < 0.26) yield Material.SHORT_GRASS;
                if (v < 0.30) yield Material.SWEET_BERRY_BUSH;
                yield null;
            }
            case JUNGLE -> {
                if (v < 0.22) yield Material.SHORT_GRASS;
                if (v < 0.35) yield Material.FERN;
                if (v < 0.38) yield Material.MELON;
                yield null;
            }
            case MUSHROOM -> {
                if (v < 0.10) yield Material.RED_MUSHROOM;
                if (v < 0.20) yield Material.BROWN_MUSHROOM;
                yield null;
            }
        };
    }

    // ── Tree placement (Redesign phase 2) ──────────────────────────────────────

    private void beginTreePhase() {
        inTreePhase = true;
        treePositions = new ArrayList<>();
        for (int x = minX + 2; x <= maxX - 2; x++) {
            for (int z = minZ + 2; z <= maxZ - 2; z++) {
                if (isTreePosition(x, z)) {
                    treePositions.add(new int[]{x, z});
                }
            }
        }
        if (treeCursor < 0) treeCursor = 0;
        if (treePositions.isEmpty()) {
            finish(BuilderNpcService.baseNameOf(npc) + ": All done — placed " + totalPlaced + " block(s). "
                    + "The area's filled up and ready to go.");
            return;
        }
        phase = Phase.SEEKING;
    }

    private boolean isTreePosition(int x, int z) {
        long h = x * 374761393L + z * 668265263L + noiseSeed + 999983L;
        h = (h ^ (h >> 13)) * 1103515245L;
        double v = (h & 0x7fffffffL) / (double) 0x7fffffffL;
        double density = treeDensity();
        return v < density;
    }

    private double treeDensity() {
        if (landscapeBiome == null) return 0.02;
        return switch (landscapeBiome) {
            case JUNGLE -> 0.08;
            case TAIGA -> 0.06;
            case PLAINS -> 0.02;
            case MUSHROOM -> 0.04;
            case SNOWY -> 0.015;
            case DESERT, BADLANDS -> 0.005;
        };
    }

    private void seekNextTree() {
        while (treeCursor < treePositions.size()) {
            int[] pos = treePositions.get(treeCursor);
            targetColumnX = pos[0];
            targetColumnZ = pos[1];
            startWalkingToColumn();
            return;
        }
        finish(BuilderNpcService.baseNameOf(npc) + ": All done — placed " + totalPlaced + " block(s). "
                + "Terrain shaped and trees planted.");
    }

    private void plantTree() {
        int x = targetColumnX;
        int z = targetColumnZ;
        int groundY = computeRedesignTarget(x, z);
        if (groundY < minY) groundY = minY;

        Material logMat = treeLogMaterial();
        Material leafMat = treeLeafMaterial();
        int trunkHeight = treeTrunkHeight(x, z);

        for (int dy = 1; dy <= trunkHeight; dy++) {
            Block block = world.getBlockAt(x, groundY + dy, z);
            if (!block.getType().isSolid()) {
                block.setType(logMat);
                totalPlaced++;
            }
        }

        int leafBase = groundY + trunkHeight - 1;
        int leafTop = groundY + trunkHeight + 1;
        for (int ly = leafBase; ly <= leafTop; ly++) {
            int radius = (ly == leafTop) ? 1 : 2;
            for (int lx = x - radius; lx <= x + radius; lx++) {
                for (int lz = z - radius; lz <= z + radius; lz++) {
                    if (lx == x && lz == z && ly < leafTop) continue;
                    if (Math.abs(lx - x) == radius && Math.abs(lz - z) == radius
                            && blockVariation(lx, ly, lz, 0.3)) continue;
                    Block leaf = world.getBlockAt(lx, ly, lz);
                    if (!leaf.getType().isSolid()) {
                        leaf.setType(leafMat);
                        totalPlaced++;
                    }
                }
            }
        }

        world.playSound(new Location(world, x + 0.5, groundY + 1, z + 0.5),
                Sound.BLOCK_AZALEA_LEAVES_PLACE, 0.8f, 0.9f);
    }

    private int treeTrunkHeight(int x, int z) {
        long h = x * 341873128712L + z * 132897987541L + noiseSeed + 7919L;
        h = (h ^ (h >> 13)) * 1274126177L;
        int base = (landscapeBiome == LandscapeBiome.JUNGLE) ? 6 : 4;
        int variation = (landscapeBiome == LandscapeBiome.JUNGLE) ? 4 : 2;
        return base + (int) ((h & 0x7fffffffL) % variation);
    }

    private Material treeLogMaterial() {
        if (landscapeBiome == null) return Material.OAK_LOG;
        return switch (landscapeBiome) {
            case JUNGLE -> Material.JUNGLE_LOG;
            case TAIGA, SNOWY -> Material.SPRUCE_LOG;
            case DESERT -> Material.DEAD_BUSH;
            case BADLANDS -> Material.DARK_OAK_LOG;
            case MUSHROOM -> Material.MUSHROOM_STEM;
            default -> Material.OAK_LOG;
        };
    }

    private Material treeLeafMaterial() {
        if (landscapeBiome == null) return Material.OAK_LEAVES;
        return switch (landscapeBiome) {
            case JUNGLE -> Material.JUNGLE_LEAVES;
            case TAIGA, SNOWY -> Material.SPRUCE_LEAVES;
            case DESERT -> Material.AIR;
            case BADLANDS -> Material.DARK_OAK_LEAVES;
            case MUSHROOM -> Material.RED_MUSHROOM_BLOCK;
            default -> Material.OAK_LEAVES;
        };
    }

    private void refreshChunkTickets() {
        Set<Long> desired = new HashSet<>();
        desired.add(chunkKey(npcEntity.getLocation()));
        if (phase == Phase.WALKING || phase == Phase.PLACING) {
            desired.add(chunkKey(targetColumnX >> 4, targetColumnZ >> 4));
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
}
