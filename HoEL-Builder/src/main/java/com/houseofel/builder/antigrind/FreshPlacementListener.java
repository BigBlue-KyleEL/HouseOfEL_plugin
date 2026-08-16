package com.houseofel.builder.antigrind;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;

/** Feeds {@link FreshLedger} — the only {@code BlockPlaceEvent} listener in this codebase before now. */
public final class FreshPlacementListener implements Listener {

    private final FreshLedger freshLedger;

    public FreshPlacementListener(FreshLedger freshLedger) {
        this.freshLedger = freshLedger;
    }

    @EventHandler
    public void onPlace(BlockPlaceEvent event) {
        freshLedger.recordPlacement(event.getBlockPlaced(), System.currentTimeMillis());
    }
}
