package com.houseofel.builder;

import com.houseofel.builder.command.BuilderCommand;
import com.houseofel.builder.gui.BedrockJobForm;
import com.houseofel.builder.gui.JavaJobDialog;
import com.houseofel.builder.job.JobExecutionService;
import com.houseofel.builder.job.JobManager;
import com.houseofel.builder.job.JobNotificationListener;
import com.houseofel.builder.job.PlayerPlacementTracker;
import com.houseofel.builder.npc.BuilderNpcListener;
import com.houseofel.builder.npc.BuilderNpcService;
import com.houseofel.builder.npc.HelperCommandListener;
import com.houseofel.builder.region.RegionConfirmListener;
import com.houseofel.builder.region.RegionSelectionService;
import com.houseofel.builder.region.SurveyorListener;
import com.houseofel.builder.region.SurveyorRod;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * HoEL-Builder — Helper NPC (builder/gatherer companion) systems.
 */
public final class HoELBuilder extends JavaPlugin {

    private JobManager jobManager;

    @Override
    public void onEnable() {
        BuilderNpcService npcService = new BuilderNpcService();
        SurveyorRod rod = new SurveyorRod(this);
        PlayerPlacementTracker placementTracker = new PlayerPlacementTracker(this);
        jobManager = new JobManager(this, placementTracker);
        JobExecutionService jobExecutionService = new JobExecutionService(this, jobManager, placementTracker);
        RegionSelectionService regionService = new RegionSelectionService(this, rod, jobExecutionService);
        JavaJobDialog javaDialog = new JavaJobDialog(this, regionService);
        BedrockJobForm bedrockForm = new BedrockJobForm(this, regionService);

        getCommand("builder").setExecutor(new BuilderCommand(npcService, regionService));
        getServer().getPluginManager().registerEvents(
                new BuilderNpcListener(npcService, javaDialog, bedrockForm, jobManager), this);
        getServer().getPluginManager().registerEvents(new HelperCommandListener(this, npcService, jobManager), this);
        getServer().getPluginManager().registerEvents(new SurveyorListener(rod, regionService), this);
        getServer().getPluginManager().registerEvents(new RegionConfirmListener(this, regionService), this);
        getServer().getPluginManager().registerEvents(new JobNotificationListener(jobManager), this);
        getServer().getPluginManager().registerEvents(placementTracker, this);

        jobManager.resumeAllOnEnable();

        getLogger().info("HoEL-Builder enabled.");
    }

    @Override
    public void onDisable() {
        if (jobManager != null) {
            jobManager.saveAllOnDisable();
        }
        getLogger().info("HoEL-Builder disabled.");
    }
}
