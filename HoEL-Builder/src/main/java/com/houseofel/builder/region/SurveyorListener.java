package com.houseofel.builder.region;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

/**
 * Left/right-click with a Surveyor's Rod marks point A / point B of a job region.
 * Once both points are set the rod locks, and the same clicks confirm/cancel instead.
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

        Player player = event.getPlayer();
        Action action = event.getAction();

        if (action == Action.LEFT_CLICK_BLOCK) {
            event.setCancelled(true);
            regionService.onLeftClick(player, event.getClickedBlock().getLocation());
        } else if (action == Action.RIGHT_CLICK_BLOCK) {
            event.setCancelled(true);
            regionService.onRightClick(player, event.getClickedBlock().getLocation());
        }
    }
}
