package com.houseofel.builder.job;

import com.houseofel.builder.antigrind.FreshLedger;
import com.houseofel.builder.antigrind.RedundancyTracker;
import com.houseofel.builder.npc.HelperLevelService;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Tracks every Helper's currently running or paused job, and is the single place that
 * knows how to save/resume them. One job per NPC — a second dispatch to the same
 * Helper isn't wired up yet, so this deliberately doesn't handle that case.
 *
 * <p>Chat commands arrive via Paper's async chat event, off the main thread — so
 * {@link #find} is safe to call from there (existence checks only), but {@link #pause},
 * {@link #resume}, and {@link #cancel} touch live NPC/entity state and must only ever
 * be called from the main thread. The registry itself is a {@link ConcurrentHashMap} so
 * that async existence-checking can never race the main thread's own reads/writes.
 */
public final class JobManager {

    /** What a pause/resume/cancel attempt actually did, so a caller can pick the right response. */
    public enum Outcome { OK, NOT_OWNER, ALREADY_IN_THAT_STATE }

    /**
     * How many jobs can tick at once, server-wide. A paused job costs nothing per tick,
     * so it doesn't count against this — only actively running ones do.
     */
    private static final int CONCURRENCY_CEILING = 3;

    private record ChunkKey(String world, int x, int z) { }

    private final Plugin plugin;
    private final Logger logger;
    private final JobStateStore store;
    private final PendingNotificationStore notifications;
    private final HelperLevelService levelService;
    private final RedundancyTracker redundancyTracker;
    private final FreshLedger freshLedger;
    private final Map<Integer, JobTask> jobs = new ConcurrentHashMap<>();
    /**
     * How many jobs currently want each chunk kept loaded. Paper's own chunk-ticket API
     * is keyed by (chunk, plugin), not (chunk, job) — with several jobs able to run at
     * once now, two jobs whose work happens to share a chunk would otherwise fight over
     * that one ticket, and whichever job moved on first would yank the chunk out from
     * under the other. Reference-counting here means the real ticket is only released
     * once nobody needs it anymore.
     */
    private final Map<ChunkKey, Integer> chunkRefCounts = new ConcurrentHashMap<>();

    public JobManager(Plugin plugin, HelperLevelService levelService, RedundancyTracker redundancyTracker,
                       FreshLedger freshLedger) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.store = new JobStateStore(plugin);
        this.notifications = new PendingNotificationStore(plugin);
        this.levelService = levelService;
        this.redundancyTracker = redundancyTracker;
        this.freshLedger = freshLedger;
    }

    /** Queues a message for a player who's currently offline — delivered the next time they log in. */
    void queueOfflineNotification(UUID playerId, String message) {
        notifications.add(playerId, message);
    }

    /** Delivers (and clears) whatever was queued while this player was away. */
    public void deliverPendingNotifications(Player player) {
        List<String> messages = notifications.takeAll(player.getUniqueId());
        for (String message : messages) {
            player.sendMessage(Component.text(message, NamedTextColor.GREEN));
        }
    }

    void register(JobTask task) {
        jobs.put(task.npc().getId(), task);
    }

    /** Called by a job itself once it cancels or completes naturally. */
    void onJobEnded(int npcId) {
        jobs.remove(npcId);
        store.delete(npcId);
    }

    /** A job wants this chunk kept loaded — only actually tickets it if nobody already has. */
    void requestChunk(World world, int chunkX, int chunkZ) {
        ChunkKey key = new ChunkKey(world.getName(), chunkX, chunkZ);
        if (chunkRefCounts.merge(key, 1, Integer::sum) == 1) {
            world.addPluginChunkTicket(chunkX, chunkZ, plugin);
        }
    }

    /** A job no longer needs this chunk — only actually releases it once nobody else does either. */
    void releaseChunk(World world, int chunkX, int chunkZ) {
        ChunkKey key = new ChunkKey(world.getName(), chunkX, chunkZ);
        Integer remaining = chunkRefCounts.computeIfPresent(key, (k, count) -> count > 1 ? count - 1 : null);
        if (remaining == null) {
            world.removePluginChunkTicket(chunkX, chunkZ, plugin);
        }
    }

    /** How many jobs are actively ticking right now — paused jobs don't count. */
    public int activeCount() {
        return (int) jobs.values().stream().filter(task -> !task.isPaused()).count();
    }

    public boolean isAtCeiling() {
        return activeCount() >= CONCURRENCY_CEILING;
    }

    /**
     * A rough, human-readable "check back in about..." for whichever running job is
     * closest to finishing — for turning a player away at the ceiling with something
     * more useful than "try later." Empty only if every tracked job is paused (their
     * finish time is indeterminate, so they're not considered).
     */
    public Optional<String> etaOfSoonestJob() {
        return jobs.values().stream()
                .filter(task -> !task.isPaused())
                .min(Comparator.comparingLong(JobTask::estimatedRemainingMillis))
                .map(task -> formatEta(task.estimatedRemainingMillis()));
    }

    private static String formatEta(long millis) {
        if (millis == Long.MAX_VALUE) {
            return "a little while";
        }
        long minutes = millis / 60_000;
        if (minutes < 1) {
            return "less than a minute";
        }
        if (minutes == 1) {
            return "about a minute";
        }
        return "about " + minutes + " minutes";
    }

    public JobTask find(int npcId) {
        return jobs.get(npcId);
    }

    public Outcome pause(int npcId, UUID requester) {
        JobTask task = jobs.get(npcId);
        if (task == null) {
            return Outcome.ALREADY_IN_THAT_STATE;
        }
        if (!task.playerId().equals(requester)) {
            return Outcome.NOT_OWNER;
        }
        if (task.isPaused()) {
            return Outcome.ALREADY_IN_THAT_STATE;
        }
        task.pause();
        store.save(task.toJobState());
        return Outcome.OK;
    }

    public Outcome resume(int npcId, UUID requester) {
        JobTask task = jobs.get(npcId);
        if (task == null) {
            return Outcome.ALREADY_IN_THAT_STATE;
        }
        if (!task.playerId().equals(requester)) {
            return Outcome.NOT_OWNER;
        }
        if (!task.isPaused()) {
            return Outcome.ALREADY_IN_THAT_STATE;
        }
        task.resumeTicking();
        return Outcome.OK;
    }

    public Outcome cancel(int npcId, UUID requester) {
        JobTask task = jobs.get(npcId);
        if (task == null) {
            return Outcome.ALREADY_IN_THAT_STATE;
        }
        if (!task.playerId().equals(requester)) {
            return Outcome.NOT_OWNER;
        }
        task.cancelJob();
        return Outcome.OK;
    }

    /** Snapshots every tracked job (running or paused) to disk — the restart safety net. */
    public void saveAllOnDisable() {
        for (JobTask task : jobs.values()) {
            store.save(task.toJobState());
        }
        logger.info("Saved " + jobs.size() + " in-progress job(s) for resume.");
    }

    /**
     * Resumes every job whose NPC is already spawned at boot. A job whose NPC isn't
     * available yet (chunk not loaded — nobody was nearby) is left on disk untouched;
     * bringing that case back is the offline-job problem, tracked separately as 1-D-3.
     * A static factory can't be part of {@link JobTask}, so this branches on the
     * persisted {@link JobType} to call the right concrete one.
     */
    public void resumeAllOnEnable() {
        int resumed = 0;
        int deferred = 0;
        for (JobState state : store.loadAll()) {
            NPC npc = CitizensAPI.getNPCRegistry().getById(state.npcId);
            JobTask task = npc == null ? null : switch (state.jobType) {
                case CLEAR -> ClearJobTask.resume(plugin, this, levelService, redundancyTracker, freshLedger, state, npc);
                case QUARRY -> QuarrymanJobTask.resume(plugin, this, levelService, redundancyTracker, freshLedger, state, npc);
                case LANDSCAPE -> LandscaperJobTask.resume(plugin, this, levelService, state, npc);
            };
            if (task == null) {
                deferred++;
                continue;
            }
            register(task);
            task.start();
            resumed++;
        }
        if (resumed > 0 || deferred > 0) {
            logger.info("Resumed " + resumed + " job(s); " + deferred + " deferred (NPC not yet available).");
        }
    }
}
