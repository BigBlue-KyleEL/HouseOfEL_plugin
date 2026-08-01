package com.houseofel.builder;

import com.houseofel.builder.command.BuilderCommand;
import com.houseofel.builder.gui.BuilderGui;
import com.houseofel.builder.npc.BuilderNpcListener;
import com.houseofel.builder.npc.BuilderNpcService;
import com.houseofel.builder.region.RegionSelectionService;
import com.houseofel.builder.region.SurveyorListener;
import com.houseofel.builder.region.SurveyorRod;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * HoEL-Builder — Helper NPC (builder/gatherer companion) systems.
 */
public final class HoELBuilder extends JavaPlugin {

    @Override
    public void onEnable() {
        BuilderNpcService npcService = new BuilderNpcService();
        SurveyorRod rod = new SurveyorRod(this);
        RegionSelectionService regionService = new RegionSelectionService(this, rod);
        BuilderGui gui = new BuilderGui(regionService);

        getCommand("builder").setExecutor(new BuilderCommand(npcService, regionService));
        getServer().getPluginManager().registerEvents(new BuilderNpcListener(npcService, gui), this);
        getServer().getPluginManager().registerEvents(gui, this);
        getServer().getPluginManager().registerEvents(new SurveyorListener(rod, regionService), this);

        getLogger().info("HoEL-Builder enabled.");
    }

    @Override
    public void onDisable() {
        getLogger().info("HoEL-Builder disabled.");
    }
}
