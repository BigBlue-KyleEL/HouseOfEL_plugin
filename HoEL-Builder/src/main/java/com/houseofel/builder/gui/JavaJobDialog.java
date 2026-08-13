package com.houseofel.builder.gui;

import com.houseofel.builder.npc.BuilderNpcService;
import com.houseofel.builder.npc.Specialization;
import com.houseofel.builder.region.RegionSelectionService;
import com.houseofel.builder.title.FlavorLadder;
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
import net.kyori.adventure.text.format.TextDecoration;
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

    public void open(Player player, NPC npc, Specialization specialization, int level) {
        showTaskTypeStep(player, npc, specialization, level);
    }

    /** Shown instead of the usual flow when the concurrency ceiling is full. */
    public void showBusy(Player player, NPC npc, String eta) {
        player.showDialog(Dialog.create(factory -> factory.empty()
                .base(DialogBase.builder(Component.text(BuilderNpcService.baseNameOf(npc) + " is busy"))
                        .body(List.of(DialogBody.plainMessage(Component.text(
                                "Can't help you right now, kiddo — every pair of hands is spoken "
                                        + "for. Check back in " + eta + "."))))
                        .build())
                .type(DialogType.notice(ActionButton.create(Component.text("Okay"), null, 100, null)))));
    }

    private void showTaskTypeStep(Player player, NPC npc, Specialization specialization, int level) {
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

        // Name in the header, flavour line as a body block beneath it. The wide pad
        // between the two is vanilla dialog spacing and isn't adjustable from here —
        // both alternatives were tried in-game and were worse: a newline in the TITLE
        // renders as an unprintable box glyph mid-line (a header is strictly one line),
        // and moving BOTH lines into the body sits them together but leaves the title
        // bar visibly empty. The gap is the least-bad of the three. Absent for an
        // unassigned or still-green Helper, which just gets the header on its own.
        String flavor = FlavorLadder.flavorFor(specialization, level);
        DialogBase.Builder base = DialogBase.builder(Component.text(entryTitle(npc, specialization)));
        if (flavor != null) {
            base.body(List.of(DialogBody.plainMessage(
                    Component.text(flavor).decorate(TextDecoration.ITALIC))));
        }

        player.showDialog(Dialog.create(factory -> factory.empty()
                .base(base.build())
                .type(DialogType.multiAction(buttons).build())));
    }

    /** "<Name> — <Specialization>", or just "<Name>" for a pre-1-F NPC with no assignment. */
    private static String entryTitle(NPC npc, Specialization specialization) {
        String name = BuilderNpcService.baseNameOf(npc);
        return specialization == null ? name : name + " — " + specialization.label();
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
                .base(DialogBase.builder(Component.text(BuilderNpcService.baseNameOf(npc) + " — Target")).build())
                .type(DialogType.multiAction(buttons).build())));
    }

    private void showConfirmStep(Player player, NPC npc, TaskType taskType, Target target) {
        player.showDialog(Dialog.create(factory -> factory.empty()
                .base(DialogBase.builder(Component.text(
                                BuilderNpcService.baseNameOf(npc) + " — " + taskType.label() + " " + target.label()))
                        .inputs(List.of(
                                DialogInput.bool("surfaceOnly", Component.text("Surface Only")).initial(true).build(),
                                DialogInput.bool("storeInChest", Component.text("Store in Chest")).initial(true).build()
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

        // beginJob() hands out an item and drives the region-selection flow — run it on
        // the main thread like every other dialog-triggered Bukkit call here.
        Bukkit.getScheduler().runTask(plugin, () ->
                regionService.beginJob(player, npc, taskType, target, storeInChest, surfaceOnly));
    }
}
