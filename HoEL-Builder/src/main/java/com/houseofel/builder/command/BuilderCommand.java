package com.houseofel.builder.command;

import com.houseofel.builder.npc.BuilderNpcService;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class BuilderCommand implements CommandExecutor {

    private static final String SPAWN_PERMISSION = "houseofel.builder.spawn";

    private final BuilderNpcService npcService;

    public BuilderCommand(BuilderNpcService npcService) {
        this.npcService = npcService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command can only be used in-game.");
            return true;
        }
        if (!player.hasPermission(SPAWN_PERMISSION)) {
            player.sendMessage("You don't have permission to spawn a Helper NPC.");
            return true;
        }
        if (args.length != 1 || !args[0].equalsIgnoreCase("spawn")) {
            player.sendMessage("Usage: /builder spawn");
            return true;
        }

        NPC npc = npcService.spawnHelper(player.getLocation(), "Helper");
        player.sendMessage("Spawned Helper NPC '" + npc.getName() + "' (#" + npc.getId() + ").");
        return true;
    }
}
