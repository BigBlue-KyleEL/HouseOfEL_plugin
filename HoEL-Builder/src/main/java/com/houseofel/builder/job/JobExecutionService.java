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

    public void dispatchClear(Player player, NPC npc, TaskType taskType, Target target,
                               Location pointA, Location pointB, boolean storeInChest,
                               boolean surfaceOnly, boolean restoreTopsoil) {
        // Purely a cosmetic starting point — ClearJobTask.beginDigging() picks the real
        // per-block tool (see BlockTool) the moment it reaches its first block, so
        // whatever's held here is only ever visible for a step or two at most.
        Material initialTool = Material.IRON_SHOVEL;
        Entity npcEntity = npc.getEntity();
        if (npcEntity == null) {
            player.sendMessage(Component.text(
                    BuilderNpcService.baseNameOf(npc) + " isn't spawned right now — can't start the job.", NamedTextColor.RED));
            return;
        }
        // Belt-and-suspenders: BuilderNpcListener already turns players away at the door
        // when the ceiling is full, but the gap between opening that menu and hitting
        // confirm is real — another job could have started in between.
        if (jobManager.isAtCeiling()) {
            String eta = jobManager.etaOfSoonestJob().orElse("a little while");
            player.sendMessage(Component.text(
                    "Every Helper is tied up right now — check back in " + eta + ".", NamedTextColor.RED));
            return;
        }
        // Same belt-and-suspenders reasoning, added 2026-08-21: this NPC-specific check
        // didn't exist before Quarryman shared JobManager's own registry — a latent
        // double-dispatch race (this NPC already mid-Quarry-job) that a single shared
        // find() now makes trivial to close here too.
        if (jobManager.find(npc.getId()) != null) {
            player.sendMessage(Component.text(
                    BuilderNpcService.baseNameOf(npc) + " is already busy — wait for that to finish first.", NamedTextColor.RED));
            return;
        }

        // Ownership (for Rust visibility and recruitment-cost escalation) now tracks
        // whoever's actually operating a Helper, not just whoever originally recruited
        // it — reassigned to the dispatching player on every real dispatch, so a
        // household where several people run Helpers attributes recklessness to whoever's
        // actually responsible for it, per Kyle's call (2026-08-14).
        deathRecordStore.setOwner(npc.getUniqueId(), player.getUniqueId());

        World world = pointA.getWorld();
        // Warning-only per the Masterfile — no restriction, the job proceeds regardless.
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
        int spanY = maxY - minY + 1;
        int spanZ = maxZ - minZ + 1;
        long totalCells = (long) spanX * spanY * spanZ;

        TextDisplay label = ClearJobTask.spawnLabel(npcEntity.getLocation());
        EntityEquipment equipment = ClearJobTask.equipTool(npcEntity, initialTool, BuilderNpcService.baseNameOf(npc), taskType.toolNoun());

        player.sendMessage(Component.text(BuilderNpcService.baseNameOf(npc) + ": Right, I'll get started on the "
                + target.label() + " — " + totalCells + " blocks to check.", NamedTextColor.GREEN));
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
                        "Couldn't find anywhere to place a storage chest — " + BuilderNpcService.baseNameOf(npc)
                                + " will work without one.", NamedTextColor.RED));
            } else {
                String coords = "(" + chestAt.getBlockX() + ", " + chestAt.getBlockY() + ", "
                        + chestAt.getBlockZ() + ")";
                player.sendMessage(Component.text("Storage chest placed at " + coords,
                        NamedTextColor.AQUA));
                logger.info("Storage chest placed at " + coords + " for " + player.getName() + "'s job");
            }
        }

        // Whether THIS job restores topsoil comes from which button was pressed
        // (Landscaping vs plain Clearing), not from the Helper's class — a Landscaper can
        // still be asked for a plain clear.
        boolean restoresTopsoil = restoreTopsoil;
        // The depth cap, by contrast, is a property of the CLASS, not the job: "It cannot
        // dig deep — hand it a quarry and it refuses and says so" (V1 Perk Ladders,
        // Landscaper's own balance clause). Applied to any job a Landscaper takes, so the
        // cap cannot be side-stepped by using the plain Clearing button.
        if (isLandscaper(npc) && (maxY - minY + 1) > LANDSCAPER_MAX_DEPTH) {
            player.sendMessage(Component.text(BuilderNpcService.baseNameOf(npc)
                    + ": That's a quarry, not landscaping — I work the surface, no deeper than "
                    + LANDSCAPER_MAX_DEPTH + " blocks. Mark something shallower and I'll make it look like it grew there.",
                    NamedTextColor.RED));
            return;
        }

        RegionOutline outline = new RegionOutline(world, minX, minY, minZ, maxX, maxY, maxZ);

        ClearJobTask task = new ClearJobTask(plugin, jobManager, levelService, redundancyTracker, freshLedger,
                player, npc, npcEntity, equipment, label, world, target, initialTool, minX, maxX, minY, maxY, minZ, maxZ,
                spanX, spanZ, totalCells, storage, storeInChest, surfaceOnly, outline, restoresTopsoil);
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
    public void dispatchQuarryman(Player player, NPC npc, TaskType taskType, Target target,
                                   Location pointA, Location pointB, boolean storeInChest,
                                   boolean surfaceOnly, Integer requestedLevels, Integer requestedTargetY) {
        Material initialTool = Material.IRON_PICKAXE;
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

        int minX = Math.min(pointA.getBlockX(), pointB.getBlockX());
        int maxX = Math.max(pointA.getBlockX(), pointB.getBlockX());
        int minZ = Math.min(pointA.getBlockZ(), pointB.getBlockZ());
        int maxZ = Math.max(pointA.getBlockZ(), pointB.getBlockZ());
        // The lower of the two marked points' Y is deliberately ignored — depth is now
        // always an explicit player choice (Level or Coordinates, picked before this area
        // was even marked), never inferred from the marked volume's own height.
        int topY = Math.max(pointA.getBlockY(), pointB.getBlockY());
        // Which axis the staircase's footprint slides along as it goes deeper, and which
        // way — Kyle's own rule (2026-08-21), cross-validated against 8 worked examples
        // across two differently-shaped marked areas (not an assumption: every one of the
        // 8 checks out exactly). Compare the click's own X delta to its own Z delta, not
        // either axis's span and not a fixed axis: same sign (point B is diagonally
        // "ahead" of point A along the X=Z line — both coordinates moved the same way)
        // steps along X, using that shared sign. Opposite signs (point B is diagonally
        // "ahead" along the X=-Z line) steps along Z, using Z's own sign. Defaults to +1
        // for the degenerate all-zero case (no real diagonal to read a direction from)
        // rather than leaving the footprint direction undefined.
        int dx = pointB.getBlockX() - pointA.getBlockX();
        int dz = pointB.getBlockZ() - pointA.getBlockZ();
        boolean stepsAlongX = Integer.signum(dx) == Integer.signum(dz);
        int stepDirection = stepsAlongX ? Integer.signum(dx) : Integer.signum(dz);
        if (stepDirection == 0) {
            stepDirection = 1;
        }

        // Coordinates mode gives a target Y directly; Level mode gives a block count
        // directly. Both resolve to the same requestedDepth. Kyle's explicit call,
        // 2026-08-20: the marked footprint's own size is NOT a constraint on depth — any
        // footprint can reach any depth (see buildDigOrder's own doc for how a footprint
        // too narrow for a full graduated staircase still digs safely, as a shaft
        // instead). The one real, physically-necessary check is Coordinates mode's own
        // target genuinely being below the marked area — "that doesn't make sense",
        // Kyle's own words — plus not digging below the world's own floor.
        int requestedDepth;
        if (requestedTargetY != null) {
            if (requestedTargetY >= topY) {
                player.sendMessage(Component.text(BuilderNpcService.baseNameOf(npc)
                        + ": Y coordinate destination and work area is invalid — " + requestedTargetY
                        + " isn't below where you marked (top is " + topY + ").", NamedTextColor.RED));
                return;
            }
            requestedDepth = topY - requestedTargetY + 1;
        } else if (requestedLevels != null) {
            requestedDepth = requestedLevels;
        } else {
            // Defensive only — both the Java wizard and the Bedrock form always resolve
            // one or the other before a Quarry dispatch ever reaches here.
            requestedDepth = 1;
        }

        if (requestedDepth < 1) {
            player.sendMessage(Component.text(BuilderNpcService.baseNameOf(npc)
                    + ": Can't dig a negative number of blocks — ask for at least 1.", NamedTextColor.RED));
            return;
        }
        int bottomY = topY - requestedDepth + 1;
        if (bottomY < world.getMinHeight()) {
            player.sendMessage(Component.text(BuilderNpcService.baseNameOf(npc)
                    + ": That goes below the bottom of the world (Y " + world.getMinHeight()
                    + ") — ask for a shallower depth.", NamedTextColor.RED));
            return;
        }

        Deque<Block> digOrder = QuarrymanJobTask.buildDigOrder(world, minX, maxX, minZ, maxZ, topY, requestedDepth,
                stepsAlongX, stepDirection);

        TextDisplay label = ClearJobTask.spawnLabel(npcEntity.getLocation());
        EntityEquipment equipment = ClearJobTask.equipTool(npcEntity, initialTool, BuilderNpcService.baseNameOf(npc), taskType.toolNoun());

        player.sendMessage(Component.text(BuilderNpcService.baseNameOf(npc) + ": Right, I'll step this down as I go "
                + "— one block deeper each row, " + requestedDepth + " block(s) deep by the far end. "
                + digOrder.size() + " blocks to check.", NamedTextColor.GREEN));
        logger.info(player.getName() + " dispatched QUARRY job over " + digOrder.size() + " cells, depth "
                + requestedDepth);

        JobStorage storage = storeInChest
                ? new JobStorage(plugin, world, minX, maxX, bottomY, topY, minZ, maxZ)
                : null;

        if (storage != null) {
            Location chestAt = storage.depositPoint();
            if (chestAt == null) {
                player.sendMessage(Component.text(
                        "Couldn't find anywhere to place a storage chest — " + BuilderNpcService.baseNameOf(npc)
                                + " will work without one.", NamedTextColor.RED));
            } else {
                String coords = "(" + chestAt.getBlockX() + ", " + chestAt.getBlockY() + ", "
                        + chestAt.getBlockZ() + ")";
                player.sendMessage(Component.text("Storage chest placed at " + coords, NamedTextColor.AQUA));
                logger.info("Storage chest placed at " + coords + " for " + player.getName() + "'s job");
            }
        }

        RegionOutline outline = new RegionOutline(world, minX, bottomY, minZ, maxX, topY, maxZ);

        QuarrymanJobTask task = new QuarrymanJobTask(plugin, jobManager, levelService, redundancyTracker,
                freshLedger, player.getUniqueId(), npc,
                npcEntity, equipment, label, world, initialTool, minX, maxX, minZ, maxZ, topY, requestedDepth,
                stepsAlongX, stepDirection, digOrder, storage, outline);
        jobManager.register(task);
        task.start();
    }
}
