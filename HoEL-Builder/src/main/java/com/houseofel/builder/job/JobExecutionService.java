package com.houseofel.builder.job;

import com.houseofel.builder.antigrind.FreshLedger;
import com.houseofel.builder.choice.GroundworkerL8Choice;
import com.houseofel.builder.choice.MilestoneChoiceRecord;
import com.houseofel.builder.choice.MilestoneChoiceStore;
import com.houseofel.builder.antigrind.RedundancyTracker;
import com.houseofel.builder.death.DeathRecordStore;
import com.houseofel.builder.gui.Target;
import com.houseofel.builder.gui.TaskType;
import com.houseofel.builder.npc.BuilderNpcService;
import com.houseofel.builder.npc.HelperLevelService;
import com.houseofel.builder.npc.Specialization;
import com.houseofel.builder.region.RegionOutline;
import net.citizensnpcs.api.npc.NPC;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.plugin.Plugin;

import java.util.Deque;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Dispatches the only wired-up task type so far: Clearing. Builds the region bounds,
 * stands up a storage chest and work-area outline if requested, then hands off to a
 * {@link ClearJobTask} — which is where the actual tick-by-tick work happens, and
 * which {@link JobManager} keeps a handle on for pause/resume/cancel and persistence.
 */
public final class JobExecutionService {

    private final Plugin plugin;
    private final Logger logger;
    private final JobManager jobManager;
    private final HelperLevelService levelService;
    private final DeathRecordStore deathRecordStore;
    private final RedundancyTracker redundancyTracker;
    private final FreshLedger freshLedger;
    private final MilestoneChoiceStore choiceStore;

