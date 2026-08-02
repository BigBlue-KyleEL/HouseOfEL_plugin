package com.houseofel.builder.job;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.plugin.Plugin;

import java.util.Map;
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

    private final Plugin plugin;
    private final Logger logger;
    private final JobStateStore store;
    private final Map<Integer, ClearJobTask> jobs = new ConcurrentHashMap<>();

    public JobManager(Plugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.store = new JobStateStore(plugin);
    }

    void register(ClearJobTask task) {
        jobs.put(task.npc().getId(), task);
    }

    /** Called by a job itself once it cancels or completes naturally. */
    void onJobEnded(int npcId) {
        jobs.remove(npcId);
        store.delete(npcId);
    }

    public ClearJobTask find(int npcId) {
        return jobs.get(npcId);
    }

    public Outcome pause(int npcId, UUID requester) {
        ClearJobTask task = jobs.get(npcId);
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
        ClearJobTask task = jobs.get(npcId);
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
        ClearJobTask task = jobs.get(npcId);
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
        for (ClearJobTask task : jobs.values()) {
            store.save(task.toJobState());
        }
        logger.info("Saved " + jobs.size() + " in-progress job(s) for resume.");
    }

    /**
     * Resumes every job whose NPC is already spawned at boot. A job whose NPC isn't
     * available yet (chunk not loaded — nobody was nearby) is left on disk untouched;
     * bringing that case back is the offline-job problem, tracked separately as 1-D-3.
     */
    public void resumeAllOnEnable() {
        int resumed = 0;
        int deferred = 0;
        for (JobState state : store.loadAll()) {
            NPC npc = CitizensAPI.getNPCRegistry().getById(state.npcId);
            ClearJobTask task = npc == null ? null : ClearJobTask.resume(plugin, this, state, npc);
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
