package com.houseofel.builder.npc;

import com.houseofel.builder.choice.MilestoneChoiceOption;
import com.houseofel.builder.choice.MilestoneChoiceRecord;
import com.houseofel.builder.choice.MilestoneChoiceRegistry;
import com.houseofel.builder.choice.MilestoneChoiceStore;
import com.houseofel.builder.gui.BedrockJobForm;
import com.houseofel.builder.gui.JavaJobDialog;
import com.houseofel.builder.gui.MilestoneChoiceDialog;
import com.houseofel.builder.gui.MilestoneChoiceForm;
import com.houseofel.builder.job.JobManager;
import com.houseofel.builder.toil.LevelCurve;
import net.citizensnpcs.api.event.NPCRightClickEvent;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.util.List;

/**
 * Opens the task-configuration flow when a player right-clicks a Helper NPC — a native
 * Dialog for Java players, or a native Form for Bedrock players. Both are single-shot
 * menus (pick everything, hit one button) rather than a chest-style inventory, since
 * clicking items as makeshift buttons reads as awkward on Java and is outright broken
 * on Bedrock (whose touch controls treat any inventory click as pick-up-then-place).
 *
 * <p>If every Helper is already busy up to the concurrency ceiling, both platforms get
 * a "can't help you right now" notice instead, with a rough ETA for the soonest job to
 * free up.
 */
public final class BuilderNpcListener implements Listener {

    private final BuilderNpcService npcService;
    private final HelperLevelService levelService;
    private final JavaJobDialog javaDialog;
    private final BedrockJobForm bedrockForm;
    private final JobManager jobManager;
    private final MilestoneChoiceStore choiceStore;
    private final MilestoneChoiceDialog choiceDialog;
    private final MilestoneChoiceForm choiceForm;

    public BuilderNpcListener(BuilderNpcService npcService, HelperLevelService levelService,
                               JavaJobDialog javaDialog, BedrockJobForm bedrockForm, JobManager jobManager,
                               MilestoneChoiceStore choiceStore,
                               MilestoneChoiceDialog choiceDialog, MilestoneChoiceForm choiceForm) {
        this.npcService = npcService;
        this.levelService = levelService;
        this.javaDialog = javaDialog;
        this.bedrockForm = bedrockForm;
        this.jobManager = jobManager;
        this.choiceStore = choiceStore;
        this.choiceDialog = choiceDialog;
        this.choiceForm = choiceForm;
    }

    @EventHandler
    public void onRightClick(NPCRightClickEvent event) {
        if (!npcService.isHelper(event.getNPC())) {
            return;
        }
        Player player = event.getClicker();
        NPC npc = event.getNPC();
        boolean isBedrock = BedrockJobForm.isBedrockPlayer(player);

        // Null for a pre-1-F NPC that was never assigned a specialization — both
        // open()s fall back to just the Helper's name in that case. The level drives the
        // flavour line under the header, which changes as the Helper grows.
        Specialization specialization = levelService.specializationOf(npc);
        int level = levelService.levelOf(npc);

        // A pending, not-yet-made Choice takes over the right-click entirely — the
        // Helper "needs help deciding" before it'll take normal job orders again. Once
        // decided, right-click goes straight back to the usual job menu; reconsidering an
        // ALREADY-made choice is a separate, deliberate action (the "<Name> respec" chat
        // trigger — see MilestoneChoiceRespecListener), not something every future
        // right-click should interrupt.
        int pendingChoiceLevel = pendingChoiceLevel(npc, specialization, level);
        if (pendingChoiceLevel > 0) {
            String parentChoice = parentChoiceFor(npc, pendingChoiceLevel);
            List<MilestoneChoiceOption> options = MilestoneChoiceRegistry.optionsFor(specialization, pendingChoiceLevel, parentChoice);
            if (isBedrock) {
                choiceForm.open(player, npc, pendingChoiceLevel, options);
            } else {
                choiceDialog.open(player, npc, pendingChoiceLevel, options);
            }
            return;
        }

        // This SPECIFIC Helper already working (Clear or Quarry — both share JobManager's
        // registry since 2026-08-21) is a different condition from the server-wide
        // ceiling below: right-clicking a busy Helper should show its stats, not another
        // set of dispatch buttons that would just orphan the job already running. Kyle's
        // report, 2026-08-20.
        if (jobManager.find(npc.getId()) != null) {
            if (isBedrock) {
                bedrockForm.showStatusOnly(player, npc, specialization, level);
            } else {
                javaDialog.showStatusOnly(player, npc, specialization, level);
            }
            return;
        }

        if (jobManager.isAtCeiling()) {
            String eta = jobManager.etaOfSoonestJob().orElse("a little while");
            if (isBedrock) {
                bedrockForm.showBusy(player, npc, eta);
            } else {
                javaDialog.showBusy(player, npc, eta);
            }
            return;
        }

        if (isBedrock) {
            bedrockForm.open(player, npc, specialization, level);
        } else {
            javaDialog.open(player, npc, specialization, level);
        }
    }

    /**
     * The lowest Choice-slot level this Helper has reached but not yet decided, or 0 if
     * none — checked in level order so an unlikely double-pending case (e.g. a big single
     * Toil award that jumped past both 8 and 16 at once) always offers the earlier one first.
     * Uses parent-choice filtering for path-specific forks (L16 depends on L8).
     */
    private int pendingChoiceLevel(NPC npc, Specialization specialization, int level) {
        for (int candidate = 1; candidate <= level; candidate++) {
            if (!LevelCurve.isChoiceLevel(candidate)) {
                continue;
            }
            String parentChoice = parentChoiceFor(npc, candidate);
            List<MilestoneChoiceOption> options = MilestoneChoiceRegistry.optionsFor(specialization, candidate, parentChoice);
            if (!options.isEmpty() && choiceStore.find(npc.getUniqueId(), candidate) == null) {
                return candidate;
            }
        }
        return 0;
    }

    /**
     * The NPC's prior choice that gates a path-specific fork at {@code choiceLevel}.
     * For L16, that's the L8 choice; for L8, null (no parent). Returns null if the
     * NPC hasn't made the prerequisite choice yet.
     */
    private String parentChoiceFor(NPC npc, int choiceLevel) {
        if (choiceLevel <= 8) {
            return null;
        }
        MilestoneChoiceRecord l8 = choiceStore.find(npc.getUniqueId(), 8);
        return l8 != null ? l8.choice() : null;
    }
}
