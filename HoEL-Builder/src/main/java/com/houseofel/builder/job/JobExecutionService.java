package com.houseofel.builder.job;

import com.houseofel.builder.gui.BuilderGui.Target;
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
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Runs the only wired-up task type so far: Clearing. The Helper actually walks to
 * each block, digs it, then moves on — so the pace comes from real travel time
 * rather than an arbitrary throttle. Skips anything not exposed to open sky, so it
 * clears surface material instead of tunnelling through terrain.
 */
public final class JobExecutionService {

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
     * TEMPORARY, FOR TESTING ONLY — inflates every drop to 10 stacks so storage fills
     * fast enough to exercise chest overflow in one short job. Set back to 1 for real
     * behaviour, otherwise a Helper mints hundreds of blocks out of thin air.
     */
    private static final int TEST_DROP_MULTIPLIER = 10 * 64;
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

    private final Plugin plugin;
    private final Logger logger;

    public JobExecutionService(Plugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    public void dispatchClear(Player player, NPC npc, Material tool, Target target,
                               Location pointA, Location pointB, boolean storeInChest,
                               boolean surfaceOnly) {
        Entity npcEntity = npc.getEntity();
        if (npcEntity == null) {
            player.sendMessage(Component.text("The Helper isn't spawned right now — can't start the job.", NamedTextColor.RED));
            return;
        }

        World world = pointA.getWorld();
        int minX = Math.min(pointA.getBlockX(), pointB.getBlockX());
        int minY = Math.min(pointA.getBlockY(), pointB.getBlockY());
        int minZ = Math.min(pointA.getBlockZ(), pointB.getBlockZ());
        int maxX = Math.max(pointA.getBlockX(), pointB.getBlockX());
        int maxY = Math.max(pointA.getBlockY(), pointB.getBlockY());
        int maxZ = Math.max(pointA.getBlockZ(), pointB.getBlockZ());

        int spanX = maxX - minX + 1;
        int spanY = maxY - minY + 1;
        int spanZ = maxZ - minZ + 1;
        long totalCells = (long) spanX * spanY * spanZ;

        TextDisplay label = spawnLabel(npcEntity.getLocation());
        EntityEquipment equipment = equipTool(npcEntity, tool);

        // Citizens defaults to a 25-block pathfinding range and simply gives up past it,
        // which strands the NPC on anything across a decent-sized site. 100 is the max.
        npc.getNavigator().getDefaultParameters()
                .speedModifier(WALK_SPEED_MODIFIER)
                .range(PATHFINDING_RANGE)
                .stuckAction(TeleportStuckAction.INSTANCE);

        player.sendMessage(Component.text(npc.getName() + " sets out to clear " + target.label()
                + " (" + totalCells + " blocks to check)...", NamedTextColor.GREEN));
        logger.info(player.getName() + " dispatched CLEAR/" + target + " job over " + totalCells + " cells");

        JobStorage storage = storeInChest
                ? new JobStorage(plugin, world, minX, maxX, minY, maxY, minZ, maxZ)
                : null;

        // Stand the chest up front rather than lazily on the first haul, so the player
        // can see where the job's output is going from the moment it starts.
        if (storage != null) {
            Location chestAt = storage.depositPoint();
            if (chestAt == null) {
                player.sendMessage(Component.text(
                        "Couldn't find anywhere to place a storage chest — " + npc.getName()
                                + " will work without one.", NamedTextColor.RED));
            } else {
                String coords = "(" + chestAt.getBlockX() + ", " + chestAt.getBlockY() + ", "
                        + chestAt.getBlockZ() + ")";
                player.sendMessage(Component.text("Storage chest placed at " + coords,
                        NamedTextColor.AQUA));
                logger.info("Storage chest placed at " + coords + " for " + player.getName() + "'s job");
            }
        }

        RegionOutline outline = new RegionOutline(world, minX, minY, minZ, maxX, maxY, maxZ);

        new ClearJobTask(player, npc, npcEntity, equipment, label, world, target,
                minX, maxY, minZ, spanX, spanZ, totalCells, tool, storage, surfaceOnly, outline).start();
    }

    private TextDisplay spawnLabel(Location npcLocation) {
        Location labelLocation = npcLocation.clone().add(0, LABEL_HEIGHT_OFFSET, 0);
        TextDisplay label = labelLocation.getWorld().spawn(labelLocation, TextDisplay.class);
        label.setBillboard(Display.Billboard.CENTER);
        label.text(Component.text("0%", NamedTextColor.YELLOW));
        return label;
    }

    /** Returns the entity's equipment if it can hold one, so the job can clear it when done. */
    private EntityEquipment equipTool(Entity npcEntity, Material tool) {
        if (!(npcEntity instanceof LivingEntity livingEntity)) {
            return null;
        }
        EntityEquipment equipment = livingEntity.getEquipment();
        if (equipment != null) {
            equipment.setItemInMainHand(new ItemStack(tool));
        }
        return equipment;
    }

    private enum Phase { SEEKING, WALKING, DIGGING, HAULING }

    private final class ClearJobTask {
        private final Player player;
        private final NPC npc;
        private final Entity npcEntity;
        private final EntityEquipment equipment;
        private final TextDisplay label;
        private final World world;
        private final Target target;
        private final int minX;
        private final int maxY;
        private final int minZ;
        private final int spanX;
        private final int spanZ;
        private final long totalCells;
        private final Material tool;
        private final JobStorage storage;
        private final boolean surfaceOnly;
        private final RegionOutline outline;
        private int outlineTicks;
        private final Map<Material, Integer> carried = new HashMap<>();
        private int carriedTotal;
        private Location depositPoint;

        private Phase phase = Phase.SEEKING;
        private long processedCells;
        private long clearedCells;
        private long deposited;
        private long clearedThisPass;
        private long skippedThisPass;
        private long skippedNoPath;
        private long skippedTimeout;
        private int passNumber = 1;
        private Block pendingBlock;
        private int walkTicks;
        private int noProgressTicks;
        private double closestApproachSquared;
        private int digTicks;
        private boolean usedStraightLine;
        private boolean jobComplete;
        private BukkitTask task;

        private ClearJobTask(Player player, NPC npc, Entity npcEntity, EntityEquipment equipment, TextDisplay label,
                              World world, Target target, int minX, int maxY, int minZ,
                              int spanX, int spanZ, long totalCells, Material tool, JobStorage storage,
                              boolean surfaceOnly, RegionOutline outline) {
            this.player = player;
            this.npc = npc;
            this.npcEntity = npcEntity;
            this.equipment = equipment;
            this.label = label;
            this.world = world;
            this.target = target;
            this.minX = minX;
            this.maxY = maxY;
            this.minZ = minZ;
            this.spanX = spanX;
            this.spanZ = spanZ;
            this.totalCells = totalCells;
            this.tool = tool;
            this.storage = storage;
            this.surfaceOnly = surfaceOnly;
            this.outline = outline;
        }

        private void start() {
            task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 0L, 1L);
        }

        private void tick() {
            if (!npcEntity.isValid()) {
                finish("The Helper disappeared mid-job — clearing stopped early.");
                return;
            }

            switch (phase) {
                case SEEKING -> seekNextBlock();
                case WALKING -> walkToPendingBlock();
                case DIGGING -> digPendingBlock();
                case HAULING -> haulToChest();
            }

            // Glow is the constant base and lingers, so it only needs redrawing about
            // once a second; the tint runs faster so the pulse reads as a smooth fade.
            if (outlineTicks % GLOW_TICK_PERIOD == 0) {
                outline.drawGlowNearby();
            }
            if (outlineTicks % TINT_TICK_PERIOD == 0) {
                outline.drawTintNearby(RegionOutline.WORKING_TINT, RegionOutline.tintSize(outlineTicks));
            }
            outlineTicks++;
            updateLabel();
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

            finish(npc.getName() + " finished clearing — " + clearedCells + " " + target.label()
                    + " block(s) cleared." + storedSuffix());
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
                digTicks = 0;
                phase = Phase.DIGGING;
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
                    digTicks = 0;
                    phase = Phase.DIGGING;
                    return;
                }
                skippedTimeout++;
                skippedThisPass++;
                abandonPendingBlock();
            }
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

            collectDrops(pendingBlock);
            pendingBlock.setType(Material.AIR);
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
                player.sendMessage(Component.text(
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
                player.sendMessage(Component.text(
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
            task.cancel();
            npc.getNavigator().cancelNavigation();
            label.remove();
            if (equipment != null) {
                equipment.setItemInMainHand(null);
            }
            player.sendMessage(Component.text(message, NamedTextColor.GREEN));
            logger.info(message + " [passes=" + passNumber + ", no-path-skips=" + skippedNoPath
                    + ", timeout-skips=" + skippedTimeout + "]");
        }
    }
}
