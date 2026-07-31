package com.houseofel.llm;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * HoEL-LLM — empty shell for now. Will house the Gemini API bridge
 * for lore/quest NPC dialogue starting in Phase 3.
 */
public final class HoELLLM extends JavaPlugin {

    @Override
    public void onEnable() {
        getLogger().info("HoEL-LLM enabled (empty shell).");
    }

    @Override
    public void onDisable() {
        getLogger().info("HoEL-LLM disabled.");
    }
}
