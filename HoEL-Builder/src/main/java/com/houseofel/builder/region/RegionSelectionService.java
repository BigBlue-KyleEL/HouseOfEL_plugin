package com.houseofel.builder.region;

import com.houseofel.builder.npc.BuilderNpcService;
import com.houseofel.builder.gui.Target;
import com.houseofel.builder.gui.TaskType;
import com.houseofel.builder.job.JobExecutionService;
import com.houseofel.builder.job.LandscapeBiome;
import com.houseofel.builder.job.LandscapeMode;
import net.citizensnpcs.api.npc.NPC;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.logging.Logger;

/**
 * Drives one Surveyor's Rod job end to end: hand-off, point marking, then a locked
 * region awaiting a "Yep"/"Wait" chat reply. Point marking is click-type-agnostic —
 * whichever click (or tap) comes first sets point A, the next sets point B — since
 * Bedrock's touch controls don't map cleanly onto discrete left/right clicks; a single
 * "hold" gesture there can fire both event types in quick succession, which is also why
 * clicks within {@link #DEBOUNCE_MILLIS} of each other collapse into one. The rod is
 * consumed on any terminal outcome. No job dispatch here.
 */
public final class RegionSelectionService {

    /** Safety cap on total blocks, to keep future FAWE jobs from lag-bombing the server. */
    private static final long MAX_VOLUME = 1_500_000L;
    /** Off for now while testing big regions — re-enable once a real duration is chosen. */
    private static final boolean TIMEOUT_ENABLED = false;
    private static final int TIMEOUT_TICKS = 20 * 20;
    private static final int PARTICLE_TICK_PERIOD = 20;
    /**
     * Clicks this close together are treated as one physical gesture, not two separate
     * points — a Bedrock "hold" can fire a left- and right-click event within a few
     * milliseconds of each other for the same tap.
     */
    private static final long DEBOUNCE_MILLIS = 300;

    private final Plugin plugin;
    private final Logger logger;
    private final SurveyorRod rod;
    private final JobExecutionService jobExecutionService;
    private final Map<UUID, PendingJob> jobs = new ConcurrentHashMap<>();
    private final Map<UUID, PendingChestSelection> chestSelections = new ConcurrentHashMap<>();

