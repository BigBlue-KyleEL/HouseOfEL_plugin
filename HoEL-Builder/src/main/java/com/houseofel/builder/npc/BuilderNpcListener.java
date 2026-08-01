package com.houseofel.builder.npc;

import com.houseofel.builder.gui.BuilderGui;
import net.citizensnpcs.api.event.NPCRightClickEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

/** Opens the task-configuration GUI when a player right-clicks a Helper NPC. */
public final class BuilderNpcListener implements Listener {

    private final BuilderNpcService npcService;
    private final BuilderGui gui;

    public BuilderNpcListener(BuilderNpcService npcService, BuilderGui gui) {
        this.npcService = npcService;
        this.gui = gui;
    }

    @EventHandler
    public void onRightClick(NPCRightClickEvent event) {
        if (!npcService.isHelper(event.getNPC())) {
            return;
        }
        Player player = event.getClicker();
        gui.open(player, event.getNPC());
    }
}
