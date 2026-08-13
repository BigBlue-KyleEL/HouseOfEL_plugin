package com.houseofel.builder.death;

import com.houseofel.builder.npc.HelperLevelService;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.logging.Logger;

/**
 * Sweeps {@code helper_ledger_pending} on an interval; a Ledger left uncollected past the
 * window applies the rank-floor fallback ({@link HelperLevelService#applyRankFloor}) and
 * clears the pending state, whether or not the physical item still exists. Synchronous
 * (registered via {@code runTaskTimer}, NOT {@code runTaskTimerAsynchronously}) to match
 * {@code ToilDatabase}'s stated single-connection, main-thread-only SQLite access model —
 * the first singleton periodic DB-sweep task in this codebase; no existing pattern to
 * mirror.
 */
public final class LedgerExpiryTask implements Runnable {

    private static final long REAL_WINDOW_MILLIS = 7L * 24 * 60 * 60 * 1000;
    /**
     * TEMPORARY, FOR TESTING ONLY — swap to {@link #REAL_WINDOW_MILLIS} to observe expiry
     * live in a few minutes instead of 7 real days. MUST be reverted before calling Death
     * Policy done, same discipline as item 3's {@code GROUNDWORKER_TICKET_BLOCKS} knob.
     */
    private static final long WINDOW_MILLIS = REAL_WINDOW_MILLIS;
    private static final long CHECK_PERIOD_TICKS = 20L * 60; // once a minute

    private final DeathRecordStore store;
    private final HelperLevelService levelService;
    private final Logger logger;

    public LedgerExpiryTask(DeathRecordStore store, HelperLevelService levelService, Logger logger) {
        this.store = store;
        this.levelService = levelService;
        this.logger = logger;
    }

    public static BukkitTask start(Plugin plugin, DeathRecordStore store, HelperLevelService levelService) {
        LedgerExpiryTask task = new LedgerExpiryTask(store, levelService, plugin.getLogger());
        return Bukkit.getScheduler().runTaskTimer(plugin, task, CHECK_PERIOD_TICKS, CHECK_PERIOD_TICKS);
    }

    @Override
    public void run() {
        long now = System.currentTimeMillis();
        for (LedgerPending pending : store.allPending()) {
            if (now - pending.droppedAt() < WINDOW_MILLIS) {
                continue;
            }
            NPC npc = CitizensAPI.getNPCRegistry().getByUniqueId(pending.npcUuid());
            if (npc == null) {
                logger.warning("Ledger expired for NPC " + pending.npcUuid() + ", but it's no longer registered — "
                        + "clearing the pending row without applying the rank floor.");
            } else {
                levelService.applyRankFloor(npc);
            }
            store.clearPending(pending.npcUuid());
        }
    }
}
