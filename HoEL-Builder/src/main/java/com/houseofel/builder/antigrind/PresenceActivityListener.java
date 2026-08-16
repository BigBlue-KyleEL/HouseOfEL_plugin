package com.houseofel.builder.antigrind;

import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.Plugin;

/**
 * Feeds {@link PresenceTracker} — move/break/place/inventory-open/chat, per the framework's
 * exact "active" definition. Deliberately a separate listener from
 * {@link FreshPlacementListener} even though both handle {@code BlockPlaceEvent} — matches
 * this codebase's existing precedent of independent listeners sharing an event type for
 * unrelated purposes ({@code HelperStatusListener}/{@code HelperDeathListener} both already
 * handle {@code AsyncChatEvent}).
 */
public final class PresenceActivityListener implements Listener {

    private final Plugin plugin;
    private final PresenceTracker presenceTracker;

    public PresenceActivityListener(Plugin plugin, PresenceTracker presenceTracker) {
        this.plugin = plugin;
        this.presenceTracker = presenceTracker;
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        // Only a real position change counts — otherwise this fires on every look-around.
        if (event.getTo() == null
                || (event.getFrom().getBlockX() == event.getTo().getBlockX()
                        && event.getFrom().getBlockY() == event.getTo().getBlockY()
                        && event.getFrom().getBlockZ() == event.getTo().getBlockZ())) {
            return;
        }
        presenceTracker.recordActivity(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onBreak(BlockBreakEvent event) {
        presenceTracker.recordActivity(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onPlace(BlockPlaceEvent event) {
        presenceTracker.recordActivity(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player) {
            presenceTracker.recordActivity(player.getUniqueId());
        }
    }

    @EventHandler
    public void onChat(AsyncChatEvent event) {
        // Fires off the main thread — PresenceTracker's map isn't synchronized, so hop
        // before touching it, same reason HelperStatusListener/HelperDeathListener do.
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> presenceTracker.recordActivity(player.getUniqueId()));
    }
}
