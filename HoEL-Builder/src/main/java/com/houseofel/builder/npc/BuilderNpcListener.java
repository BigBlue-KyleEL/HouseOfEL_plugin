package com.houseofel.builder.npc;

import com.houseofel.builder.gui.BedrockJobForm;
import com.houseofel.builder.gui.JavaJobDialog;
import com.houseofel.builder.job.JobManager;
import net.citizensnpcs.api.event.NPCRightClickEvent;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

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

    public BuilderNpcListener(BuilderNpcService npcService, HelperLevelService levelService,
                               JavaJobDialog javaDialog, BedrockJobForm bedrockForm, JobManager jobManager) {
        this.npcService = npcService;
        this.levelService = levelService;
        this.javaDialog = javaDialog;
        this.bedrockForm = bedrockForm;
        this.jobManager = jobManager;
    }

    @EventHandler
    public void onRightClick(NPCRightClickEvent event) {
        if (!npcService.isHelper(event.getNPC())) {
            return;
        }
        Player player = event.getClicker();
        NPC npc = event.getNPC();
        boolean isBedrock = BedrockJobForm.isBedrockPlayer(player);

        if (jobManager.isAtCeiling()) {
            String eta = jobManager.etaOfSoonestJob().orElse("a little while");
            if (isBedrock) {
                bedrockForm.showBusy(player, npc, eta);
            } else {
                javaDialog.showBusy(player, npc, eta);
            }
            return;
        }

        // Null for a pre-1-F NPC that was never assigned a specialization — both
        // open()s fall back to just the Helper's name in that case. The level drives the
        // flavour line under the header, which changes as the Helper grows.
        Specialization specialization = levelService.specializationOf(npc);
        int level = levelService.levelOf(npc);
        if (isBedrock) {
            bedrockForm.open(player, npc, specialization, level);
        } else {
            javaDialog.open(player, npc, specialization, level);
        }
    }
}
