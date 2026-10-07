package com.houseofel.builder.gui;

import com.houseofel.builder.choice.MilestoneChoiceOption;
import com.houseofel.builder.choice.MilestoneChoiceRecord;
import com.houseofel.builder.choice.MilestoneChoiceService;
import com.houseofel.builder.choice.MilestoneChoiceStore;
import com.houseofel.builder.npc.BuilderNpcService;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.geysermc.cumulus.form.SimpleForm;
import org.geysermc.floodgate.api.FloodgateApi;
import org.geysermc.floodgate.api.player.FloodgatePlayer;

import java.util.List;

/**
 * Bedrock's equivalent of {@link MilestoneChoiceDialog}. Confirmed against the real
 * Cumulus API (not assumed): a Bedrock {@code SimpleForm} button has no separate
 * description field at all, only a label — so both options' descriptions go in the
 * form's shared content text instead, above the buttons, giving a Bedrock player the
 * same information a Java player gets from hovering a button, just laid out per that
 * platform's real constraints. The pick/respec guard chain lives in
 * {@link MilestoneChoiceService}, shared with {@link MilestoneChoiceDialog}.
 */
public final class MilestoneChoiceForm {

    private final Plugin plugin;
    private final MilestoneChoiceStore choiceStore;
    private final MilestoneChoiceService choiceService;

    public MilestoneChoiceForm(Plugin plugin, MilestoneChoiceStore choiceStore, MilestoneChoiceService choiceService) {
        this.plugin = plugin;
        this.choiceStore = choiceStore;
        this.choiceService = choiceService;
    }

    public void open(Player player, NPC npc, int level, List<MilestoneChoiceOption> options) {
        FloodgatePlayer floodgatePlayer = FloodgateApi.getInstance().getPlayer(player.getUniqueId());
        if (floodgatePlayer == null) {
            return;
        }

        MilestoneChoiceRecord existing = choiceStore.find(npc.getUniqueId(), level);
        String name = BuilderNpcService.baseNameOf(npc);
        String title = existing == null ? name + " — Choose a Path" : name + " — Reconsider Your Path";

        StringBuilder content = new StringBuilder();
        for (MilestoneChoiceOption option : options) {
            boolean isCurrent = existing != null && existing.choice().equals(option.storedValue());
            content.append(option.label()).append(isCurrent ? " (current)" : "")
                    .append(": ").append(option.description()).append("\n\n");
        }

        SimpleForm.Builder builder = SimpleForm.builder().title(title).content(content.toString().trim());
        for (MilestoneChoiceOption option : options) {
            builder.button(option.label());
        }
        builder.validResultHandler(response -> {
            MilestoneChoiceOption chosen = options.get(response.clickedButtonId());
            // The form response arrives off the main thread — attempt() reads/writes
            // SQLite and sends a message, same as every other form/dialog handler here.
            Bukkit.getScheduler().runTask(plugin, () -> choiceService.attempt(player, npc, level, chosen));
        });
        floodgatePlayer.sendForm(builder.build());
    }
}
