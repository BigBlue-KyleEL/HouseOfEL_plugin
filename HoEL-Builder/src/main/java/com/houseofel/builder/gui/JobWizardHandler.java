package com.houseofel.builder.gui;

import com.houseofel.builder.choice.GroundworkerL8Choice;
import com.houseofel.builder.choice.GroundworkerL16Choice;
import com.houseofel.builder.choice.MilestoneChoiceRecord;
import com.houseofel.builder.choice.MilestoneChoiceStore;
import com.houseofel.builder.death.DeathRecordStore;
import com.houseofel.builder.job.JobManager;
import com.houseofel.builder.job.LandscapeBiome;
import com.houseofel.builder.job.LandscapeMode;
import com.houseofel.builder.npc.BuilderNpcService;
import com.houseofel.builder.npc.HelperLevelService;
import com.houseofel.builder.npc.Specialization;
import com.houseofel.builder.region.RegionSelectionService;
import com.houseofel.builder.title.FlavorLadder;
import com.houseofel.common.net.DispatchValue;
import com.houseofel.core.gui.ScreenDispatchHandler;
import com.houseofel.core.gui.ScreenService;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public final class JobWizardHandler implements ScreenDispatchHandler, Listener {

    private static final Logger LOGGER = Logger.getLogger("HoEL-Builder");

    private final Plugin plugin;
    private final ScreenService screenService;
    private final RegionSelectionService regionService;
    private final DeathRecordStore deathRecordStore;
    private final MilestoneChoiceStore choiceStore;
    private final HelperLevelService levelService;

    private final Map<UUID, WizardSession> sessions = new ConcurrentHashMap<>();

    public JobWizardHandler(Plugin plugin, ScreenService screenService,
                             RegionSelectionService regionService,
                             DeathRecordStore deathRecordStore,
                             MilestoneChoiceStore choiceStore,
                             HelperLevelService levelService) {
        this.plugin = plugin;
        this.screenService = screenService;
        this.regionService = regionService;
        this.deathRecordStore = deathRecordStore;
        this.choiceStore = choiceStore;
        this.levelService = levelService;
    }

    // --- Entry points (called from BuilderNpcListener on the main thread) ---

    /** Opens the live menu and registers the NPC for its subsequent job clicks. */
    public void openMainMenu(Player player, NPC npc, Specialization specialization, int level,
                              JobManager jobManager) {
        sessions.put(player.getUniqueId(), new WizardSession(npc.getId(), specialization, level));
        screenService.openScreen(player, MainMenuLayout.create(npc, specialization, level, jobManager,
                levelService, deathRecordStore, choiceStore, buildTaskTypeButtons(npc, specialization, level)));
    }
    /** The session and buttons retain the real NPC's abilities even while its display is faked. */
    public void openMainMenuPreview(Player player, NPC npc, JobManager jobManager,
                                    MainMenuLayout.Preview preview) {
        Specialization specialization = levelService.specializationOf(npc);
        int level = levelService.levelOf(npc);
        sessions.put(player.getUniqueId(), new WizardSession(npc.getId(), specialization, level));
        screenService.openScreen(player, MainMenuLayout.create(npc, specialization, level, jobManager,
                levelService, deathRecordStore, choiceStore, buildTaskTypeButtons(npc, specialization, level),
                preview));
    }

    public void startWizard(Player player, NPC npc, Specialization specialization, int level) {
        sessions.put(player.getUniqueId(), new WizardSession(npc.getId(), specialization, level));

        String title = HelperTitleFormatter.dispatchTitleOf(npc, specialization, deathRecordStore, choiceStore);
        List<String> statusLines = buildStatusLines(npc, specialization, level);
        List<JobMenuLayout.ButtonOption> buttons = buildTaskTypeButtons(npc, specialization, level);

        screenService.openScreen(player, JobMenuLayout.taskTypeScreen(title, statusLines, buttons));
    }

    public void showStatusOnly(Player player, NPC npc, Specialization specialization, int level) {
        String title = HelperTitleFormatter.dispatchTitleOf(npc, specialization, deathRecordStore, choiceStore);
        List<String> statusLines = buildStatusLines(npc, specialization, level);
        screenService.openScreen(player, JobMenuLayout.statusScreen(title, statusLines));
    }

    // --- Dispatch handler (called from Netty thread — schedule to main) ---

    @Override
    public void onDispatch(Player player, String screenId, String action,
                            Map<String, DispatchValue> values) {
        Bukkit.getScheduler().runTask(plugin, () ->
                handleDispatch(player, screenId, action, values));
    }

    private void handleDispatch(Player player, String screenId, String action,
                                 Map<String, DispatchValue> values) {
        if (!player.isOnline() || !screenId.equals(screenService.getOpenScreen(player))) return;
        if ("dismiss".equals(action) || "cancel".equals(action)) {
            screenService.closeScreen(player, screenId);
            sessions.remove(player.getUniqueId());
            return;
        }

        if (JobAvailability.refuseAction(action, player::sendMessage)) return;

        if (MainMenuLayout.SCREEN_ID.equals(screenId)) {
            WizardSession session = sessions.get(player.getUniqueId());
            if (session == null) return;
            NPC npc = lookupNpc(session);
            if (npc == null || buildTaskTypeButtons(npc, levelService.specializationOf(npc),
                    levelService.levelOf(npc)).stream().noneMatch(button -> button.action().equals(action))) return;
        }
        switch (screenId) {
            case JobMenuLayout.JOB_TYPE, MainMenuLayout.SCREEN_ID -> handleTaskType(player, action);
            case JobMenuLayout.JOB_TARGET -> handleTarget(player, action);
            case JobMenuLayout.CLEARING_TARGET -> handleClearingTarget(player, action);
            case JobMenuLayout.CLEARING_PICKER -> handleClearingPicker(player, action, values);
            case JobMenuLayout.JOB_CONFIRM -> handleConfirm(player, action, values);
            case JobMenuLayout.JOB_DEPTH -> handleDepthChoice(player, action);
            case JobMenuLayout.JOB_DEPTH_LEVEL -> handleDepthLevel(player, values);
            case JobMenuLayout.JOB_DEPTH_COORD -> handleDepthCoord(player, values);
            case JobMenuLayout.LANDSCAPE_MODE -> handleLandscapeMode(player, action);
            case JobMenuLayout.LANDSCAPE_BIOME -> handleLandscapeBiome(player, action);
            default -> {
                screenService.closeScreen(player, screenId);
                sessions.remove(player.getUniqueId());
            }
        }
    }

    // --- Step handlers ---

    private void handleTaskType(Player player, String action) {
        WizardSession session = sessions.get(player.getUniqueId());
        if (session == null) return;
        NPC npc = lookupNpc(session);
        if (npc == null) return;

        switch (action) {
            case "clear" -> advanceToTarget(player, session, npc, TaskType.CLEAR);
            case "quarry" -> {
                session.taskType = TaskType.QUARRY;
                session.target = Target.ANY_EARTH;
                screenService.openScreen(player, JobMenuLayout.depthScreen(npc, TaskType.QUARRY));
            }
            case "landscape" -> {
                session.taskType = TaskType.LANDSCAPE;
                screenService.openScreen(player, JobMenuLayout.landscapeModeScreen(npc));
            }
            case "cofferdam" -> {
                sessions.remove(player.getUniqueId());
                screenService.closeScreen(player, screenService.getOpenScreen(player));
                regionService.beginCofferdamJob(player, npc);
            }
            case "shaft_miner" -> {
                session.taskType = TaskType.SHAFT_MINER;
                session.target = Target.ANY_EARTH;
                screenService.openScreen(player, JobMenuLayout.depthScreen(npc, TaskType.SHAFT_MINER));
            }
        }
    }

    private void advanceToTarget(Player player, WizardSession session, NPC npc, TaskType taskType) {
        session.taskType = taskType;
        session.target = null;
        if (taskType == TaskType.CLEAR) {
            showClearingTarget(player, npc);
            return;
        }
        List<Target> targets = new ArrayList<>();
        for (Target t : Target.values()) {
            if (t == Target.ANY_EARTH
                    && !(taskType == TaskType.CLEAR
                    && session.specialization == Specialization.GROUNDWORKER
                    && session.level >= 3)) {
                continue;
            }
            targets.add(t);
        }
        screenService.openScreen(player, JobMenuLayout.targetScreen(npc, targets));
    }

    private void handleTarget(Player player, String action) {
        WizardSession session = sessions.get(player.getUniqueId());
        if (session == null) return;
        NPC npc = lookupNpc(session);
        if (npc == null) return;

        for (Target t : Target.values()) {
            if (session.taskType != TaskType.CLEAR && t != Target.ANY_EARTH && t.name().equalsIgnoreCase(action)) {
                session.target = t;
                screenService.openScreen(player,
                        JobMenuLayout.confirmScreen(npc, session.taskType, t));
                return;
            }
        }
    }

    private void showClearingTarget(Player player, NPC npc) {
        screenService.openScreen(player, JobMenuLayout.clearingTargetScreen(npc,
                ClearingPicker.everythingState(levelService.specializationOf(npc), levelService.levelOf(npc))));
    }

    private void handleClearingTarget(Player player, String action) {
        WizardSession session = sessions.get(player.getUniqueId());
        if (session == null || session.taskType != TaskType.CLEAR) return;
        NPC npc = lookupNpc(session);
        if (npc == null) return;
        if ("specific_block".equals(action)) {
            session.results = ClearingTargetPool.allowedMaterials().stream()
                    .sorted(java.util.Comparator.comparing(Target::blockLabel)).toList();
            showClearingPicker(player, npc, session);
        } else if ("everything".equals(action)) {
            chooseClearingTarget(player, npc, session, Target.ANY_EARTH);
        }
    }

    private void showClearingPicker(Player player, NPC npc, WizardSession session) {
        screenService.openScreen(player, JobMenuLayout.clearingPickerScreen(npc,
                session.results));
    }

    private void handleClearingPicker(Player player, String action, Map<String, DispatchValue> values) {
        WizardSession session = sessions.get(player.getUniqueId());
        if (session == null || session.taskType != TaskType.CLEAR) return;
        NPC npc = lookupNpc(session);
        if (npc == null) return;
        if ("back".equals(action)) {
            showClearingTarget(player, npc);
            return;
        }
        if (!"pick_block".equals(action) || !(values.get("blocks") instanceof DispatchValue.StringVal value)) return;
        try {
            Material material = Material.valueOf(value.value());
            if (!session.results.contains(material)) return;
            // specificBlock revalidates the live tags, including if they changed since opening.
            chooseClearingTarget(player, npc, session, Target.specificBlock(material));
        } catch (IllegalArgumentException invalid) {
            player.sendMessage(Component.text("That block is not available for Clearing.", NamedTextColor.RED));
        }
    }

    private void chooseClearingTarget(Player player, NPC npc, WizardSession session, Target target) {
        if (!ClearingPicker.isAllowed(target, levelService.specializationOf(npc), levelService.levelOf(npc))) {
            player.sendMessage(Component.text("Everything unlocks at Groundworker L3.", NamedTextColor.YELLOW));
            showClearingTarget(player, npc);
            return;
        }
        session.target = target;
        screenService.openScreen(player, JobMenuLayout.confirmScreen(npc, TaskType.CLEAR, target));
    }

    private void handleConfirm(Player player, String action, Map<String, DispatchValue> values) {
        if (!"send_to_work".equals(action)) return;
        WizardSession session = sessions.get(player.getUniqueId());
        if (session == null || session.target == null) return;
        NPC npc = lookupNpc(session);
        if (npc == null) return;
        if (session.taskType == TaskType.CLEAR && !ClearingPicker.isAllowed(session.target,
                levelService.specializationOf(npc), levelService.levelOf(npc))) {
            player.sendMessage(Component.text("That Clearing target is no longer available.", NamedTextColor.RED));
            showClearingTarget(player, npc);
            return;
        }
        sessions.remove(player.getUniqueId());

        boolean surfaceOnly = boolValue(values, "surfaceOnly", true);
        boolean storeInChest = boolValue(values, "storeInChest", true);

        screenService.closeScreen(player, JobMenuLayout.JOB_CONFIRM);
        regionService.beginJob(player, npc, session.taskType, session.target,
                storeInChest, surfaceOnly, session.depthLevels, session.depthTargetY);
    }

    private void handleDepthChoice(Player player, String action) {
        WizardSession session = sessions.get(player.getUniqueId());
        if (session == null) return;
        NPC npc = lookupNpc(session);
        if (npc == null) return;

        if ("depth_level".equals(action)) {
            screenService.openScreen(player,
                    JobMenuLayout.depthLevelScreen(npc, session.taskType));
        } else if ("depth_coordinates".equals(action)) {
            screenService.openScreen(player,
                    JobMenuLayout.depthCoordScreen(npc, session.taskType));
        }
    }

    private void handleDepthLevel(Player player, Map<String, DispatchValue> values) {
        WizardSession session = sessions.get(player.getUniqueId());
        if (session == null) return;
        NPC npc = lookupNpc(session);
        if (npc == null) return;

        Integer levels = parseIntValue(player, npc, values, "levels");
        if (levels == null) return;

        session.depthLevels = levels;

        if (session.taskType == TaskType.SHAFT_MINER) {
            sessions.remove(player.getUniqueId());
            screenService.closeScreen(player, JobMenuLayout.JOB_DEPTH_LEVEL);
            regionService.beginJob(player, npc, TaskType.SHAFT_MINER, session.target,
                    true, false, levels, null);
        } else {
            screenService.openScreen(player,
                    JobMenuLayout.confirmScreen(npc, session.taskType, session.target));
        }
    }

    private void handleDepthCoord(Player player, Map<String, DispatchValue> values) {
        WizardSession session = sessions.get(player.getUniqueId());
        if (session == null) return;
        NPC npc = lookupNpc(session);
        if (npc == null) return;

        Integer targetY = parseIntValue(player, npc, values, "targetY");
        if (targetY == null) return;

        session.depthTargetY = targetY;

        if (session.taskType == TaskType.SHAFT_MINER) {
            sessions.remove(player.getUniqueId());
            screenService.closeScreen(player, JobMenuLayout.JOB_DEPTH_COORD);
            regionService.beginJob(player, npc, TaskType.SHAFT_MINER, session.target,
                    true, false, null, targetY);
        } else {
            screenService.openScreen(player,
                    JobMenuLayout.confirmScreen(npc, session.taskType, session.target));
        }
    }

    private void handleLandscapeMode(Player player, String action) {
        WizardSession session = sessions.get(player.getUniqueId());
        if (session == null) return;
        NPC npc = lookupNpc(session);
        if (npc == null) return;

        for (LandscapeMode mode : LandscapeMode.values()) {
            if (mode.name().equalsIgnoreCase(action)) {
                if (mode == LandscapeMode.REDESIGN) {
                    screenService.openScreen(player,
                            JobMenuLayout.landscapeBiomeScreen(npc));
                } else {
                    sessions.remove(player.getUniqueId());
                    screenService.closeScreen(player, JobMenuLayout.LANDSCAPE_MODE);
                    regionService.beginLandscapeJob(player, npc, mode, null);
                }
                return;
            }
        }
    }

    private void handleLandscapeBiome(Player player, String action) {
        WizardSession session = sessions.remove(player.getUniqueId());
        if (session == null) return;
        NPC npc = lookupNpc(session);
        if (npc == null) return;

        for (LandscapeBiome biome : LandscapeBiome.values()) {
            if (biome.name().equalsIgnoreCase(action)) {
                screenService.closeScreen(player, JobMenuLayout.LANDSCAPE_BIOME);
                regionService.beginLandscapeJob(player, npc, LandscapeMode.REDESIGN, biome);
                return;
            }
        }
    }

    // --- Helpers ---

    private List<String> buildStatusLines(NPC npc, Specialization specialization, int level) {
        List<String> lines = new ArrayList<>();
        String flavor = FlavorLadder.flavorFor(specialization, level);
        if (flavor != null) lines.add(flavor);
        String hearts = HelperTitleFormatter.heartsFor(npc);
        if (hearts != null) lines.add(hearts);
        String xpBar = HelperTitleFormatter.xpBarFor(npc, specialization, levelService);
        if (xpBar != null) lines.add(xpBar);
        String rustLine = HelperTitleFormatter.rustLineFor(npc, deathRecordStore);
        if (rustLine != null) lines.add(rustLine);
        return lines;
    }

    private List<JobMenuLayout.ButtonOption> buildTaskTypeButtons(NPC npc,
                                                                    Specialization specialization,
                                                                    int level) {
        List<JobMenuLayout.ButtonOption> buttons = new ArrayList<>();
        for (TaskType type : List.of(TaskType.MINE, TaskType.LUMBERJACK, TaskType.FARM, TaskType.CLEAR)) {
            buttons.add(new JobMenuLayout.ButtonOption(
                    "btn_" + type.name(), type.name().toLowerCase(java.util.Locale.ROOT), type.label()));
        }

        TaskType specialised = specialisedJobFor(npc, specialization, level);
        if (specialised != null) {
            buttons.add(new JobMenuLayout.ButtonOption(
                    "btn_" + specialised.name(), specialised.name().toLowerCase(java.util.Locale.ROOT),
                    "Lvl.8: " + specialised.label()));
        }
        if (hasCofferdam(npc, specialization, level)) {
            buttons.add(new JobMenuLayout.ButtonOption(
                    "btn_cofferdam", "cofferdam", "Lvl.16: Cofferdam"));
        }
        if (hasShaftMiner(npc, specialization, level)) {
            buttons.add(new JobMenuLayout.ButtonOption(
                    "btn_shaft_miner", "shaft_miner", "Lvl.16: Shaft Miner"));
        }
        return buttons;
    }

    private TaskType specialisedJobFor(NPC npc, Specialization specialization, int level) {
        if (specialization != Specialization.GROUNDWORKER || level < 8) return null;
        MilestoneChoiceRecord record = choiceStore.find(npc.getUniqueId(), 8);
        if (record == null) return null;
        if (GroundworkerL8Choice.QUARRYMAN.name().equals(record.choice())) return TaskType.QUARRY;
        if (GroundworkerL8Choice.LANDSCAPER.name().equals(record.choice())) return TaskType.LANDSCAPE;
        return null;
    }

    private boolean hasCofferdam(NPC npc, Specialization specialization, int level) {
        if (specialization != Specialization.GROUNDWORKER || level < 16) return false;
        MilestoneChoiceRecord record = choiceStore.find(npc.getUniqueId(), 16);
        return record != null && GroundworkerL16Choice.COFFERDAM.name().equals(record.choice());
    }

    private boolean hasShaftMiner(NPC npc, Specialization specialization, int level) {
        if (specialization != Specialization.GROUNDWORKER || level < 16) return false;
        MilestoneChoiceRecord record = choiceStore.find(npc.getUniqueId(), 16);
        return record != null && GroundworkerL16Choice.SHAFT_MINER.name().equals(record.choice());
    }

    private NPC lookupNpc(WizardSession session) {
        return CitizensAPI.getNPCRegistry().getById(session.npcId);
    }

    private static boolean boolValue(Map<String, DispatchValue> values, String key, boolean fallback) {
        DispatchValue val = values.get(key);
        if (val instanceof DispatchValue.BoolVal b) return b.value();
        return fallback;
    }

    private Integer parseIntValue(Player player, NPC npc, Map<String, DispatchValue> values, String key) {
        DispatchValue val = values.get(key);
        if (val instanceof DispatchValue.StringVal s) {
            try {
                return Integer.parseInt(s.value().trim());
            } catch (NumberFormatException ignored) {}
        }
        player.sendMessage(Component.text(
                BuilderNpcService.baseNameOf(npc) + ": That's not a whole number — try again.",
                NamedTextColor.RED));
        return null;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        WizardSession removed = sessions.remove(event.getPlayer().getUniqueId());
        if (removed != null) {
            LOGGER.info("Cleared wizard session for disconnected " + event.getPlayer().getName());
        }
    }

    // --- Per-player wizard state ---

    static final class WizardSession {
        final int npcId;
        final Specialization specialization;
        final int level;
        TaskType taskType;
        Target target;
        List<Material> results = List.of();
        Integer depthLevels;
        Integer depthTargetY;

        WizardSession(int npcId, Specialization specialization, int level) {
            this.npcId = npcId;
            this.specialization = specialization;
            this.level = level;
        }
    }
}
