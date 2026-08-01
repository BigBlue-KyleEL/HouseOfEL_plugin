package com.houseofel.builder;

import com.houseofel.builder.command.BuilderCommand;
import com.houseofel.builder.gui.BuilderGui;
import com.houseofel.builder.npc.BuilderNpcListener;
import com.houseofel.builder.npc.BuilderNpcService;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * HoEL-Builder — Helper NPC (builder/gatherer companion) systems.
 */
public final class HoELBuilder extends JavaPlugin {

    @Override
    public void onEnable() {
        BuilderNpcService npcService = new BuilderNpcService();
        BuilderGui gui = new BuilderGui();

        getCommand("builder").setExecutor(new BuilderCommand(npcService));
        getServer().getPluginManager().registerEvents(new BuilderNpcListener(npcService, gui), this);
        getServer().getPluginManager().registerEvents(gui, this);

        getLogger().info("HoEL-Builder enabled.");
    }

    @Override
    public void onDisable() {
        getLogger().info("HoEL-Builder disabled.");
    }
}
