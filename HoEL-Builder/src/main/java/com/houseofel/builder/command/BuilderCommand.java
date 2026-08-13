package com.houseofel.builder.command;

import com.houseofel.builder.gui.BedrockJobForm;
import com.houseofel.builder.npc.SpecializationDialog;
import com.houseofel.builder.npc.SpecializationForm;
import com.houseofel.builder.region.RegionSelectionService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class BuilderCommand implements CommandExecutor {

    private static final String SPAWN_PERMISSION = "houseofel.builder.spawn";

    private final SpecializationDialog specializationDialog;
    private final SpecializationForm specializationForm;
    private final RegionSelectionService regionService;

    public BuilderCommand(SpecializationDialog specializationDialog, SpecializationForm specializationForm,
                           RegionSelectionService regionService) {
        this.specializationDialog = specializationDialog;
        this.specializationForm = specializationForm;
        this.regionService = regionService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command can only be used in-game.");
            return true;
        }
        if (args.length != 1) {
            player.sendMessage("Usage: /builder spawn|confirm|cancel");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "spawn" -> spawn(player);
            case "confirm" -> regionService.confirmPending(player);
            case "cancel" -> regionService.cancelPending(player);
            default -> player.sendMessage("Usage: /builder spawn|confirm|cancel");
        }
        return true;
    }

    private void spawn(Player player) {
        if (!player.hasPermission(SPAWN_PERMISSION)) {
            player.sendMessage("You don't have permission to spawn a Helper NPC.");
            return;
        }
        if (BedrockJobForm.isBedrockPlayer(player)) {
            specializationForm.open(player, player.getLocation());
        } else {
            specializationDialog.open(player, player.getLocation());
        }
    }
}
