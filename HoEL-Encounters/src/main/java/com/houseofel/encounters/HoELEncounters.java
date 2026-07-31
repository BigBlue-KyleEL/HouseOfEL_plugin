package com.houseofel.encounters;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * HoEL-Encounters — empty shell for now. Will house combat/skill NPC
 * encounters (MythicMobs bridging) starting in Phase 4.
 */
public final class HoELEncounters extends JavaPlugin {

    @Override
    public void onEnable() {
        getLogger().info("HoEL-Encounters enabled (empty shell).");
    }

    @Override
    public void onDisable() {
        getLogger().info("HoEL-Encounters disabled.");
    }
}
