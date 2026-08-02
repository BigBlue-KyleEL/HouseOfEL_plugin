package com.houseofel.builder.gui;

import com.houseofel.builder.region.RegionSelectionService;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.citizensnpcs.api.npc.NPC;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;

/**
 * Java's equivalent of {@link BedrockJobForm} — native Dialogs (Minecraft's own
 * server-driven menu system) instead of the old chest-style inventory. Task type and
 * target are each their own screen of direct-pick buttons rather than a single-option
 * "dropdown" input, which in vanilla is actually a cycle-through button, not a real
 * list — awkward once there are more than a couple of options, since missing the one
 * you want means cycling all the way back around. A short wizard (pick task type, pick
 * target, then toggles + confirm) stays a direct pick at every step regardless of how
 * many options exist.
 */
public final class JavaJobDialog {

    private final Plugin plugin;
    private final RegionSelectionService regionService;

    public JavaJobDialog(Plugin plugin, RegionSelectionService regionService) {
        this.plugin = plugin;
        this.regionService = regionService;
    }

    public void open(Player player, NPC npc) {
        showTaskTypeStep(player, npc);
    }

    /** Shown instead of the usual flow when the concurrency ceiling is full. */
    public void showBusy(Player player, NPC npc, String eta) {
        player.showDialog(Dialog.create(factory -> factory.empty()
                .base(DialogBase.builder(Component.text(npc.getName() + " is busy"))
                        .body(List.of(DialogBody.plainMessage(Component.text(
                                "Can't help you right now, kiddo — every pair of hands is spoken "
                                        + "for. Check back in " + eta + "."))))
                        .build())
                .type(DialogType.notice(ActionButton.create(Component.text("Okay"), null, 100, null)))));
    }

    private void showTaskTypeStep(Player player, NPC npc) {
        List<ActionButton> buttons = new ArrayList<>();
        for (TaskType type : TaskType.values()) {
            buttons.add(ActionButton.create(Component.text(type.label()), null, 150,
                    DialogAction.customClick(
                            (view, audience) -> {
                                if (audience instanceof Player p) {
                                    // showDialog sends a packet — stays on the main thread,
                                    // same as every other Bukkit call triggered from a click.
                                    Bukkit.getScheduler().runTask(plugin, () -> showTargetStep(p, npc, type));
                                }
                            },
                            ClickCallback.Options.builder().build())));
        }

        player.showDialog(Dialog.create(factory -> factory.empty()
                .base(DialogBase.builder(Component.text(npc.getName() + " — Task Type")).build())
                .type(DialogType.multiAction(buttons).build())));
    }

    private void showTargetStep(Player player, NPC npc, TaskType taskType) {
        List<ActionButton> buttons = new ArrayList<>();
        for (Target target : Target.values()) {
            buttons.add(ActionButton.create(Component.text(target.label()), null, 150,
                    DialogAction.customClick(
                            (view, audience) -> {
                                if (audience instanceof Player p) {
                                    Bukkit.getScheduler().runTask(plugin,
                                            () -> showConfirmStep(p, npc, taskType, target));
                                }
                            },
                            ClickCallback.Options.builder().build())));
        }

        player.showDialog(Dialog.create(factory -> factory.empty()
                .base(DialogBase.builder(Component.text(npc.getName() + " — Target")).build())
                .type(DialogType.multiAction(buttons).build())));
    }

    private void showConfirmStep(Player player, NPC npc, TaskType taskType, Target target) {
        player.showDialog(Dialog.create(factory -> factory.empty()
                .base(DialogBase.builder(Component.text(
                                npc.getName() + " — " + taskType.label() + " " + target.label()))
                        .inputs(List.of(
                                DialogInput.bool("surfaceOnly", Component.text("Surface Only")).initial(true).build(),
                                DialogInput.bool("storeInChest", Component.text("Store in Chest")).initial(true).build(),
                                DialogInput.bool("griefPlayerPlaced", Component.text("Grief Player-Placed Items"))
                                        .initial(false).build()
                        ))
                        .build())
                .type(DialogType.confirmation(
                        ActionButton.create(Component.text("Send to Work"), null, 150,
                                DialogAction.customClick(
                                        (view, audience) -> {
                                            if (audience instanceof Player p) {
                                                onSubmit(p, npc, taskType, target, view);
                                            }
                                        },
                                        ClickCallback.Options.builder().build())),
                        ActionButton.create(Component.text("Cancel"), null, 150, null)))));
    }

    private void onSubmit(Player player, NPC npc, TaskType taskType, Target target, DialogResponseView view) {
        boolean surfaceOnly = Boolean.TRUE.equals(view.getBoolean("surfaceOnly"));
        boolean storeInChest = Boolean.TRUE.equals(view.getBoolean("storeInChest"));
        boolean griefPlayerPlaced = Boolean.TRUE.equals(view.getBoolean("griefPlayerPlaced"));

        // beginJob() hands out an item and drives the region-selection flow — run it on
        // the main thread like every other dialog-triggered Bukkit call here.
        Bukkit.getScheduler().runTask(plugin, () ->
                regionService.beginJob(player, npc, taskType, target, storeInChest, surfaceOnly, griefPlayerPlaced));
    }
}