    public RegionSelectionService(Plugin plugin, SurveyorRod rod, JobExecutionService jobExecutionService) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.rod = rod;
        this.jobExecutionService = jobExecutionService;
    }

    /** Called once the job is configured and sent off from the Helper NPC's GUI. */
    public void beginJob(Player player, NPC npc, TaskType taskType, Target target,
                          boolean storeInChest, boolean surfaceOnly) {
        beginJob(player, npc, taskType, target, storeInChest, surfaceOnly, null, null);
    }

    /** Quarry-dispatch overload — carries the depth choice made before this area was ever marked. */
    public void beginJob(Player player, NPC npc, TaskType taskType, Target target, boolean storeInChest,
                          boolean surfaceOnly, Integer requestedLevels, Integer requestedTargetY) {
        clearJob(player.getUniqueId());
        clearChestSelection(player.getUniqueId());
        if (!rod.giveTo(player, BuilderNpcService.baseNameOf(npc))) {
            player.sendMessage(Component.text(
                    BuilderNpcService.baseNameOf(npc) + ": You can't even hold the Surveyor's Rod. Make space.",
                    NamedTextColor.RED));
            return;
        }
        jobs.put(player.getUniqueId(),
                new PendingJob(npc, taskType, target, storeInChest, surfaceOnly, requestedLevels, requestedTargetY));
    }

    public void beginLandscapeJob(Player player, NPC npc, LandscapeMode landscapeMode,
                                   LandscapeBiome landscapeBiome) {
        clearJob(player.getUniqueId());
        clearChestSelection(player.getUniqueId());
        if (!rod.giveTo(player, BuilderNpcService.baseNameOf(npc))) {
            player.sendMessage(Component.text(
                    BuilderNpcService.baseNameOf(npc) + ": You can't even hold the Surveyor's Rod. Make space.",
                    NamedTextColor.RED));
            return;
        }
        jobs.put(player.getUniqueId(),
                new PendingJob(npc, TaskType.LANDSCAPE, Target.DIRT, false, false, null, null,
                        landscapeMode, landscapeBiome));
    }

    public void beginCofferdamJob(Player player, NPC npc) {
        clearJob(player.getUniqueId());
        clearChestSelection(player.getUniqueId());
        if (!rod.giveTo(player, BuilderNpcService.baseNameOf(npc))) {
            player.sendMessage(Component.text(
                    BuilderNpcService.baseNameOf(npc) + ": You can't even hold the Surveyor's Rod. Make space.",
                    NamedTextColor.RED));
            return;
        }
        jobs.put(player.getUniqueId(),
                new PendingJob(npc, TaskType.COFFERDAM, Target.DIRT, false, false, null, null,
                        null, null));
    }

    /**
     * Enters chest-selection mode: gives the player back the Surveyor's Rod so they can
     * tap an existing chest after confirming the area for any chest-backed job.
     */
    public void beginChestSelection(Player player, NPC npc, Predicate<Block> callback) {
        clearChestSelection(player.getUniqueId());
        if (!rod.giveTo(player, BuilderNpcService.baseNameOf(npc))) {
            player.sendMessage(Component.text(
                    BuilderNpcService.baseNameOf(npc)
                            + ": You can't even hold the Surveyor's Rod. Make space.",
                    NamedTextColor.RED));
            return;
        }
        chestSelections.put(player.getUniqueId(), new PendingChestSelection(npc, callback));
    }

    /**
     * Any click/tap while unlocked marks whichever point isn't set yet — point A first,
     * then point B. Once locked, clicks do nothing; confirming or cancelling happens via
     * a "Yep"/"Wait" chat reply (see {@link RegionConfirmListener}) or the equivalent
     * {@code /builder confirm}/{@code /builder cancel} commands, both of which work
     * identically regardless of platform.
     */
    public void onClick(Player player, Location location) {
        PendingChestSelection chestSel = chestSelections.get(player.getUniqueId());
        if (chestSel != null) {
            handleChestClick(player, location, chestSel);
            return;
        }

        PendingJob job = jobs.get(player.getUniqueId());
        if (job == null || job.locked()) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now - job.lastClickMillis < DEBOUNCE_MILLIS) {
            return;
        }
        job.lastClickMillis = now;

        if (job.pointA == null) {
            job.pointA = location;
            announce(player, "A", location);
        } else {
            job.pointB = location;
            announce(player, "B", location);
        }
        tryLock(player, job);
    }

    private void handleChestClick(Player player, Location location, PendingChestSelection sel) {
        Block block = location.getBlock();
        if (block.getType() != Material.CHEST && block.getType() != Material.TRAPPED_CHEST) {
            player.sendMessage(Component.text(BuilderNpcService.baseNameOf(sel.npc)
                    + ": That's not a chest. Place one down and tap it.",
                    NamedTextColor.RED));
            return;
        }
        if (!sel.callback.test(block)) return;
        chestSelections.remove(player.getUniqueId());
        rod.removeAllFrom(player);
        logger.info(player.getName() + " selected chest at " + describe(location)
                + " for " + BuilderNpcService.baseNameOf(sel.npc));
    }

    private void doConfirm(Player player, PendingJob job) {
        logger.info(player.getName() + " confirmed " + job.taskType + "/" + job.target + " region "
                + describe(job.pointA) + " to " + describe(job.pointB));

        if (job.taskType == TaskType.CLEAR) {
            Location pointA = job.pointA;
            Location pointB = job.pointB;
            NPC npc = job.npc;
            finish(player);
            Predicate<Block> needsChest = jobExecutionService.dispatchClear(player, npc,
                    job.taskType, job.target, pointA, pointB, job.storeInChest,
                    job.surfaceOnly, false);
            if (needsChest != null) {
                beginChestSelection(player, npc, needsChest);
            }
            return;
        }

        if (job.taskType == TaskType.LANDSCAPE) {
            Location pointA = job.pointA;
            Location pointB = job.pointB;
            LandscapeMode mode = job.landscapeMode != null ? job.landscapeMode : LandscapeMode.FILL;
            finish(player);
            jobExecutionService.dispatchLandscape(player, job.npc, pointA, pointB, mode, job.landscapeBiome);
            return;
        }

        if (job.taskType == TaskType.QUARRY) {
            Location pointA = job.pointA;
            Location pointB = job.pointB;
            NPC npc = job.npc;
            finish(player);
            Predicate<Block> needsChest = jobExecutionService.dispatchQuarryman(player, npc,
                    job.taskType, job.target, pointA, pointB, job.storeInChest,
                    job.surfaceOnly, job.requestedLevels, job.requestedTargetY);
            if (needsChest != null) {
                beginChestSelection(player, npc, needsChest);
            }
            return;
        }

        if (job.taskType == TaskType.COFFERDAM) {
            Location pointA = job.pointA;
            Location pointB = job.pointB;
            NPC npc = job.npc;
            finish(player);
            Predicate<Block> needsChest = jobExecutionService.dispatchCofferdam(player, npc,
                    pointA, pointB);
            if (needsChest != null) {
                beginChestSelection(player, npc, needsChest);
            }
            return;
        }

        if (job.taskType == TaskType.SHAFT_MINER) {
            Location pointA = job.pointA;
            Location pointB = job.pointB;
            NPC npc = job.npc;
            finish(player);
            Predicate<Block> needsChest = jobExecutionService.dispatchShaftMiner(player, npc,
                    pointA, pointB, job.requestedLevels, job.requestedTargetY);
            if (needsChest != null) {
                beginChestSelection(player, npc, needsChest);
            }
            return;
        }

        player.sendMessage(Component.text("Region confirmed for " + job.taskType.label() + " " + job.target.label()
                + " — logged, but only Clearing is wired up to actually run so far.", NamedTextColor.GREEN));
        finish(player);
    }

    private void doCancel(Player player, PendingJob job) {
        player.sendMessage(Component.text(
                BuilderNpcService.baseNameOf(job.npc) + " lowers the rod. Come find me again when you're ready for a new job.",
                NamedTextColor.RED));
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
        long volume = (long) dx * dy * dz;

        if (volume > MAX_VOLUME) {
            player.sendMessage(Component.text(
                    "Region too large (" + dx + " x " + dy + " x " + dz + " = " + volume + " blocks, max "
                            + MAX_VOLUME + "). Rod consumed — visit " + BuilderNpcService.baseNameOf(job.npc) + " again for a new one.",
                    NamedTextColor.RED));
            logger.info(player.getName() + " rejected: region " + dx + "x" + dy + "x" + dz + " (" + volume
                    + " blocks) exceeds " + MAX_VOLUME);
            finish(player);
            return;
        }

        job.particleTask = Bukkit.getScheduler().runTaskTimer(plugin,
                () -> drawBoxOutline(player, job.pointA, job.pointB), 0L, PARTICLE_TICK_PERIOD);
        if (TIMEOUT_ENABLED) {
            job.timeoutTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
                player.sendMessage(Component.text("Region confirmation timed out.", NamedTextColor.RED));
                logger.info(player.getName() + " region confirmation timed out");
                finish(player);
            }, TIMEOUT_TICKS);
        }

        String jobLabel = job.taskType == TaskType.LANDSCAPE
                ? job.taskType.label()
                : job.taskType.label() + " " + job.target.label();
        player.sendMessage(Component.text(
                BuilderNpcService.baseNameOf(job.npc) + " looks over the marked area — " + dx + " x " + dy + " x " + dz + " ("
                        + volume + " blocks) for " + jobLabel
                        + ". Say \"Yep\" to begin, or \"Wait\" to call it off.",
                NamedTextColor.AQUA));
    }

    /** Thread-safe existence check for the async chat listener — see {@link RegionConfirmListener}. */
    public boolean hasPending(UUID playerId) {
        return jobs.containsKey(playerId) || chestSelections.containsKey(playerId);
    }

    /** Confirm/cancel entry point for both the "Yep"/"Wait" chat reply and the /builder command fallback. */
    public void confirmPending(Player player) {
        if (chestSelections.containsKey(player.getUniqueId())) {
            player.sendMessage(Component.text(
                    "Tap a chest with the rod to select it.", NamedTextColor.YELLOW));
            return;
        }
        PendingJob job = jobs.get(player.getUniqueId());
        if (job == null || !job.locked()) {
            player.sendMessage(Component.text("No pending region to confirm.", NamedTextColor.RED));
            return;
        }
        doConfirm(player, job);
    }

    public void cancelPending(Player player) {
        PendingChestSelection chestSel = chestSelections.remove(player.getUniqueId());
        if (chestSel != null) {
            rod.removeAllFrom(player);
            player.sendMessage(Component.text(BuilderNpcService.baseNameOf(chestSel.npc)
                    + " lowers the rod. Come find me again when you're ready for a new job.",
                    NamedTextColor.RED));
            return;
        }
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

    private void clearChestSelection(UUID playerId) {
        chestSelections.remove(playerId);
    }

    private void drawBoxOutline(Player player, Location a, Location b) {
        new RegionOutline(a.getWorld(),
                Math.min(a.getBlockX(), b.getBlockX()),
                Math.min(a.getBlockY(), b.getBlockY()),
                Math.min(a.getBlockZ(), b.getBlockZ()),
                Math.max(a.getBlockX(), b.getBlockX()),
                Math.max(a.getBlockY(), b.getBlockY()),
                Math.max(a.getBlockZ(), b.getBlockZ())
        ).drawGlowFor(player);
    }

    private String describe(Location location) {
        return "(" + location.getBlockX() + ", " + location.getBlockY() + ", " + location.getBlockZ() + ")";
    }

    private static final class PendingJob {
        private final NPC npc;
        private final TaskType taskType;
        private final Target target;
        private final boolean storeInChest;
        private final boolean surfaceOnly;
        /** Quarry-only depth choice, made before this area was even marked — see JobExecutionService.dispatchQuarryman for how these resolve. Both null for every other TaskType. */
        private final Integer requestedLevels;
        private final Integer requestedTargetY;
        private final LandscapeMode landscapeMode;
        private final LandscapeBiome landscapeBiome;
        private Location pointA;
        private Location pointB;
        private long lastClickMillis;
        private BukkitTask particleTask;
        private BukkitTask timeoutTask;

        private PendingJob(NPC npc, TaskType taskType, Target target, boolean storeInChest, boolean surfaceOnly,
                            Integer requestedLevels, Integer requestedTargetY) {
            this(npc, taskType, target, storeInChest, surfaceOnly, requestedLevels, requestedTargetY, null, null);
        }

        private PendingJob(NPC npc, TaskType taskType, Target target, boolean storeInChest, boolean surfaceOnly,
                            Integer requestedLevels, Integer requestedTargetY,
                            LandscapeMode landscapeMode, LandscapeBiome landscapeBiome) {
            this.npc = npc;
            this.taskType = taskType;
            this.target = target;
            this.storeInChest = storeInChest;
            this.surfaceOnly = surfaceOnly;
            this.requestedLevels = requestedLevels;
            this.requestedTargetY = requestedTargetY;
            this.landscapeMode = landscapeMode;
            this.landscapeBiome = landscapeBiome;
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

    private static final class PendingChestSelection {
        private final NPC npc;
        private final Predicate<Block> callback;

        private PendingChestSelection(NPC npc, Predicate<Block> callback) {
            this.npc = npc;
            this.callback = callback;
        }
    }
}