    public JobExecutionService(Plugin plugin, JobManager jobManager, HelperLevelService levelService,
                                DeathRecordStore deathRecordStore, RedundancyTracker redundancyTracker,
                                FreshLedger freshLedger, MilestoneChoiceStore choiceStore) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.jobManager = jobManager;
        this.levelService = levelService;
        this.deathRecordStore = deathRecordStore;
        this.redundancyTracker = redundancyTracker;
        this.freshLedger = freshLedger;
        this.choiceStore = choiceStore;
    }

    /**
     * Landscaper's depth cap, from its own balance clause in V1 Perk Ladders ("Depth
     * capped at 16 blocks below surface... It cannot dig deep — hand it a quarry and it
     * refuses and says so"). The one hard limit that makes the Quarryman/Landscaper fork
     * an actual trade rather than a cosmetic label.
     */
    private static final int LANDSCAPER_MAX_DEPTH = 16;

    /** True when this Helper has actually PICKED Landscaper at its level-8 Choice slot. */
    private boolean isLandscaper(NPC npc) {
        MilestoneChoiceRecord record = choiceStore.find(npc.getUniqueId(), 8);
        return record != null && GroundworkerL8Choice.LANDSCAPER.name().equals(record.choice());
    }

    public Consumer<Block> dispatchClear(Player player, NPC npc, TaskType taskType, Target target,
                               Location pointA, Location pointB, boolean storeInChest,
                               boolean surfaceOnly, boolean restoreTopsoil) {
        Entity npcEntity = npc.getEntity();
        if (npcEntity == null) {
            player.sendMessage(Component.text(
                    BuilderNpcService.baseNameOf(npc) + " isn't spawned right now — can't start the job.", NamedTextColor.RED));
            return null;
        }
        if (jobManager.isAtCeiling()) {
            String eta = jobManager.etaOfSoonestJob().orElse("a little while");
            player.sendMessage(Component.text(
                    "Every Helper is tied up right now — check back in " + eta + ".", NamedTextColor.RED));
            return null;
        }
        if (jobManager.find(npc.getId()) != null) {
            player.sendMessage(Component.text(
                    BuilderNpcService.baseNameOf(npc) + " is already busy — wait for that to finish first.", NamedTextColor.RED));
            return null;
        }

        deathRecordStore.setOwner(npc.getUniqueId(), player.getUniqueId());

        World world = pointA.getWorld();
        if (world.getEnvironment() == World.Environment.NETHER
                || world.getEnvironment() == World.Environment.THE_END) {
            player.sendMessage(Component.text(
                    BuilderNpcService.baseNameOf(npc) + ": Rough territory out here — I'll be careful, but you might want to keep an eye on me.",
                    NamedTextColor.YELLOW));
        }

        int minX = Math.min(pointA.getBlockX(), pointB.getBlockX());
        int minY = Math.min(pointA.getBlockY(), pointB.getBlockY());
        int minZ = Math.min(pointA.getBlockZ(), pointB.getBlockZ());
        int maxX = Math.max(pointA.getBlockX(), pointB.getBlockX());
        int maxY = Math.max(pointA.getBlockY(), pointB.getBlockY());
        int maxZ = Math.max(pointA.getBlockZ(), pointB.getBlockZ());

        if (isLandscaper(npc) && (maxY - minY + 1) > LANDSCAPER_MAX_DEPTH) {
            player.sendMessage(Component.text(BuilderNpcService.baseNameOf(npc)
                    + ": That's a quarry, not landscaping — I work the surface, no deeper than "
                    + LANDSCAPER_MAX_DEPTH + " blocks. Mark something shallower and I'll make it look like it grew there.",
                    NamedTextColor.RED));
            return null;
        }

        if (shouldSurvey(npc) && !runSurvey(player, npc, world, minX, minY, minZ, maxX, maxY, maxZ, JobType.CLEAR)) {
            return null;
        }

        JobStorage storage = storeInChest
                ? new JobStorage(plugin, world, minX, maxX, minY, maxY, minZ, maxZ)
                : null;

        if (storage != null) {
            Location chestAt = storage.depositPoint();
            if (chestAt == null) {
                player.sendMessage(Component.text(BuilderNpcService.baseNameOf(npc)
                        + ": I can't find a spot for a chest. Place one down nearby "
                        + "and tap it with the rod.",
                        NamedTextColor.YELLOW));
                return chestBlock -> {
                    storage.adoptChest(chestBlock);
                    String c = "(" + chestBlock.getX() + ", " + chestBlock.getY() + ", "
                            + chestBlock.getZ() + ")";
                    player.sendMessage(Component.text(BuilderNpcService.baseNameOf(npc)
                            + ": Got it, I'll use that one at " + c + ".",
                            NamedTextColor.GREEN));
                    finishClearDispatch(player, npc, taskType, target, world,
                            minX, maxX, minY, maxY, minZ, maxZ, storage,
                            storeInChest, surfaceOnly, restoreTopsoil);
                };
            }
            String coords = "(" + chestAt.getBlockX() + ", " + chestAt.getBlockY() + ", "
                    + chestAt.getBlockZ() + ")";
            player.sendMessage(Component.text("Storage chest placed at " + coords,
                    NamedTextColor.AQUA));
            logger.info("Storage chest placed at " + coords + " for " + player.getName() + "'s job");
        }

        finishClearDispatch(player, npc, taskType, target, world,
                minX, maxX, minY, maxY, minZ, maxZ, storage,
                storeInChest, surfaceOnly, restoreTopsoil);
        return null;
    }

    private void finishClearDispatch(Player player, NPC npc, TaskType taskType, Target target,
                                      World world, int minX, int maxX, int minY, int maxY, int minZ, int maxZ,
                                      JobStorage storage, boolean storeInChest, boolean surfaceOnly,
                                      boolean restoreTopsoil) {
        Entity npcEntity = npc.getEntity();
        if (npcEntity == null) return;

        Material initialTool = Material.IRON_SHOVEL;
        int spanX = maxX - minX + 1;
        int spanZ = maxZ - minZ + 1;
        long totalCells = (long) spanX * (maxY - minY + 1) * spanZ;

        TextDisplay label = ClearJobTask.spawnLabel(npcEntity.getLocation());
        EntityEquipment equipment = ClearJobTask.equipTool(npcEntity, initialTool,
                BuilderNpcService.baseNameOf(npc), taskType.toolNoun());

        player.sendMessage(Component.text(BuilderNpcService.baseNameOf(npc)
                + ": Right, I'll get started on the " + target.label() + " — "
                + totalCells + " blocks to check.", NamedTextColor.GREEN));
        logger.info(player.getName() + " dispatched CLEAR/" + target + " job over " + totalCells + " cells");

        RegionOutline outline = new RegionOutline(world, minX, minY, minZ, maxX, maxY, maxZ);

        ClearJobTask task = new ClearJobTask(plugin, jobManager, levelService, redundancyTracker, freshLedger,
                player, npc, npcEntity, equipment, label, world, target, initialTool,
                minX, maxX, minY, maxY, minZ, maxZ,
                spanX, spanZ, totalCells, storage, storeInChest, surfaceOnly, outline,
                restoreTopsoil, false);
        jobManager.register(task);
        task.start();
    }

    public void dispatchLandscape(Player player, NPC npc, Location pointA, Location pointB,
                                   LandscapeMode mode, LandscapeBiome landscapeBiome) {
        Entity npcEntity = npc.getEntity();
        if (npcEntity == null) {
            player.sendMessage(Component.text(
                    BuilderNpcService.baseNameOf(npc) + " isn't spawned right now — can't start the job.", NamedTextColor.RED));
            return;
        }
        if (jobManager.isAtCeiling()) {
            String eta = jobManager.etaOfSoonestJob().orElse("a little while");
            player.sendMessage(Component.text(
                    "Every Helper is tied up right now — check back in " + eta + ".", NamedTextColor.RED));
            return;
        }
        if (jobManager.find(npc.getId()) != null) {
            player.sendMessage(Component.text(
                    BuilderNpcService.baseNameOf(npc) + " is already busy — wait for that to finish first.", NamedTextColor.RED));
            return;
        }

        deathRecordStore.setOwner(npc.getUniqueId(), player.getUniqueId());

        World world = pointA.getWorld();
        if (world.getEnvironment() == World.Environment.NETHER
                || world.getEnvironment() == World.Environment.THE_END) {
            player.sendMessage(Component.text(
                    BuilderNpcService.baseNameOf(npc) + ": Rough territory out here — I'll be careful, but you might want to keep an eye on me.",
                    NamedTextColor.YELLOW));
        }

        int minX = Math.min(pointA.getBlockX(), pointB.getBlockX());
        int minY = Math.min(pointA.getBlockY(), pointB.getBlockY());
        int minZ = Math.min(pointA.getBlockZ(), pointB.getBlockZ());
        int maxX = Math.max(pointA.getBlockX(), pointB.getBlockX());
        int maxY = Math.max(pointA.getBlockY(), pointB.getBlockY());
        int maxZ = Math.max(pointA.getBlockZ(), pointB.getBlockZ());

        int spanX = maxX - minX + 1;
        int spanZ = maxZ - minZ + 1;

        TextDisplay label = ClearJobTask.spawnLabel(npcEntity.getLocation());
        EntityEquipment equipment = ClearJobTask.equipTool(npcEntity, Material.IRON_SHOVEL,
                BuilderNpcService.baseNameOf(npc), TaskType.LANDSCAPE.toolNoun());

        player.sendMessage(Component.text(BuilderNpcService.baseNameOf(npc)
                + ": Right, I'll get the area shaped up — " + (spanX * spanZ) + " columns to cover.",
                NamedTextColor.GREEN));
        logger.info(player.getName() + " dispatched LANDSCAPE/" + mode + " job over " + (spanX * spanZ) + " columns");

        if (shouldSurvey(npc) && !runSurvey(player, npc, world, minX, minY, minZ, maxX, maxY, maxZ, JobType.LANDSCAPE)) {
            return;
        }

        RegionOutline outline = new RegionOutline(world, minX, minY, minZ, maxX, maxY, maxZ);

        LandscaperJobTask task = new LandscaperJobTask(plugin, jobManager, levelService, redundancyTracker, freshLedger,
                player, npc, npcEntity, equipment, label, world, mode, landscapeBiome,
                minX, maxX, minY, maxY, minZ, maxZ, spanX, spanZ, outline,
                pointA.getBlockX(), pointA.getBlockZ());
        jobManager.register(task);
        task.start();
    }

    /**
     * Quarryman Phase B/C. {@code target}/{@code surfaceOnly} are accepted for signature
     * symmetry with {@link #dispatchClear} but genuinely unused: Quarryman has no
     * material choice (it digs everything within its fixed shape, via
     * {@link Target#ANY_EARTH} internally) and always digs regardless of sky exposure.
     * Shares {@link JobManager}'s registry/ceiling/persistence with Clear jobs since
     * 2026-08-21 — see {@link QuarrymanJobTask}'s class doc.
     */
    public Consumer<Block> dispatchQuarryman(Player player, NPC npc, TaskType taskType, Target target,
                                   Location pointA, Location pointB, boolean storeInChest,
                                   boolean surfaceOnly, Integer requestedLevels, Integer requestedTargetY) {
        Entity npcEntity = npc.getEntity();
        if (npcEntity == null) {
            player.sendMessage(Component.text(
                    BuilderNpcService.baseNameOf(npc) + " isn't spawned right now — can't start the job.", NamedTextColor.RED));
            return null;
        }
        if (jobManager.isAtCeiling()) {
            String eta = jobManager.etaOfSoonestJob().orElse("a little while");
            player.sendMessage(Component.text(
                    "Every Helper is tied up right now — check back in " + eta + ".", NamedTextColor.RED));
            return null;
        }
        if (jobManager.find(npc.getId()) != null) {
            player.sendMessage(Component.text(
                    BuilderNpcService.baseNameOf(npc) + " is already busy — wait for that to finish first.", NamedTextColor.RED));
            return null;
        }

        deathRecordStore.setOwner(npc.getUniqueId(), player.getUniqueId());

        World world = pointA.getWorld();

        int minX = Math.min(pointA.getBlockX(), pointB.getBlockX());
        int maxX = Math.max(pointA.getBlockX(), pointB.getBlockX());
        int minZ = Math.min(pointA.getBlockZ(), pointB.getBlockZ());
        int maxZ = Math.max(pointA.getBlockZ(), pointB.getBlockZ());
        int topY = Math.max(pointA.getBlockY(), pointB.getBlockY());
        int dx = pointB.getBlockX() - pointA.getBlockX();
        int dz = pointB.getBlockZ() - pointA.getBlockZ();
        boolean stepsAlongX = Integer.signum(dx) == Integer.signum(dz);
        int stepDirection = stepsAlongX ? Integer.signum(dx) : Integer.signum(dz);
        if (stepDirection == 0) {
            stepDirection = 1;
        }

        int requestedDepth;
        if (requestedTargetY != null) {
            if (requestedTargetY >= topY) {
                player.sendMessage(Component.text(BuilderNpcService.baseNameOf(npc)
                        + ": Y coordinate destination and work area is invalid — " + requestedTargetY
                        + " isn't below where you marked (top is " + topY + ").", NamedTextColor.RED));
                return null;
            }
            requestedDepth = topY - requestedTargetY + 1;
        } else if (requestedLevels != null) {
            requestedDepth = requestedLevels;
        } else {
            requestedDepth = 1;
        }

        if (requestedDepth < 1) {
            player.sendMessage(Component.text(BuilderNpcService.baseNameOf(npc)
                    + ": Can't dig a negative number of blocks — ask for at least 1.", NamedTextColor.RED));
            return null;
        }
        int bottomY = topY - requestedDepth + 1;
        if (bottomY < world.getMinHeight()) {
            player.sendMessage(Component.text(BuilderNpcService.baseNameOf(npc)
                    + ": That goes below the bottom of the world (Y " + world.getMinHeight()
                    + ") — ask for a shallower depth.", NamedTextColor.RED));
            return null;
        }

        if (shouldSurvey(npc) && !runSurvey(player, npc, world, minX, bottomY, minZ, maxX, topY, maxZ, JobType.QUARRY)) {
            return null;
        }

        Deque<Block> digOrder = QuarrymanJobTask.buildDigOrder(world, minX, maxX, minZ, maxZ, topY, requestedDepth,
                stepsAlongX, stepDirection);

        JobStorage storage = storeInChest
                ? new JobStorage(plugin, world, minX, maxX, bottomY, topY, minZ, maxZ)
                : null;

        if (storage != null) {
            Location chestAt = storage.depositPoint();
            if (chestAt == null) {
                player.sendMessage(Component.text(BuilderNpcService.baseNameOf(npc)
                        + ": I can't find a spot for a chest. Place one down nearby "
                        + "and tap it with the rod.",
                        NamedTextColor.YELLOW));
                int finalRequestedDepth = requestedDepth;
                boolean finalStepsAlongX = stepsAlongX;
                int finalStepDirection = stepDirection;
                return chestBlock -> {
                    storage.adoptChest(chestBlock);
                    String c = "(" + chestBlock.getX() + ", " + chestBlock.getY() + ", "
                            + chestBlock.getZ() + ")";
                    player.sendMessage(Component.text(BuilderNpcService.baseNameOf(npc)
                            + ": Got it, I'll use that one at " + c + ".",
                            NamedTextColor.GREEN));
                    finishQuarryDispatch(player, npc, world, minX, maxX, minZ, maxZ, topY,
                            finalRequestedDepth, finalStepsAlongX, finalStepDirection,
                            digOrder, storage);
                };
            }
            String coords = "(" + chestAt.getBlockX() + ", " + chestAt.getBlockY() + ", "
                    + chestAt.getBlockZ() + ")";
            player.sendMessage(Component.text("Storage chest placed at " + coords, NamedTextColor.AQUA));
            logger.info("Storage chest placed at " + coords + " for " + player.getName() + "'s job");
        }

        finishQuarryDispatch(player, npc, world, minX, maxX, minZ, maxZ, topY,
                requestedDepth, stepsAlongX, stepDirection, digOrder, storage);
        return null;
    }

    private void finishQuarryDispatch(Player player, NPC npc, World world,
                                       int minX, int maxX, int minZ, int maxZ, int topY,
                                       int requestedDepth, boolean stepsAlongX, int stepDirection,
                                       Deque<Block> digOrder, JobStorage storage) {
        Entity npcEntity = npc.getEntity();
        if (npcEntity == null) return;

        Material initialTool = Material.IRON_PICKAXE;
        int bottomY = topY - requestedDepth + 1;

        TextDisplay label = ClearJobTask.spawnLabel(npcEntity.getLocation());
        EntityEquipment equipment = ClearJobTask.equipTool(npcEntity, initialTool,
                BuilderNpcService.baseNameOf(npc), TaskType.QUARRY.toolNoun());

        player.sendMessage(Component.text(BuilderNpcService.baseNameOf(npc)
                + ": Right, I'll step this down as I go — one block deeper each row, "
                + requestedDepth + " block(s) deep by the far end. "
                + digOrder.size() + " blocks to check.", NamedTextColor.GREEN));
        logger.info(player.getName() + " dispatched QUARRY job over " + digOrder.size()
                + " cells, depth " + requestedDepth);

        RegionOutline outline = new RegionOutline(world, minX, bottomY, minZ, maxX, topY, maxZ);

        QuarrymanJobTask task = new QuarrymanJobTask(plugin, jobManager, levelService, redundancyTracker,
                freshLedger, player.getUniqueId(), npc,
                npcEntity, equipment, label, world, initialTool, minX, maxX, minZ, maxZ, topY, requestedDepth,
                stepsAlongX, stepDirection, digOrder, storage, outline);
        jobManager.register(task);
        task.start();
    }

    public Consumer<Block> dispatchCofferdam(Player player, NPC npc, Location pointA, Location pointB) {
        Entity npcEntity = npc.getEntity();
        if (npcEntity == null) {
            player.sendMessage(Component.text(
                    BuilderNpcService.baseNameOf(npc) + " isn't spawned right now — can't start the job.", NamedTextColor.RED));
            return null;
        }
        if (jobManager.isAtCeiling()) {
            String eta = jobManager.etaOfSoonestJob().orElse("a little while");
            player.sendMessage(Component.text(
                    "Every Helper is tied up right now — check back in " + eta + ".", NamedTextColor.RED));
            return null;
        }
        if (jobManager.find(npc.getId()) != null) {
            player.sendMessage(Component.text(
                    BuilderNpcService.baseNameOf(npc) + " is already busy — wait for that to finish first.", NamedTextColor.RED));
            return null;
        }

        deathRecordStore.setOwner(npc.getUniqueId(), player.getUniqueId());

        World world = pointA.getWorld();

        int minX = Math.min(pointA.getBlockX(), pointB.getBlockX()) - 1;
        int minY = Math.min(pointA.getBlockY(), pointB.getBlockY()) - 1;
        int minZ = Math.min(pointA.getBlockZ(), pointB.getBlockZ()) - 1;
        int maxX = Math.max(pointA.getBlockX(), pointB.getBlockX()) + 1;
        int maxY = Math.max(pointA.getBlockY(), pointB.getBlockY()) + 1;
        int maxZ = Math.max(pointA.getBlockZ(), pointB.getBlockZ()) + 1;

        if (maxY-minY-1 < 2) {
            player.sendMessage(Component.text("Select at least two blocks of usable interior height.",NamedTextColor.RED));
            return null;
        }
        if (minY < world.getMinHeight() || Math.max(maxY,minY+4) >= world.getMaxHeight()) {
            player.sendMessage(Component.text("The dam shell and entrance marker would exceed world height limits.",NamedTextColor.RED));
            return null;
        }
        if (!world.getWorldBorder().isInside(new Location(world,minX-1,minY,minZ-1))
                || !world.getWorldBorder().isInside(new Location(world,maxX+2,maxY,maxZ+2))) {
            player.sendMessage(Component.text("The dam shell or entrance marker would cross the world border.",NamedTextColor.RED));
            return null;
        }
        String facing=CofferdamGeometry.facing(player.getLocation().getX(),player.getLocation().getZ(),minX,maxX,minZ,maxZ);
        Material door=CofferdamGeometry.chooseDoor(world,new Location(world,(minX+maxX)/2.0,minY+1,(minZ+maxZ)/2.0));
        if (door==null) {
            player.sendMessage(Component.text("No wooded biome was found within 8192 blocks for the dam door.",NamedTextColor.RED));
            return null;
        }
        boolean hasWater = false;
        outer:
        for (int x = minX+1; x < maxX; x++) {
            for (int z = minZ+1; z < maxZ; z++) {
                for (int y = minY+1; y < maxY; y++) {
                    if (CofferdamWork.wet(world.getBlockAt(x, y, z))) {
                        hasWater = true;
                        break outer;
                    }
                }
            }
        }
        if (!hasWater) {
            player.sendMessage(Component.text(BuilderNpcService.baseNameOf(npc)
                    + ": There's no water in that area — a cofferdam wouldn't do anything.",
                    NamedTextColor.RED));
            return null;
        }

        if (shouldSurvey(npc) && !runSurvey(player, npc, world, minX, minY, minZ, maxX, maxY, maxZ, JobType.COFFERDAM)) {
            return null;
        }

        boolean hasCeiling=CofferdamGeometry.requiresCeiling(world,minX,maxX,maxY,minZ,maxZ);
        int wallBlocks = CofferdamJobTask.computeBuildOrder(minX, maxX, minY, maxY, minZ, maxZ,hasCeiling).size();

        JobStorage storage = new JobStorage(plugin, world, minX, maxX, minY, maxY, minZ, maxZ);
        Location chestAt = storage.depositPoint();
        if (chestAt == null) {
            player.sendMessage(Component.text(BuilderNpcService.baseNameOf(npc)
                    + ": I can't find a spot for a chest out here. Place one down nearby "
                    + "and tap it with the rod.",
                    NamedTextColor.YELLOW));
            return chestBlock -> {
                storage.adoptChest(chestBlock);
                String c = "(" + chestBlock.getX() + ", " + chestBlock.getY() + ", "
                        + chestBlock.getZ() + ")";
                player.sendMessage(Component.text(BuilderNpcService.baseNameOf(npc)
                        + ": Got it, I'll use that one at " + c + ".",
                        NamedTextColor.GREEN));
                finishCofferdamDispatch(player, npc, storage, world,
                        minX, maxX, minY, maxY, minZ, maxZ, wallBlocks, facing, door, hasCeiling);
            };
        }

        String coords = "(" + chestAt.getBlockX() + ", " + chestAt.getBlockY() + ", "
                + chestAt.getBlockZ() + ")";
        player.sendMessage(Component.text("Storage chest placed at " + coords
                + " — fill it with cobblestone for the dam walls.", NamedTextColor.AQUA));
        logger.info("Storage chest placed at " + coords + " for " + player.getName() + "'s cofferdam");

        finishCofferdamDispatch(player, npc, storage, world,
                minX, maxX, minY, maxY, minZ, maxZ, wallBlocks, facing, door, hasCeiling);
        return null;
    }

    private void finishCofferdamDispatch(Player player, NPC npc, JobStorage storage, World world,
                                          int minX, int maxX, int minY, int maxY, int minZ, int maxZ,
                                          int wallBlocks, String facing, Material door, boolean hasCeiling) {
        Entity npcEntity = npc.getEntity();
        if (npcEntity == null) return;

        TextDisplay label = ClearJobTask.spawnLabel(npcEntity.getLocation());
        EntityEquipment equipment = ClearJobTask.equipTool(npcEntity, Material.IRON_SHOVEL,
                BuilderNpcService.baseNameOf(npc), TaskType.COFFERDAM.toolNoun());

        player.sendMessage(Component.text(BuilderNpcService.baseNameOf(npc)
                + ": Right, I'll wall it off and drain the inside — up to " + wallBlocks
                + " blocks to seal. Make sure there's cobblestone in the chest.",
                NamedTextColor.GREEN));
        logger.info(player.getName() + " dispatched COFFERDAM job, up to " + wallBlocks + " wall blocks");

        RegionOutline outline = new RegionOutline(world, minX, minY, minZ, maxX, maxY, maxZ);

        CofferdamJobTask task = new CofferdamJobTask(plugin, jobManager, levelService, redundancyTracker, freshLedger,
                player.getUniqueId(), npc, npcEntity, equipment, label, world,
                minX, maxX, minY, maxY, minZ, maxZ, outline, storage);
        task.configureEntrance(facing,door);
        task.configureCeiling(hasCeiling);
        jobManager.register(task);
        task.start();
    }

    public Consumer<Block> dispatchShaftMiner(Player player, NPC npc, Location pointA, Location pointB,
                                                Integer requestedLevels, Integer requestedTargetY) {
        Entity npcEntity = npc.getEntity();
        if (npcEntity == null) {
            player.sendMessage(Component.text(
                    BuilderNpcService.baseNameOf(npc) + " isn't spawned right now — can't start the job.", NamedTextColor.RED));
            return null;
        }
        if (jobManager.isAtCeiling()) {
            String eta = jobManager.etaOfSoonestJob().orElse("a little while");
            player.sendMessage(Component.text(
                    "Every Helper is tied up right now — check back in " + eta + ".", NamedTextColor.RED));
            return null;
        }
        if (jobManager.find(npc.getId()) != null) {
            player.sendMessage(Component.text(
                    BuilderNpcService.baseNameOf(npc) + " is already busy — wait for that to finish first.", NamedTextColor.RED));
            return null;
        }

        deathRecordStore.setOwner(npc.getUniqueId(), player.getUniqueId());

        World world = pointA.getWorld();

        int minX = Math.min(pointA.getBlockX(), pointB.getBlockX());
        int maxX = Math.max(pointA.getBlockX(), pointB.getBlockX());
        int minZ = Math.min(pointA.getBlockZ(), pointB.getBlockZ());
        int maxZ = Math.max(pointA.getBlockZ(), pointB.getBlockZ());
        int topY = Math.max(pointA.getBlockY(), pointB.getBlockY());

        int spanX = maxX - minX + 1;
        int spanZ = maxZ - minZ + 1;
        if (spanX < 2 || spanZ < 2) {
            player.sendMessage(Component.text(BuilderNpcService.baseNameOf(npc)
                    + ": That footprint is too narrow for a proper shaft — mark at least a 2x2 area.",
                    NamedTextColor.RED));
            return null;
        }

        int requestedDepth;
        if (requestedTargetY != null) {
            if (requestedTargetY >= topY) {
                player.sendMessage(Component.text(BuilderNpcService.baseNameOf(npc)
                        + ": Y coordinate destination and work area is invalid — " + requestedTargetY
                        + " isn't below where you marked (top is " + topY + ").", NamedTextColor.RED));
                return null;
            }
            requestedDepth = topY - requestedTargetY + 1;
        } else if (requestedLevels != null) {
            requestedDepth = requestedLevels;
        } else {
            requestedDepth = 1;
        }

        if (requestedDepth < 1) {
            player.sendMessage(Component.text(BuilderNpcService.baseNameOf(npc)
                    + ": Can't dig a negative number of blocks — ask for at least 1.", NamedTextColor.RED));
            return null;
        }
        int bottomY = topY - requestedDepth + 1;
        if (bottomY < world.getMinHeight()) {
            player.sendMessage(Component.text(BuilderNpcService.baseNameOf(npc)
                    + ": That goes below the bottom of the world (Y " + world.getMinHeight()
                    + ") — ask for a shallower depth.", NamedTextColor.RED));
            return null;
        }

        if (shouldSurvey(npc) && !runSurvey(player, npc, world, minX, bottomY, minZ, maxX, topY, maxZ, JobType.SHAFT_MINER)) {
            return null;
        }

        Deque<Block> digOrder = ShaftMinerJobTask.buildDigOrder(world, minX, maxX, minZ, maxZ, topY, requestedDepth);

        JobStorage storage = new JobStorage(plugin, world, minX, maxX, bottomY, topY, minZ, maxZ);
        Location chestAt = storage.depositPoint();
        if (chestAt == null) {
            player.sendMessage(Component.text(BuilderNpcService.baseNameOf(npc)
                    + ": I can't find a spot for a chest. Place one down nearby "
                    + "and tap it with the rod.",
                    NamedTextColor.YELLOW));
            int finalRequestedDepth = requestedDepth;
            return chestBlock -> {
                storage.adoptChest(chestBlock);
                String c = "(" + chestBlock.getX() + ", " + chestBlock.getY() + ", "
                        + chestBlock.getZ() + ")";
                player.sendMessage(Component.text(BuilderNpcService.baseNameOf(npc)
                        + ": Got it, I'll use that one at " + c + ".",
                        NamedTextColor.GREEN));
                finishShaftMinerDispatch(player, npc, world, minX, maxX, minZ, maxZ, topY,
                        finalRequestedDepth, digOrder, storage);
            };
        }

        String coords = "(" + chestAt.getBlockX() + ", " + chestAt.getBlockY() + ", "
                + chestAt.getBlockZ() + ")";
        player.sendMessage(Component.text("Storage chest placed at " + coords, NamedTextColor.AQUA));
        logger.info("Storage chest placed at " + coords + " for " + player.getName() + "'s shaft miner job");

        finishShaftMinerDispatch(player, npc, world, minX, maxX, minZ, maxZ, topY,
                requestedDepth, digOrder, storage);
        return null;
    }

    private void finishShaftMinerDispatch(Player player, NPC npc, World world,
                                           int minX, int maxX, int minZ, int maxZ, int topY,
                                           int requestedDepth, Deque<Block> digOrder, JobStorage storage) {
        Entity npcEntity = npc.getEntity();
        if (npcEntity == null) return;

        Material initialTool = Material.IRON_PICKAXE;
        int bottomY = topY - requestedDepth + 1;

        TextDisplay label = ClearJobTask.spawnLabel(npcEntity.getLocation());
        EntityEquipment equipment = ClearJobTask.equipTool(npcEntity, initialTool,
                BuilderNpcService.baseNameOf(npc), TaskType.SHAFT_MINER.toolNoun());

        player.sendMessage(Component.text(BuilderNpcService.baseNameOf(npc)
                + ": Right, I'll sink this shaft straight down — " + requestedDepth
                + " block(s) deep. " + digOrder.size() + " blocks to check.", NamedTextColor.GREEN));
        logger.info(player.getName() + " dispatched SHAFT_MINER job over " + digOrder.size()
                + " cells, depth " + requestedDepth);

        RegionOutline outline = new RegionOutline(world, minX, bottomY, minZ, maxX, topY, maxZ);

        ShaftMinerJobTask task = new ShaftMinerJobTask(plugin, jobManager, levelService, redundancyTracker,
                freshLedger, player.getUniqueId(), npc, npcEntity, equipment, label, world, initialTool,
                minX, maxX, minZ, maxZ, topY, requestedDepth, digOrder, storage, outline);
        jobManager.register(task);
        task.start();
    }

    private boolean shouldSurvey(NPC npc) {
        return levelService.specializationOf(npc) == Specialization.GROUNDWORKER
                && levelService.levelOf(npc) >= RegionSurvey.STAKE_OUT_LEVEL;
    }

    /**
     * Runs the L12 Stake-Out survey, posts results to the player, and returns true if
     * the job should proceed. Returns false if the NPC refuses the region.
     */
    private boolean runSurvey(Player player, NPC npc, World world,
                               int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                               JobType jobType) {
        String name = BuilderNpcService.baseNameOf(npc);
        RegionSurvey.SurveyResult result = RegionSurvey.analyze(
                world, minX, minY, minZ, maxX, maxY, maxZ, jobType);

        for (String line : RegionSurvey.formatSummary(result, jobType)) {
            player.sendMessage(Component.text(name + ": " + line, NamedTextColor.AQUA));
        }

        if (result.refused()) {
            for (String reason : result.refusals()) {
                player.sendMessage(Component.text(name + ": " + reason, NamedTextColor.RED));
            }
            player.sendMessage(Component.text(name + ": I'm not starting this one — mark a different area.",
                    NamedTextColor.RED));
            logger.info(name + " (#" + npc.getId() + ") REFUSED region: " + result.refusals());
            return false;
        }

        for (String warning : result.warnings()) {
            player.sendMessage(Component.text(name + ": " + warning, NamedTextColor.YELLOW));
        }

        return true;
    }
}
