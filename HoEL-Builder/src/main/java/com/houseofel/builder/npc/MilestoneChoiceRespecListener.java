package com.houseofel.builder.npc;

import com.houseofel.builder.choice.MilestoneChoiceOption;
import com.houseofel.builder.choice.MilestoneChoiceRegistry;
import com.houseofel.builder.choice.MilestoneChoiceStore;
import com.houseofel.builder.gui.BedrockJobForm;
import com.houseofel.builder.gui.MilestoneChoiceDialog;
import com.houseofel.builder.gui.MilestoneChoiceForm;
import com.houseofel.builder.toil.LevelCurve;
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

import java.util.List;

/**
 * {@code "<Name> respec"} reopens the Choice picker for a milestone this Helper has
 * ALREADY decided, so it can be reconsidered — the deliberate, separate trigger for that
 * (see {@link BuilderNpcListener}'s own comment on why an already-made choice doesn't
 * hijack every future right-click the way a pending one does). Actually applying a new
 * pick still goes through the GUI and {@code MilestoneChoiceService}'s guard chain
 * (ownership, cooldown, cost) — this listener only ever opens the picker, it never
 * changes anything itself.
 */
public final class MilestoneChoiceRespecListener implements Listener {

    private static final String TRIGGER_WORD = "respec";

    private final Plugin plugin;
    private final BuilderNpcService npcService;
    private final HelperLevelService levelService;
    private final MilestoneChoiceStore choiceStore;
    private final MilestoneChoiceDialog choiceDialog;
    private final MilestoneChoiceForm choiceForm;

    public MilestoneChoiceRespecListener(Plugin plugin, BuilderNpcService npcService, HelperLevelService levelService,
                                          MilestoneChoiceStore choiceStore, MilestoneChoiceDialog choiceDialog,
                                          MilestoneChoiceForm choiceForm) {
        this.plugin = plugin;
        this.npcService = npcService;
        this.levelService = levelService;
        this.choiceStore = choiceStore;
        this.choiceDialog = choiceDialog;
        this.choiceForm = choiceForm;
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
        Bukkit.getScheduler().runTask(plugin, () -> openRespec(player, npc));
    }

    private void openRespec(Player player, NPC npc) {
        Specialization specialization = levelService.specializationOf(npc);
        int level = levelService.levelOf(npc);
        String name = BuilderNpcService.baseNameOf(npc);

        // Highest already-decided Choice level this Helper has reached — the most likely
        // thing a player means by "respec" once there's more than one Choice slot (8, 16).
        int target = 0;
        for (int candidate = level; candidate >= 1; candidate--) {
            if (!LevelCurve.isChoiceLevel(candidate)) {
                continue;
            }
            if (choiceStore.find(npc.getUniqueId(), candidate) != null) {
                target = candidate;
                break;
            }
        }
        if (target == 0) {
            player.sendMessage(Component.text(
                    name + " hasn't made a choice to reconsider yet.", NamedTextColor.RED));
            return;
        }

        List<MilestoneChoiceOption> options = MilestoneChoiceRegistry.optionsFor(specialization, target);
        if (BedrockJobForm.isBedrockPlayer(player)) {
            choiceForm.open(player, npc, target, options);
        } else {
            choiceDialog.open(player, npc, target, options);
        }
    }
}
