package com.houseofel.builder.region;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

/**
 * Clicking (or tapping, on Bedrock) a block with a Surveyor's Rod marks the next unset
 * point of the pending region — point A first, then point B. Left- and right-click are
 * treated identically: Bedrock's touch controls don't map cleanly onto discrete mouse
 * buttons, so distinguishing them isn't reliable there. Confirming or cancelling once
 * both points are set happens via chat instead — see {@link RegionSelectionService}.
 */
public final class SurveyorListener implements Listener {

    private final SurveyorRod rod;
    private final RegionSelectionService regionService;

    public SurveyorListener(SurveyorRod rod, RegionSelectionService regionService) {
        this.rod = rod;
        this.regionService = regionService;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getClickedBlock() == null) {
            return;
        }
        if (!rod.isSurveyorRod(event.getPlayer().getInventory().getItemInMainHand())) {
            return;
        }

        Action action = event.getAction();
        if (action != Action.LEFT_CLICK_BLOCK && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        event.setCancelled(true);
        regionService.onClick(event.getPlayer(), event.getClickedBlock().getLocation());
    }
}
