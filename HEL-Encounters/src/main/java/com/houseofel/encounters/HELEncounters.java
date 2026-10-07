package com.houseofel.encounters;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * HEL-Encounters — empty shell for now. Will house combat/skill NPC
 * encounters (MythicMobs bridging) starting in Phase 4.
 */
public final class HELEncounters extends JavaPlugin {

    @Override
    public void onEnable() {
        getLogger().info("HEL-Encounters enabled (empty shell).");
    }

    @Override
    public void onDisable() {
        getLogger().info("HEL-Encounters disabled.");
    }
}
