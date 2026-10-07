package com.houseofel.llm;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * HEL-LLM — empty shell for now. Will house the Gemini API bridge
 * for lore/quest NPC dialogue starting in Phase 3.
 */
public final class HELLLM extends JavaPlugin {

    @Override
    public void onEnable() {
        getLogger().info("HEL-LLM enabled (empty shell).");
    }

    @Override
    public void onDisable() {
        getLogger().info("HEL-LLM disabled.");
    }
}
