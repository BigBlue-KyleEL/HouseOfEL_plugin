package com.houseofel.builder.npc;

import com.houseofel.builder.gui.BedrockJobForm;
import com.houseofel.builder.gui.JavaJobDialog;
import net.citizensnpcs.api.event.NPCRightClickEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

/**
 * Opens the task-configuration flow when a player right-clicks a Helper NPC — a native
 * Dialog for Java players, or a native Form for Bedrock players. Both are single-shot
 * menus (pick everything, hit one button) rather than a chest-style inventory, since
 * clicking items as makeshift buttons reads as awkward on Java and is outright broken
 * on Bedrock (whose touch controls treat any inventory click as pick-up-then-place).
 */
public final class BuilderNpcListener implements Listener {

    private final BuilderNpcService npcService;
    private final JavaJobDialog javaDialog;
    private final BedrockJobForm bedrockForm;

    public BuilderNpcListener(BuilderNpcService npcService, JavaJobDialog javaDialog, BedrockJobForm bedrockForm) {
        this.npcService = npcService;
        this.javaDialog = javaDialog;
        this.bedrockForm = bedrockForm;
    }

    @EventHandler
    public void onRightClick(NPCRightClickEvent event) {
        if (!npcService.isHelper(event.getNPC())) {
            return;
        }
        Player player = event.getClicker();
        if (BedrockJobForm.isBedrockPlayer(player)) {
            bedrockForm.open(player, event.getNPC());
        } else {
            javaDialog.open(player, event.getNPC());
        }
    }
}
