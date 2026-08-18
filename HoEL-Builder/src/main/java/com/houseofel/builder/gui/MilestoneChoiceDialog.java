package com.houseofel.builder.gui;

import com.houseofel.builder.choice.MilestoneChoiceOption;
import com.houseofel.builder.choice.MilestoneChoiceRecord;
import com.houseofel.builder.choice.MilestoneChoiceService;
import com.houseofel.builder.choice.MilestoneChoiceStore;
import com.houseofel.builder.npc.BuilderNpcService;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.citizensnpcs.api.npc.NPC;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;

/**
 * Java's native-Dialog picker for a specialization's Choice-slot milestones (level 8,
 * eventually 16 too) — shown from {@code BuilderNpcListener}'s right-click branch when a
 * pending choice exists, or from the {@code "<Name> respec"} chat trigger once one's
 * already been made. Mirrors {@link com.houseofel.builder.npc.SpecializationDialog}'s
 * shape closely; the actual pick/respec guard chain lives in {@link MilestoneChoiceService}
 * so it's identical between this and {@link MilestoneChoiceForm}.
 */
public final class MilestoneChoiceDialog {

    private final Plugin plugin;
    private final MilestoneChoiceStore choiceStore;
    private final MilestoneChoiceService choiceService;

    public MilestoneChoiceDialog(Plugin plugin, MilestoneChoiceStore choiceStore, MilestoneChoiceService choiceService) {
        this.plugin = plugin;
        this.choiceStore = choiceStore;
        this.choiceService = choiceService;
    }

    public void open(Player player, NPC npc, int level, List<MilestoneChoiceOption> options) {
        MilestoneChoiceRecord existing = choiceStore.find(npc.getUniqueId(), level);
        String name = BuilderNpcService.baseNameOf(npc);
        String title = existing == null ? name + " — Choose a Path" : name + " — Reconsider Your Path";

        // Descriptions also go in the body, not just each button's hover tooltip below —
        // a tooltip is real per-button text on Java, but hover-only, so this keeps both
        // options readable without requiring the player to hover each one first.
        List<DialogBody> body = new ArrayList<>();
        for (MilestoneChoiceOption option : options) {
            body.add(DialogBody.plainMessage(
                    Component.text(option.label() + ": " + option.description(), NamedTextColor.GRAY)));
        }

        List<ActionButton> buttons = new ArrayList<>();
        for (MilestoneChoiceOption option : options) {
            boolean isCurrent = existing != null && existing.choice().equals(option.storedValue());
            String label = option.label() + (isCurrent ? " (current)" : "");
            buttons.add(ActionButton.create(Component.text(label), Component.text(option.description()), 200,
                    DialogAction.customClick(
                            (view, audience) -> {
                                if (audience instanceof Player p) {
                                    // showDialog sends a packet — stays on the main thread,
                                    // same as every other Bukkit call triggered from a click.
                                    Bukkit.getScheduler().runTask(plugin,
                                            () -> choiceService.attempt(p, npc, level, option));
                                }
                            },
                            ClickCallback.Options.builder().build())));
        }

        player.showDialog(Dialog.create(factory -> factory.empty()
                .base(DialogBase.builder(Component.text(title)).body(body).build())
                .type(DialogType.multiAction(buttons).build())));
    }
}
