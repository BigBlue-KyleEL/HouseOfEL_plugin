package com.houseofel.builder;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * HoEL-Builder — empty shell for now. Will house the Helper NPC
 * (builder/gatherer companion) systems starting in Phase 1.
 */
public final class HoELBuilder extends JavaPlugin {

    @Override
    public void onEnable() {
        getLogger().info("HoEL-Builder enabled (empty shell).");
    }

    @Override
    public void onDisable() {
        getLogger().info("HoEL-Builder disabled.");
    }
}
