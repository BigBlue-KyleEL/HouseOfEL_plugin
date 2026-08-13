package com.houseofel.builder.job;

import com.houseofel.builder.gui.Target;
import com.houseofel.builder.gui.TaskType;
import com.houseofel.builder.npc.HelperLevelService;
import com.houseofel.builder.region.RegionOutline;
import net.citizensnpcs.api.npc.NPC;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.plugin.Plugin;

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

    public JobExecutionService(Plugin plugin, JobManager jobManager, HelperLevelService levelService) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.jobManager = jobManager;
        this.levelService = levelService;
    }

    public void dispatchClear(Player player, NPC npc, TaskType taskType, Target target,
                               Location pointA, Location pointB, boolean storeInChest,
                               boolean surfaceOnly) {
        Material tool = taskType.tool();
        Entity npcEntity = npc.getEntity();
        if (npcEntity == null) {
            player.sendMessage(Component.text(
                    npc.getName() + " isn't spawned right now — can't start the job.", NamedTextColor.RED));
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

        World world = pointA.getWorld();
        // Warning-only per the Masterfile — no restriction, the job proceeds regardless.
        if (world.getEnvironment() == World.Environment.NETHER
                || world.getEnvironment() == World.Environment.THE_END) {
            player.sendMessage(Component.text(
                    npc.getName() + ": Rough territory out here — I'll be careful, but you might want to keep an eye on me.",
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
        EntityEquipment equipment = ClearJobTask.equipTool(npcEntity, tool, npc.getName(), taskType.toolNoun());

        player.sendMessage(Component.text(npc.getName() + ": Right, I'll get started on the "
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

        ClearJobTask task = new ClearJobTask(plugin, jobManager, levelService, player, npc,
                npcEntity, equipment, label, world, target, tool, minX, maxX, minY, maxY, minZ, maxZ, spanX, spanZ,
                totalCells, storage, storeInChest, surfaceOnly, outline);
        jobManager.register(task);
        task.start();
    }
}
