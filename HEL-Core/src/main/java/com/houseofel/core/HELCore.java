package com.houseofel.core;

import com.houseofel.common.net.GuiConstants;
import com.houseofel.core.border.RectBorderMath;
import com.houseofel.core.border.RectBorderService;
import com.houseofel.core.gui.GuiCapabilityService;
import com.houseofel.core.gui.ScreenService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class HELCore extends JavaPlugin {

    private static final String HEL_PERMISSION = "houseofel.hel.use";

    private GuiCapabilityService capabilityService;
    private ScreenService screenService;
    private RectBorderService rectBorderService;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        int minSchema = getConfig().getInt("minimum-schema-version", GuiConstants.SCHEMA_VERSION);

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

        rectBorderService = new RectBorderService(this, getLogger());
        rectBorderService.load(getConfig().getConfigurationSection("rect-border"));
        getServer().getPluginManager().registerEvents(rectBorderService, this);
        rectBorderService.applyToOnline();

        getLogger().info("HEL-Core enabled.");
    }

    @Override
    public void onDisable() {
        if (rectBorderService != null) rectBorderService.clearAll();
        var messenger = getServer().getMessenger();
        messenger.unregisterIncomingPluginChannel(
                this, GuiConstants.HANDSHAKE_CHANNEL, capabilityService);
        messenger.unregisterOutgoingPluginChannel(this, GuiConstants.OPEN_SCREEN_CHANNEL);
        messenger.unregisterOutgoingPluginChannel(this, GuiConstants.CLOSE_SCREEN_CHANNEL);
        messenger.unregisterIncomingPluginChannel(
                this, GuiConstants.DISPATCH_CHANNEL, screenService);
        messenger.unregisterIncomingPluginChannel(
                this, GuiConstants.SCREEN_CLOSED_CHANNEL, screenService);
        getLogger().info("HEL-Core disabled.");
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
            boolean borderStatus = args.length > 0 && args[0].equalsIgnoreCase("border");
            // Console/RCON can't hold a LuckPerms node, so it may read the (read-only) border
            // status; players still need the node.
            boolean consoleBorder = borderStatus && !(sender instanceof Player);
            if (!consoleBorder && !sender.hasPermission(HEL_PERMISSION)) {
                sender.sendMessage("You don't have permission to use this command.");
                return true;
            }
            if (borderStatus) {
                sendBorderStatus(sender);
                return true;
            }
            sender.sendMessage("House of EL — systems online.");
            return true;
        }
        return false;
    }

    private void sendBorderStatus(CommandSender sender) {
        RectBorderService rb = rectBorderService;
        if (rb == null || !rb.isEnabled()) {
            sender.sendMessage("Rect border: disabled.");
            return;
        }
        RectBorderMath m = rb.math();
        sender.sendMessage("Rect border: enabled in '" + rb.worldName() + "'.");
        sender.sendMessage("Box: X " + fmt(m.minX()) + " to " + fmt(m.maxX())
                + ", Z " + fmt(m.minZ()) + " to " + fmt(m.maxZ()) + " (square side " + fmt(m.side()) + ").");
        if (!(sender instanceof Player p)) return;
        var border = rb.borderOf(p);
        if (border == null) {
            sender.sendMessage("Your border: none (you are not in '" + rb.worldName() + "').");
            return;
        }
        var c = border.getCenter();
        sender.sendMessage("Your square: center " + fmt(c.getX()) + ", " + fmt(c.getZ())
                + ", size " + fmt(border.getSize()) + ".");
    }

    private static String fmt(double v) {
        return v == Math.rint(v) ? Long.toString((long) v) : Double.toString(v);
    }
}
