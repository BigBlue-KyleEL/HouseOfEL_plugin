package com.houseofel.builder.command;

import com.houseofel.builder.npc.BuilderNpcService;
import com.houseofel.builder.region.RegionSelectionService;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class BuilderCommand implements CommandExecutor {

    private static final String SPAWN_PERMISSION = "houseofel.builder.spawn";

    private final BuilderNpcService npcService;
    private final RegionSelectionService regionService;

    public BuilderCommand(BuilderNpcService npcService, RegionSelectionService regionService) {
        this.npcService = npcService;
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
        NPC npc = npcService.spawnHelper(player.getLocation(), "Helper");
        player.sendMessage("Spawned Helper NPC '" + npc.getName() + "' (#" + npc.getId() + ").");
    }
}
