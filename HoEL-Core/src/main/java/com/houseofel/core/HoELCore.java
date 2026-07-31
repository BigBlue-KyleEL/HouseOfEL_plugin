package com.houseofel.core;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * HoEL-Core — the base House of EL module.
 * Owns the shared /hel command and, later, shared services other modules depend on.
 */
public final class HoELCore extends JavaPlugin {

    private static final String HEL_PERMISSION = "houseofel.hel.use";

    @Override
    public void onEnable() {
        getLogger().info("HoEL-Core enabled.");
    }

    @Override
    public void onDisable() {
        getLogger().info("HoEL-Core disabled.");
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
