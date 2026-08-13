package com.houseofel.builder.npc;

import net.citizensnpcs.api.event.CitizensEnableEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

/**
 * Runs a one-shot callback once Citizens has actually loaded its NPC registry.
 *
 * <p>Citizens loads NPCs AFTER dependent plugins finish enabling — the boot log shows
 * "[Citizens] Loaded N NPCs" well after "[HoEL-Builder] enabled" — so anything at
 * {@code onEnable} time that iterates {@code CitizensAPI.getNPCRegistry()} silently sees
 * an empty registry and does nothing. That already cost one real bug (Helper base names
 * never being captured, so earned titles compounded onto each other on every level-up),
 * and {@code JobManager}'s own "NPC not yet available" deferral exists for the same
 * reason.
 */
public final class CitizensReadyListener implements Listener {

    private final Runnable callback;
    private boolean fired;

    public CitizensReadyListener(Runnable callback) {
        this.callback = callback;
    }

    @EventHandler
    public void onCitizensEnabled(CitizensEnableEvent event) {
        // Guarded rather than unregistered — the event also fires on a Citizens reload,
        // and re-running the callback then would be redundant, not harmful.
        if (fired) {
            return;
        }
        fired = true;
        callback.run();
    }
}
