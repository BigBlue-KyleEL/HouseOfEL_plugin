package com.houseofel.core.gui;

import com.houseofel.common.net.HandshakePayload;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.geysermc.floodgate.api.FloodgateApi;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public final class GuiCapabilityService implements PluginMessageListener, Listener {

    public record ModCapability(String modVersion, int schemaVersion) {}

    private final Map<UUID, ModCapability> capabilities = new ConcurrentHashMap<>();
    private final int minSchemaVersion;
    private final Logger logger;

    public GuiCapabilityService(int minSchemaVersion, Logger logger) {
        this.minSchemaVersion = minSchemaVersion;
        this.logger = logger;
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        HandshakePayload handshake;
        try {
            handshake = HandshakePayload.fromBytes(message);
        } catch (Exception e) {
            logger.warning("Malformed GUI handshake from " + player.getName() + ": " + e.getMessage());
            return;
        }

        if (handshake.schemaVersion() < minSchemaVersion) {
            player.kick(Component.text(
                    "Your House of EL modpack is outdated — please update it.\n"
                    + "(Your GUI schema: v" + handshake.schemaVersion()
                    + ", server requires: v" + minSchemaVersion + ")",
                    NamedTextColor.RED));
            logger.info("Kicked " + player.getName() + " — schema v" + handshake.schemaVersion()
                    + " below minimum v" + minSchemaVersion);
            return;
        }

        capabilities.put(player.getUniqueId(),
                new ModCapability(handshake.modVersion(), handshake.schemaVersion()));
        logger.info("GUI handshake from " + player.getName()
                + ": mod v" + handshake.modVersion()
                + ", schema v" + handshake.schemaVersion());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        ModCapability removed = capabilities.remove(event.getPlayer().getUniqueId());
        if (removed != null) {
            logger.info("Cleared GUI capability for " + event.getPlayer().getName());
        }
    }

    public UiPath getUiPath(Player player) {
        if (isBedrockPlayer(player)) return UiPath.BEDROCK;
        if (capabilities.containsKey(player.getUniqueId())) return UiPath.MOD;
        return UiPath.VANILLA_JAVA;
    }

    public ModCapability getModCapability(Player player) {
        return capabilities.get(player.getUniqueId());
    }

    private boolean isBedrockPlayer(Player player) {
        try {
            return FloodgateApi.getInstance().isFloodgatePlayer(player.getUniqueId());
        } catch (Exception e) {
            return false;
        }
    }
}
