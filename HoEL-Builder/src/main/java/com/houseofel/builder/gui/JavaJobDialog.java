package com.houseofel.builder.gui;

import com.houseofel.builder.choice.MilestoneChoiceStore;
import com.houseofel.builder.death.DeathRecordStore;
import com.houseofel.builder.npc.BuilderNpcService;
import com.houseofel.builder.npc.HelperLevelService;
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
import net.kyori.adventure.text.format.NamedTextColor;
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
    private final DeathRecordStore deathRecordStore;
    private final MilestoneChoiceStore choiceStore;
    private final HelperLevelService levelService;

    public JavaJobDialog(Plugin plugin, RegionSelectionService regionService, DeathRecordStore deathRecordStore,
                          MilestoneChoiceStore choiceStore, HelperLevelService levelService) {
        this.plugin = plugin;
        this.regionService = regionService;
        this.deathRecordStore = deathRecordStore;
        this.choiceStore = choiceStore;
        this.levelService = levelService;
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
        String name = BuilderNpcService.baseNameOf(npc);
        List<ActionButton> buttons = new ArrayList<>();
        for (TaskType type : TaskType.values()) {
            // Spec-distinct styling: the task type matching this Helper's specialization
            // (its bonus-drop skill, see Specialization.taskType()) is called out; the rest
            // stay plain. Skipped entirely for an unassigned Helper — no specialization
            // means no button is "the" specialty yet. A real highlight/border around the
            // button itself isn't available — confirmed against the real ActionButton
            // interface (label/tooltip/width/action only, no background/border field);
            // vanilla renders button chrome client-side with no per-button override. Bracket
            // framing + bold is the closest real equivalent, reusing the same "[TAG]"
            // visual language the dispatch title already uses for RUSTED/WARY/Choice picks
            // — plain color alone read as too weak live (Kyle, 2026-08-19).
            boolean isSpecialty = specialization != null && type == specialization.taskType();
            Component label = isSpecialty
                    ? Component.text("[" + type.label() + "]", NamedTextColor.YELLOW).decorate(TextDecoration.BOLD)
                    : Component.text(type.label());
            Component tooltip = specialization == null ? null : Component.text(isSpecialty
                    ? name + "'s specialty — bonus drops apply here."
                    : "Not " + name + "'s specialty — no bonus drops.");
            buttons.add(ActionButton.create(label, tooltip, 150,
                    DialogAction.customClick(
                            (view, audience) -> {
                                if (audience instanceof Player p) {
                                    // showDialog sends a packet — stays on the main thread,
                                    // same as every other Bukkit call triggered from a click.
                                    Bukkit.getScheduler().runTask(plugin, () -> {
                                        if (type == TaskType.QUARRY) {
                                            // Quarryman has no material choice — it digs
                                            // everything within its own footprint, so there's
                                            // nothing for a Target step to offer. Goes to the
                                            // depth picker instead of straight to confirm.
                                            showDepthStep(p, npc, Target.ANY_EARTH);
                                        } else {
                                            showTargetStep(p, npc, type, specialization, level);
                                        }
                                    });
                                }
                            },
                            ClickCallback.Options.builder().build())));
        }

        // Name in the header, status block beneath it. The wide pad between the two is
        // vanilla dialog spacing and isn't adjustable from here — both alternatives were
        // tried in-game and were worse: a newline in the TITLE renders as an unprintable
        // box glyph mid-line (a header is strictly one line), and moving every line into
        // the body sits them together but leaves the title bar visibly empty. The gap is
        // the least-bad of the three.
        DialogBase.Builder base = DialogBase.builder(Component.text(entryTitle(npc, specialization)));
        List<DialogBody> body = statusBody(npc, specialization, level);
        if (!body.isEmpty()) {
            base.body(body);
        }

        player.showDialog(Dialog.create(factory -> factory.empty()
                .base(base.build())
                .type(DialogType.multiAction(buttons).build())));
    }

    /**
     * Shown instead of the usual task-type picker when this Helper is already mid-job —
     * same status content (flavour/hearts/XP bar/Rust), but a single "Okay" button rather
     * than dispatch options, since there's nothing to dispatch right now. Kyle's report,
     * 2026-08-20: right-clicking a busy Helper was still opening the full job menu.
     */
    public void showStatusOnly(Player player, NPC npc, Specialization specialization, int level) {
        DialogBase.Builder base = DialogBase.builder(Component.text(entryTitle(npc, specialization)));
        List<DialogBody> body = statusBody(npc, specialization, level);
        if (!body.isEmpty()) {
            base.body(body);
        }

        player.showDialog(Dialog.create(factory -> factory.empty()
                .base(base.build())
                .type(DialogType.notice(ActionButton.create(Component.text("Okay"), null, 100, null)))));
    }

    /**
     * Flavour line, hearts, XP bar, Rust line — in that order, each absent entirely when
     * not applicable (unassigned Helper, full health, not rusted) rather than shown empty.
     * Shared by both the normal dispatch menu and {@link #showStatusOnly} so the two can
     * never drift apart.
     */
    private List<DialogBody> statusBody(NPC npc, Specialization specialization, int level) {
        String flavor = FlavorLadder.flavorFor(specialization, level);
        String hearts = HelperTitleFormatter.heartsFor(npc);
        String xpBar = HelperTitleFormatter.xpBarFor(npc, specialization, levelService);
        String rustLine = HelperTitleFormatter.rustLineFor(npc, deathRecordStore);

        List<DialogBody> body = new ArrayList<>();
        if (flavor != null) {
            body.add(DialogBody.plainMessage(Component.text(flavor).decorate(TextDecoration.ITALIC)));
        }
        if (hearts != null) {
            body.add(DialogBody.plainMessage(Component.text(hearts, NamedTextColor.RED)));
        }
        if (xpBar != null) {
            body.add(DialogBody.plainMessage(Component.text(xpBar, NamedTextColor.GREEN)));
        }
        if (rustLine != null) {
            body.add(DialogBody.plainMessage(Component.text(rustLine, NamedTextColor.GOLD)));
        }
        return body;
    }

    /**
     * "<Name> — <Specialization> [WARY] [QUARRYMAN]", or just "<Name>" for a pre-1-F NPC
     * with no assignment. Bracket tags (scar choice, Choice-slot picks) are built by the
     * shared {@link HelperTitleFormatter} — see it for why this isn't inlined here.
     */
    private String entryTitle(NPC npc, Specialization specialization) {
        return HelperTitleFormatter.dispatchTitleOf(npc, specialization, deathRecordStore, choiceStore);
    }

    private void showTargetStep(Player player, NPC npc, TaskType taskType, Specialization specialization, int level) {
        List<ActionButton> buttons = new ArrayList<>();
        for (Target target : Target.values()) {
            // "Anything" (Groundworker's level-3 verb, "Clears Anything") only makes sense
            // for a Clear job, and only once the Helper has actually earned it.
            if (target == Target.ANY_EARTH
                    && !(taskType == TaskType.CLEAR && specialization == Specialization.GROUNDWORKER && level >= 3)) {
                continue;
            }
            buttons.add(ActionButton.create(Component.text(target.label()), null, 150,
                    DialogAction.customClick(
                            (view, audience) -> {
                                if (audience instanceof Player p) {
                                    Bukkit.getScheduler().runTask(plugin,
                                            () -> showConfirmStep(p, npc, taskType, target, null, null));
                                }
                            },
                            ClickCallback.Options.builder().build())));
        }

        player.showDialog(Dialog.create(factory -> factory.empty()
                .base(DialogBase.builder(Component.text(BuilderNpcService.baseNameOf(npc) + " — Target")).build())
                .type(DialogType.multiAction(buttons).build())));
    }

    /**
     * Quarry-only — how deep to go, chosen before the work area is even marked (see
     * JobExecutionService.dispatchQuarryman for why the actual number only gets validated
     * once the marked footprint is known). No separator/divider construct exists anywhere
     * in the real Dialog API (confirmed against the real jar, 2026-08-20 — DialogBody has
     * only plainMessage/item) — a styled text line is the honest closest substitute, not
     * a true rule.
     */
    private void showDepthStep(Player player, NPC npc, Target target) {
        Component depthTitle = Component.text("— Depth —", NamedTextColor.GOLD).decorate(TextDecoration.BOLD);
        ActionButton levelButton = ActionButton.create(Component.text("Level"),
                Component.text("Type how many blocks deep to go."), 150,
                DialogAction.customClick(
                        (view, audience) -> {
                            if (audience instanceof Player p) {
                                Bukkit.getScheduler().runTask(plugin, () -> showDepthLevelStep(p, npc, target));
                            }
                        },
                        ClickCallback.Options.builder().build()));
        ActionButton coordinatesButton = ActionButton.create(Component.text("Coordinates"),
                Component.text("Type the exact Y coordinate to dig down to."), 150,
                DialogAction.customClick(
                        (view, audience) -> {
                            if (audience instanceof Player p) {
                                Bukkit.getScheduler().runTask(plugin, () -> showDepthCoordinateStep(p, npc, target));
                            }
                        },
                        ClickCallback.Options.builder().build()));

        player.showDialog(Dialog.create(factory -> factory.empty()
                .base(DialogBase.builder(Component.text(BuilderNpcService.baseNameOf(npc) + " — Quarrying"))
                        .body(List.of(DialogBody.plainMessage(depthTitle)))
                        .build())
                .type(DialogType.multiAction(List.of(levelButton, coordinatesButton)).build())));
    }

    private void showDepthLevelStep(Player player, NPC npc, Target target) {
        player.showDialog(Dialog.create(factory -> factory.empty()
                .base(DialogBase.builder(Component.text(BuilderNpcService.baseNameOf(npc) + " — Depth (Level)"))
                        .inputs(List.of(DialogInput.text("levels", Component.text("Blocks deep")).build()))
                        .build())
                .type(DialogType.confirmation(
                        ActionButton.create(Component.text("Next"), null, 150,
                                DialogAction.customClick(
                                        (view, audience) -> {
                                            if (audience instanceof Player p) {
                                                Integer levels = parseDialogInt(p, npc, view.getText("levels"));
                                                if (levels != null) {
                                                    Bukkit.getScheduler().runTask(plugin, () ->
                                                            showConfirmStep(p, npc, TaskType.QUARRY, target, levels, null));
                                                }
                                            }
                                        },
                                        ClickCallback.Options.builder().build())),
                        ActionButton.create(Component.text("Cancel"), null, 150, null)))));
    }

    private void showDepthCoordinateStep(Player player, NPC npc, Target target) {
        player.showDialog(Dialog.create(factory -> factory.empty()
                .base(DialogBase.builder(Component.text(BuilderNpcService.baseNameOf(npc) + " — Depth (Coordinates)"))
                        .inputs(List.of(DialogInput.text("targetY", Component.text("Target Y coordinate")).build()))
                        .build())
                .type(DialogType.confirmation(
                        ActionButton.create(Component.text("Next"), null, 150,
                                DialogAction.customClick(
                                        (view, audience) -> {
                                            if (audience instanceof Player p) {
                                                Integer targetY = parseDialogInt(p, npc, view.getText("targetY"));
                                                if (targetY != null) {
                                                    Bukkit.getScheduler().runTask(plugin, () ->
                                                            showConfirmStep(p, npc, TaskType.QUARRY, target, null, targetY));
                                                }
                                            }
                                        },
                                        ClickCallback.Options.builder().build())),
                        ActionButton.create(Component.text("Cancel"), null, 150, null)))));
    }

    /**
     * Text-input responses arrive as a raw, unvalidated String (confirmed against the
     * real Dialog API — no numeric-only field exists) — this parses it, or messages the
     * player and returns null on anything non-numeric, same defensive shape as the
     * boolean-response handling in {@link #onSubmit}.
     */
    private Integer parseDialogInt(Player player, NPC npc, String rawText) {
        if (rawText != null) {
            try {
                return Integer.parseInt(rawText.trim());
            } catch (NumberFormatException ignored) {
                // Falls through to the message below.
            }
        }
        player.sendMessage(Component.text(
                BuilderNpcService.baseNameOf(npc) + ": That's not a whole number — try again.", NamedTextColor.RED));
        return null;
    }

    private void showConfirmStep(Player player, NPC npc, TaskType taskType, Target target,
                                  Integer requestedLevels, Integer requestedTargetY) {
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
                                                onSubmit(p, npc, taskType, target, view, requestedLevels, requestedTargetY);
                                            }
                                        },
                                        ClickCallback.Options.builder().build())),
                        ActionButton.create(Component.text("Cancel"), null, 150, null)))));
    }

    private void onSubmit(Player player, NPC npc, TaskType taskType, Target target, DialogResponseView view,
                           Integer requestedLevels, Integer requestedTargetY) {
        boolean surfaceOnly = Boolean.TRUE.equals(view.getBoolean("surfaceOnly"));
        boolean storeInChest = Boolean.TRUE.equals(view.getBoolean("storeInChest"));

        // beginJob() hands out an item and drives the region-selection flow — run it on
        // the main thread like every other dialog-triggered Bukkit call here.
        Bukkit.getScheduler().runTask(plugin, () ->
                regionService.beginJob(player, npc, taskType, target, storeInChest, surfaceOnly,
                        requestedLevels, requestedTargetY));
    }
}
