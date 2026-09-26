package com.houseofel.core.gui;

import com.houseofel.common.net.CloseScreenPayload;
import com.houseofel.common.net.DispatchPayload;
import com.houseofel.common.net.GuiConstants;
import com.houseofel.common.net.OpenScreenPayload;
import com.houseofel.common.net.ScreenClosedPayload;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public final class ScreenService implements PluginMessageListener, Listener {

    private final Plugin plugin;
    private final Logger logger;
    private final Map<UUID, String> openScreens = new ConcurrentHashMap<>();
    private ScreenDispatchHandler dispatchHandler;

    public ScreenService(Plugin plugin, Logger logger) {
        this.plugin = plugin;
        this.logger = logger;
    }

    public void setDispatchHandler(ScreenDispatchHandler handler) {
        this.dispatchHandler = handler;
    }

    public void openScreen(Player player, OpenScreenPayload payload) {
        player.sendPluginMessage(plugin, GuiConstants.OPEN_SCREEN_CHANNEL,
                payload.toBytes());
        openScreens.put(player.getUniqueId(), payload.screenId());
        logger.info("Opened screen '" + payload.screenId() + "' for " + player.getName());
    }

    public void closeScreen(Player player, String screenId) {
        player.sendPluginMessage(plugin, GuiConstants.CLOSE_SCREEN_CHANNEL,
                new CloseScreenPayload(screenId).toBytes());
        openScreens.remove(player.getUniqueId());
        logger.info("Closed screen '" + screenId + "' for " + player.getName());
    }

    public String getOpenScreen(Player player) {
        return openScreens.get(player.getUniqueId());
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        try {
            if (channel.equals(GuiConstants.DISPATCH_CHANNEL)) {
                DispatchPayload dispatch = DispatchPayload.fromBytes(message);
                logger.info("Dispatch from " + player.getName()
                        + ": screen=" + dispatch.screenId()
                        + " action=" + dispatch.action()
                        + " values=" + dispatch.values());
                if (dispatchHandler != null) {
                    dispatchHandler.onDispatch(player, dispatch.screenId(),
                            dispatch.action(), dispatch.values());
                }
            } else if (channel.equals(GuiConstants.SCREEN_CLOSED_CHANNEL)) {
                ScreenClosedPayload closed = ScreenClosedPayload.fromBytes(message);
                openScreens.remove(player.getUniqueId());
                logger.info("Screen '" + closed.screenId()
                        + "' closed by " + player.getName());
            }
        } catch (Exception e) {
            logger.warning("Malformed GUI packet on " + channel
                    + " from " + player.getName() + ": " + e.getMessage());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        String removed = openScreens.remove(event.getPlayer().getUniqueId());
        if (removed != null) {
            logger.info("Cleared open screen '" + removed
                    + "' for disconnected " + event.getPlayer().getName());
        }
    }
}
