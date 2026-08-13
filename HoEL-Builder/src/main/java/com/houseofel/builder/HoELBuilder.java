package com.houseofel.builder;

import com.houseofel.builder.command.BuilderCommand;
import com.houseofel.builder.gui.BedrockJobForm;
import com.houseofel.builder.gui.JavaJobDialog;
import com.houseofel.builder.job.JobExecutionService;
import com.houseofel.builder.job.JobManager;
import com.houseofel.builder.job.JobNotificationListener;
import com.houseofel.builder.npc.BuilderNpcListener;
import com.houseofel.builder.npc.BuilderNpcService;
import com.houseofel.builder.npc.CitizensReadyListener;
import com.houseofel.builder.npc.HelperCommandListener;
import com.houseofel.builder.npc.HelperLevelService;
import com.houseofel.builder.npc.HelperStatusListener;
import com.houseofel.builder.npc.Specialization;
import com.houseofel.builder.npc.SpecializationDialog;
import com.houseofel.builder.npc.SpecializationForm;
import com.houseofel.builder.region.RegionConfirmListener;
import com.houseofel.builder.region.RegionSelectionService;
import com.houseofel.builder.region.SurveyorListener;
import com.houseofel.builder.region.SurveyorRod;
import com.houseofel.builder.title.HelperTitleService;
import com.houseofel.builder.toil.ToilDatabase;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * HoEL-Builder — Helper NPC (builder/gatherer companion) systems.
 */
public final class HoELBuilder extends JavaPlugin {

    private JobManager jobManager;
    private ToilDatabase toilDatabase;
    private HelperLevelService levelService;

    @Override
    public void onEnable() {
        toilDatabase = new ToilDatabase(this);
        HelperTitleService titleService = new HelperTitleService();
        levelService = new HelperLevelService(this, toilDatabase, titleService);
        BuilderNpcService npcService = new BuilderNpcService(levelService, titleService);
        SurveyorRod rod = new SurveyorRod(this);
        jobManager = new JobManager(this, levelService);
        JobExecutionService jobExecutionService = new JobExecutionService(this, jobManager, levelService);
        RegionSelectionService regionService = new RegionSelectionService(this, rod, jobExecutionService);
        JavaJobDialog javaDialog = new JavaJobDialog(this, regionService);
        BedrockJobForm bedrockForm = new BedrockJobForm(this, regionService);
        SpecializationDialog specializationDialog = new SpecializationDialog(this, npcService);
        SpecializationForm specializationForm = new SpecializationForm(this, npcService);

        getCommand("builder").setExecutor(
                new BuilderCommand(specializationDialog, specializationForm, regionService));
        getServer().getPluginManager().registerEvents(
                new BuilderNpcListener(npcService, levelService, javaDialog, bedrockForm, jobManager), this);
        getServer().getPluginManager().registerEvents(new HelperCommandListener(this, npcService, jobManager), this);
        getServer().getPluginManager().registerEvents(
                new HelperStatusListener(this, npcService, levelService), this);
        getServer().getPluginManager().registerEvents(new SurveyorListener(rod, regionService), this);
        getServer().getPluginManager().registerEvents(new RegionConfirmListener(this, regionService), this);
        getServer().getPluginManager().registerEvents(new JobNotificationListener(jobManager), this);

        jobManager.resumeAllOnEnable();
        // Citizens loads its NPC registry AFTER plugins enable, so this can't run inline
        // here — at this point the registry is still empty. CitizensEnableEvent fires
        // once its NPCs are actually loaded.
        getServer().getPluginManager().registerEvents(
                new CitizensReadyListener(() -> refreshHelperTitles(npcService, levelService, titleService)), this);

        getLogger().info("HoEL-Builder enabled.");
    }

    /**
     * Citizens persists the titled name itself, so titles already survive a restart —
     * this pass is a cheap self-heal for the cases that wouldn't: a Helper that predates
     * titles entirely, or one whose title text changed in {@code TitleLadder} since it
     * last levelled. Skips the write when the name already matches.
     */
    private void refreshHelperTitles(BuilderNpcService npcService, HelperLevelService levelService,
                                      HelperTitleService titleService) {
        for (NPC npc : CitizensAPI.getNPCRegistry()) {
            if (!npcService.isHelper(npc)) {
                continue;
            }
            // Must precede applyTitle — see ensureBaseName for why order matters here.
            BuilderNpcService.ensureBaseName(npc);
            Specialization specialization = levelService.specializationOf(npc);
            if (specialization != null) {
                titleService.applyTitle(npc, specialization, levelService.levelOf(npc));
            }
        }
    }

    @Override
    public void onDisable() {
        if (jobManager != null) {
            jobManager.saveAllOnDisable();
        }
        if (levelService != null) {
            levelService.flushProgress();
        }
        if (toilDatabase != null) {
            toilDatabase.close();
        }
        getLogger().info("HoEL-Builder disabled.");
    }
}
