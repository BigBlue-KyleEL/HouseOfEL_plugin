package com.houseofel.builder.region;

import com.houseofel.builder.gui.BuilderGui.Target;
import com.houseofel.builder.gui.BuilderGui.TaskType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Drives one Surveyor's Rod job end to end: hand-off, point marking, then the rod
 * "locks" once both points are set — further clicks confirm/cancel instead of
 * remarking. The rod is consumed on any terminal outcome. No job dispatch here.
 */
public final class RegionSelectionService {

    /** Safety cap per axis, to keep future FAWE jobs from lag-bombing the server. */
    private static final int MAX_DIMENSION = 48;
    private static final int TIMEOUT_TICKS = 20 * 20;
    private static final int PARTICLE_TICK_PERIOD = 20;

    private final Plugin plugin;
    private final Logger logger;
    private final SurveyorRod rod;
    private final Map<UUID, PendingJob> jobs = new HashMap<>();

    public RegionSelectionService(Plugin plugin, SurveyorRod rod) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.rod = rod;
    }

    /** Called once both task type and target are picked in the Helper NPC's GUI. */
    public void beginJob(Player player, TaskType taskType, Target target) {
        clearJob(player.getUniqueId());
        jobs.put(player.getUniqueId(), new PendingJob(taskType, target));
        rod.giveTo(player);
    }

    /** Left-click: marks point A while unlocked, or cancels once locked (awaiting confirmation). */
    public void onLeftClick(Player player, Location location) {
        PendingJob job = jobs.get(player.getUniqueId());
        if (job == null) {
            return;
        }
        if (job.locked()) {
            doCancel(player, job);
            return;
        }
        job.pointA = location;
        announce(player, "A", location);
        tryLock(player, job);
    }

    /** Right-click: marks point B while unlocked, or confirms once locked (awaiting confirmation). */
    public void onRightClick(Player player, Location location) {
        PendingJob job = jobs.get(player.getUniqueId());
        if (job == null) {
            return;
        }
        if (job.locked()) {
            doConfirm(player, job);
            return;
        }
        job.pointB = location;
        announce(player, "B", location);
        tryLock(player, job);
    }

    private void doConfirm(Player player, PendingJob job) {
        player.sendMessage(Component.text("Region confirmed for " + job.taskType.label() + " " + job.target.label()
                + " — logged, no job dispatched yet.", NamedTextColor.GREEN));
        logger.info(player.getName() + " confirmed " + job.taskType + "/" + job.target + " region "
                + describe(job.pointA) + " to " + describe(job.pointB));
        finish(player);
    }

    private void doCancel(Player player, PendingJob job) {
        player.sendMessage(Component.text("Region selection cancelled.", NamedTextColor.RED));
        finish(player);
    }

    private void announce(Player player, String label, Location location) {
        String coords = "(" + location.getBlockX() + ", " + location.getBlockY() + ", " + location.getBlockZ() + ")";
        player.sendMessage(Component.text("Point " + label + " set: " + coords, NamedTextColor.AQUA));
        logger.info(player.getName() + " marked point " + label + " at " + coords);
    }

    private void tryLock(Player player, PendingJob job) {
        if (job.pointA == null || job.pointB == null) {
            return;
        }

        int dx = Math.abs(job.pointB.getBlockX() - job.pointA.getBlockX()) + 1;
        int dy = Math.abs(job.pointB.getBlockY() - job.pointA.getBlockY()) + 1;
        int dz = Math.abs(job.pointB.getBlockZ() - job.pointA.getBlockZ()) + 1;

        if (dx > MAX_DIMENSION || dy > MAX_DIMENSION || dz > MAX_DIMENSION) {
            player.sendMessage(Component.text(
                    "Region too large (" + dx + " x " + dy + " x " + dz + ", max " + MAX_DIMENSION
                            + " per side). Rod consumed — visit the Helper again for a new one.", NamedTextColor.RED));
            finish(player);
            return;
        }

        job.particleTask = Bukkit.getScheduler().runTaskTimer(plugin,
                () -> drawBoxOutline(player, job.pointA, job.pointB), 0L, PARTICLE_TICK_PERIOD);
        job.timeoutTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            player.sendMessage(Component.text("Region confirmation timed out.", NamedTextColor.RED));
            finish(player);
        }, TIMEOUT_TICKS);

        player.sendMessage(Component.text(
                "Region marked: " + dx + " x " + dy + " x " + dz + " for " + job.taskType.label() + " "
                        + job.target.label() + ". Right-click to CONFIRM, left-click to CANCEL.",
                NamedTextColor.AQUA));
    }

    /** Secondary path for confirm/cancel, in case a player prefers typing over re-clicking the rod. */
    public void confirmPending(Player player) {
        PendingJob job = jobs.get(player.getUniqueId());
        if (job == null || !job.locked()) {
            player.sendMessage(Component.text("No pending region to confirm.", NamedTextColor.RED));
            return;
        }
        doConfirm(player, job);
    }

    public void cancelPending(Player player) {
        PendingJob job = jobs.get(player.getUniqueId());
        if (job == null || !job.locked()) {
            player.sendMessage(Component.text("No pending region to cancel.", NamedTextColor.RED));
            return;
        }
        doCancel(player, job);
    }

    /** Consumes the rod and clears all state for this player — one attempt per rod, success or not. */
    private void finish(Player player) {
        clearJob(player.getUniqueId());
        rod.removeAllFrom(player);
    }

    private void clearJob(UUID playerId) {
        PendingJob job = jobs.remove(playerId);
        if (job != null) {
            job.cancelScheduledTasks();
        }
    }

    private void drawBoxOutline(Player player, Location a, Location b) {
        double minX = Math.min(a.getBlockX(), b.getBlockX());
        double minY = Math.min(a.getBlockY(), b.getBlockY());
        double minZ = Math.min(a.getBlockZ(), b.getBlockZ());
        double maxX = Math.max(a.getBlockX(), b.getBlockX()) + 1;
        double maxY = Math.max(a.getBlockY(), b.getBlockY()) + 1;
        double maxZ = Math.max(a.getBlockZ(), b.getBlockZ()) + 1;

        for (double x = minX; x <= maxX; x += 1.0) {
            spawnEdgePoint(player, x, minY, minZ);
            spawnEdgePoint(player, x, minY, maxZ);
            spawnEdgePoint(player, x, maxY, minZ);
            spawnEdgePoint(player, x, maxY, maxZ);
        }
        for (double y = minY; y <= maxY; y += 1.0) {
            spawnEdgePoint(player, minX, y, minZ);
            spawnEdgePoint(player, minX, y, maxZ);
            spawnEdgePoint(player, maxX, y, minZ);
            spawnEdgePoint(player, maxX, y, maxZ);
        }
        for (double z = minZ; z <= maxZ; z += 1.0) {
            spawnEdgePoint(player, minX, minY, z);
            spawnEdgePoint(player, minX, maxY, z);
            spawnEdgePoint(player, maxX, minY, z);
            spawnEdgePoint(player, maxX, maxY, z);
        }
    }

    private void spawnEdgePoint(Player player, double x, double y, double z) {
        player.spawnParticle(Particle.END_ROD, x, y, z, 1, 0, 0, 0, 0);
    }

    private String describe(Location location) {
        return "(" + location.getBlockX() + ", " + location.getBlockY() + ", " + location.getBlockZ() + ")";
    }

    private static final class PendingJob {
        private final TaskType taskType;
        private final Target target;
        private Location pointA;
        private Location pointB;
        private BukkitTask particleTask;
        private BukkitTask timeoutTask;

        private PendingJob(TaskType taskType, Target target) {
            this.taskType = taskType;
            this.target = target;
        }

        private boolean locked() {
            return particleTask != null;
        }

        private void cancelScheduledTasks() {
            if (particleTask != null) {
                particleTask.cancel();
                particleTask = null;
            }
            if (timeoutTask != null) {
                timeoutTask.cancel();
                timeoutTask = null;
            }
        }
    }
}
