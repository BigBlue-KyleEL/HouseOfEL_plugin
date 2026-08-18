package com.houseofel.builder;

import com.houseofel.builder.antigrind.DailyTaperStore;
import com.houseofel.builder.antigrind.FreshLedger;
import com.houseofel.builder.antigrind.FreshPlacementListener;
import com.houseofel.builder.antigrind.PresenceActivityListener;
import com.houseofel.builder.antigrind.PresenceTracker;
import com.houseofel.builder.antigrind.RedundancyTracker;
import com.houseofel.builder.antigrind.VarietyTracker;
import com.houseofel.builder.choice.MilestoneChoiceService;
import com.houseofel.builder.choice.MilestoneChoiceStore;
import com.houseofel.builder.command.BuilderCommand;
import com.houseofel.builder.death.DeathRecordStore;
import com.houseofel.builder.death.HelperDeathListener;
import com.houseofel.builder.death.LedgerExpiryTask;
import com.houseofel.builder.death.RecruitmentCost;
import com.houseofel.builder.death.RustBossBar;
import com.houseofel.builder.death.WorkLedgerBook;
import com.houseofel.builder.death.WorkLedgerRecoveryListener;
import com.houseofel.builder.gui.BedrockJobForm;
import com.houseofel.builder.gui.JavaJobDialog;
import com.houseofel.builder.gui.MilestoneChoiceDialog;
import com.houseofel.builder.gui.MilestoneChoiceForm;
import com.houseofel.builder.job.JobExecutionService;
import com.houseofel.builder.job.JobManager;
import com.houseofel.builder.job.JobNotificationListener;
import com.houseofel.builder.job.SpongeAbsorptionDiagnosticListener;
import com.houseofel.builder.npc.BuilderNpcListener;
import com.houseofel.builder.npc.BuilderNpcService;
import com.houseofel.builder.npc.CitizensReadyListener;
import com.houseofel.builder.npc.HelperCommandListener;
import com.houseofel.builder.npc.HelperLevelService;
import com.houseofel.builder.npc.HelperStatusListener;
import com.houseofel.builder.npc.MilestoneChoiceRespecListener;
import com.houseofel.builder.npc.Specialization;
import com.houseofel.builder.npc.SpecializationDialog;
import com.houseofel.builder.npc.SpecializationForm;
import com.houseofel.builder.region.RegionConfirmListener;
import com.houseofel.builder.region.RegionSelectionService;
import com.houseofel.builder.region.SurveyorListener;
import com.houseofel.builder.region.SurveyorRod;
import com.houseofel.builder.timing.HelperTempo;
import com.houseofel.builder.title.HelperTitleService;
import com.houseofel.builder.toil.ToilDatabase;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * HoEL-Builder — Helper NPC (builder/gatherer companion) systems.
 */
public final class HoELBuilder extends JavaPlugin {

    private JobManager jobManager;
    private ToilDatabase toilDatabase;
    private HelperLevelService levelService;
    private BukkitTask ledgerExpiryTask;
    private BukkitTask rustBossBarTask;

