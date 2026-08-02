package com.houseofel.builder.region;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

/**
 * Lets a player confirm or cancel a locked region with a plain "Yep"/"Wait" chat reply,
 * so the flow works identically whether they're clicking on Java or tapping on Bedrock.
 * Mirrors {@link com.houseofel.builder.npc.HelperCommandListener}'s threading approach:
 * the async chat event only does a cheap, thread-safe existence check before cancelling
 * the message; the actual confirm/cancel — which can dispatch a job — always runs on
 * the main thread.
 */
public final class RegionConfirmListener implements Listener {

    private final Plugin plugin;
    private final RegionSelectionService regionService;

    public RegionConfirmListener(Plugin plugin, RegionSelectionService regionService) {
        this.plugin = plugin;
        this.regionService = regionService;
    }

    @EventHandler
    public void onChat(AsyncChatEvent event) {
        String message = PlainTextComponentSerializer.plainText().serialize(event.message()).trim().toLowerCase();
        boolean confirm = message.equals("yep");
        boolean cancel = !confirm && message.equals("wait");
        if (!confirm && !cancel) {
            return;
        }

        Player player = event.getPlayer();
        if (!regionService.hasPending(player.getUniqueId())) {
            return;
        }

        event.setCancelled(true);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (confirm) {
                regionService.confirmPending(player);
            } else {
                regionService.cancelPending(player);
            }
        });
    }
}
