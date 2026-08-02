package com.houseofel.builder.job;

import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.plugin.Plugin;

/**
 * Tracks which block positions a player has placed something at, server-wide, going
 * forward from whenever this feature first ran — there's no way to know about builds
 * that already existed before, since Minecraft doesn't record placement provenance on
 * the block itself.
 */
public final class PlayerPlacementTracker implements Listener {

    private final PlayerPlacementStore store;

    public PlayerPlacementTracker(Plugin plugin) {
        this.store = new PlayerPlacementStore(plugin);
    }

    @EventHandler
    public void onPlace(BlockPlaceEvent event) {
        Block block = event.getBlock();
        store.markPlaced(block.getWorld(), block.getX(), block.getY(), block.getZ());
    }

    @EventHandler
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        store.clear(block.getWorld(), block.getX(), block.getY(), block.getZ());
    }

    boolean isPlayerPlaced(Block block) {
        return store.isMarked(block.getWorld(), block.getX(), block.getY(), block.getZ());
    }

    /** Called by a job when it clears a tracked block directly — that bypasses BlockBreakEvent entirely. */
    void forget(Block block) {
        store.clear(block.getWorld(), block.getX(), block.getY(), block.getZ());
    }
}