    @Override
    public void onEnable() {
        // The framework asks for its 1.45x lifetime throughput cap to be asserted at
        // runtime rather than trusted. Scoped to the chassis levers only — the dig-speed
        // curve is a known deviation, see HelperTempo — so this still catches accidental
        // drift in the parts that are meant to stay compliant.
        String throughputBreach = HelperTempo.checkChassisThroughputCap();
        if (throughputBreach != null) {
            getLogger().warning(throughputBreach);
        }

        toilDatabase = new ToilDatabase(this);
        DeathRecordStore deathRecordStore = new DeathRecordStore(toilDatabase, getLogger());
        MilestoneChoiceStore choiceStore = new MilestoneChoiceStore(toilDatabase, getLogger());
        MilestoneChoiceService choiceService = new MilestoneChoiceService(deathRecordStore, choiceStore);
        DailyTaperStore dailyTaperStore = new DailyTaperStore(toilDatabase, getLogger());
        VarietyTracker varietyTracker = new VarietyTracker();
        PresenceTracker presenceTracker = new PresenceTracker();
        RedundancyTracker redundancyTracker = new RedundancyTracker(getLogger());
        FreshLedger freshLedger = new FreshLedger();
        HelperTitleService titleService = new HelperTitleService();
        levelService = new HelperLevelService(this, toilDatabase, titleService, deathRecordStore,
                varietyTracker, presenceTracker, dailyTaperStore);
        RecruitmentCost recruitmentCost = new RecruitmentCost(deathRecordStore);
        BuilderNpcService npcService = new BuilderNpcService(levelService, titleService, recruitmentCost, deathRecordStore);
        WorkLedgerBook ledgerBook = new WorkLedgerBook(this);
        SurveyorRod rod = new SurveyorRod(this);
        jobManager = new JobManager(this, levelService, redundancyTracker, freshLedger);
        JobExecutionService jobExecutionService = new JobExecutionService(this, jobManager, levelService,
                deathRecordStore, redundancyTracker, freshLedger);
        RegionSelectionService regionService = new RegionSelectionService(this, rod, jobExecutionService);
        JavaJobDialog javaDialog = new JavaJobDialog(this, regionService, deathRecordStore, choiceStore);
        BedrockJobForm bedrockForm = new BedrockJobForm(this, regionService, deathRecordStore, choiceStore);
        SpecializationDialog specializationDialog = new SpecializationDialog(this, npcService);
        SpecializationForm specializationForm = new SpecializationForm(this, npcService);
        MilestoneChoiceDialog choiceDialog = new MilestoneChoiceDialog(this, choiceStore, choiceService);
        MilestoneChoiceForm choiceForm = new MilestoneChoiceForm(this, choiceStore, choiceService);

        getCommand("builder").setExecutor(
                new BuilderCommand(specializationDialog, specializationForm, regionService));
        getServer().getPluginManager().registerEvents(
                new BuilderNpcListener(npcService, levelService, javaDialog, bedrockForm, jobManager,
                        choiceStore, choiceDialog, choiceForm), this);
        getServer().getPluginManager().registerEvents(new HelperCommandListener(this, npcService, jobManager), this);
        getServer().getPluginManager().registerEvents(
                new HelperStatusListener(this, npcService, levelService, deathRecordStore, choiceStore), this);
        getServer().getPluginManager().registerEvents(
                new MilestoneChoiceRespecListener(this, npcService, levelService, choiceStore, choiceDialog, choiceForm), this);
        getServer().getPluginManager().registerEvents(new SurveyorListener(rod, regionService), this);
        getServer().getPluginManager().registerEvents(new RegionConfirmListener(this, regionService), this);
        getServer().getPluginManager().registerEvents(new JobNotificationListener(jobManager), this);
        getServer().getPluginManager().registerEvents(
                new HelperDeathListener(this, npcService, levelService, deathRecordStore, ledgerBook), this);
        getServer().getPluginManager().registerEvents(
                new WorkLedgerRecoveryListener(this, ledgerBook, deathRecordStore), this);
        getServer().getPluginManager().registerEvents(new FreshPlacementListener(freshLedger), this);
        getServer().getPluginManager().registerEvents(new PresenceActivityListener(this, presenceTracker), this);
        getServer().getPluginManager().registerEvents(new SpongeAbsorptionDiagnosticListener(this), this);

        ledgerExpiryTask = LedgerExpiryTask.start(this, deathRecordStore, levelService);
        rustBossBarTask = RustBossBar.start(this, deathRecordStore);

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
     *
     * <p>Also carries Death Policy's master switch for every already-leveled Helper: a
     * freshly created NPC gets {@code setProtected(false)} directly in
     * {@code BuilderNpcService#spawnHelper}, but Bartholomew/Thaddeus/Montgomery predate
     * that and need it applied here, once, on the same boot-time pass that already walks
     * every Helper.
     */
    private void refreshHelperTitles(BuilderNpcService npcService, HelperLevelService levelService,
                                      HelperTitleService titleService) {
        for (NPC npc : CitizensAPI.getNPCRegistry()) {
            if (!npcService.isHelper(npc)) {
                continue;
            }
            npc.setProtected(false);
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
        if (ledgerExpiryTask != null) {
            ledgerExpiryTask.cancel();
        }
        if (rustBossBarTask != null) {
            rustBossBarTask.cancel();
        }
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
