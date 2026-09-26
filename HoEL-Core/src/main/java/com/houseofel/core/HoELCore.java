package com.houseofel.core;

import com.houseofel.common.net.GuiConstants;
import com.houseofel.core.gui.GuiCapabilityService;
import com.houseofel.core.gui.ScreenService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

public final class HoELCore extends JavaPlugin {

    private static final String HEL_PERMISSION = "houseofel.hel.use";

    private GuiCapabilityService capabilityService;
    private ScreenService screenService;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        int minSchema = getConfig().getInt("minimum-schema-version", 1);

        getLogger().info("Plugin speaks schema v" + GuiConstants.SCHEMA_VERSION
                + ", requires v" + minSchema + " minimum.");

        if (minSchema > GuiConstants.SCHEMA_VERSION) {
            getLogger().severe("minimum-schema-version (" + minSchema
                    + ") exceeds this build's SCHEMA_VERSION ("
                    + GuiConstants.SCHEMA_VERSION + "). No client can join. Disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        capabilityService = new GuiCapabilityService(minSchema, getLogger());
        screenService = new ScreenService(this, getLogger());

        var messenger = getServer().getMessenger();

        // Handshake (C2S)
        messenger.registerIncomingPluginChannel(
                this, GuiConstants.HANDSHAKE_CHANNEL, capabilityService);

        // Screen payloads — outgoing (S2C)
        messenger.registerOutgoingPluginChannel(this, GuiConstants.OPEN_SCREEN_CHANNEL);
        messenger.registerOutgoingPluginChannel(this, GuiConstants.CLOSE_SCREEN_CHANNEL);

        // Screen payloads — incoming (C2S)
        messenger.registerIncomingPluginChannel(
                this, GuiConstants.DISPATCH_CHANNEL, screenService);
        messenger.registerIncomingPluginChannel(
                this, GuiConstants.SCREEN_CLOSED_CHANNEL, screenService);

        getServer().getPluginManager().registerEvents(capabilityService, this);
        getServer().getPluginManager().registerEvents(screenService, this);

        getLogger().info("HoEL-Core enabled.");
    }

    @Override
    public void onDisable() {
        var messenger = getServer().getMessenger();
        messenger.unregisterIncomingPluginChannel(
                this, GuiConstants.HANDSHAKE_CHANNEL, capabilityService);
        messenger.unregisterOutgoingPluginChannel(this, GuiConstants.OPEN_SCREEN_CHANNEL);
        messenger.unregisterOutgoingPluginChannel(this, GuiConstants.CLOSE_SCREEN_CHANNEL);
        messenger.unregisterIncomingPluginChannel(
                this, GuiConstants.DISPATCH_CHANNEL, screenService);
        messenger.unregisterIncomingPluginChannel(
                this, GuiConstants.SCREEN_CLOSED_CHANNEL, screenService);
        getLogger().info("HoEL-Core disabled.");
    }

    public GuiCapabilityService getCapabilityService() {
        return capabilityService;
    }

    public ScreenService getScreenService() {
        return screenService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("hel")) {
            if (!sender.hasPermission(HEL_PERMISSION)) {
                sender.sendMessage("You don't have permission to use this command.");
                return true;
            }
            sender.sendMessage("House of EL — systems online.");
            return true;
        }
        return false;
    }
}
