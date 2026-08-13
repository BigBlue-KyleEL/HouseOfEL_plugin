package com.houseofel.builder.npc;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.citizensnpcs.api.npc.NPC;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

/**
 * "&lt;Helper's name&gt; report" reads back its level, specialization, and banked Toil —
 * read-only, and works whether or not the Helper currently has an active job (unlike
 * {@link HelperCommandListener}'s pause/resume/cancel words, which only fire against a
 * running job). Built as the Phase 1-F chassis's verification tool: confirming a ticket
 * actually banked Toil otherwise means grinding 512 real blocks per check, or reading the
 * SQLite file by hand. Deliberately read-only — the framework's Rule 14 forbids any
 * Toil-injection path, so there's no "set/grant" counterpart, on purpose.
 */
public final class HelperStatusListener implements Listener {

    private static final String TRIGGER_WORD = "report";

    private final Plugin plugin;
    private final BuilderNpcService npcService;
    private final HelperLevelService levelService;

    public HelperStatusListener(Plugin plugin, BuilderNpcService npcService, HelperLevelService levelService) {
        this.plugin = plugin;
        this.npcService = npcService;
        this.levelService = levelService;
    }

    @EventHandler
    public void onChat(AsyncChatEvent event) {
        String message = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();

        NPC npc = npcService.matchHelper(message);
        if (npc == null) {
            return;
        }
        String rest = message.substring(BuilderNpcService.baseNameOf(npc).length()).trim().toLowerCase();
        if (!rest.equals(TRIGGER_WORD)) {
            return;
        }

        event.setCancelled(true);
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> {
            Specialization specialization = levelService.specializationOf(npc);
            String specLabel = specialization == null ? "unassigned" : specialization.label();
            int level = levelService.levelOf(npc);
            int toil = levelService.bankedToilOf(npc);
            player.sendMessage(Component.text(
                    BuilderNpcService.baseNameOf(npc) + ": Level " + level + " " + specLabel
                            + ", " + toil + " Toil banked.",
                    NamedTextColor.AQUA));
        });
    }
}
