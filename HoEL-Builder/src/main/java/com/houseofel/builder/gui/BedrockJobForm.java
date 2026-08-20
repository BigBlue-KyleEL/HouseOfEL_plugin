package com.houseofel.builder.gui;

import com.houseofel.builder.choice.MilestoneChoiceStore;
import com.houseofel.builder.death.DeathRecordStore;
import com.houseofel.builder.npc.BuilderNpcService;
import com.houseofel.builder.npc.HelperLevelService;
import com.houseofel.builder.npc.Specialization;
import com.houseofel.builder.region.RegionSelectionService;
import com.houseofel.builder.title.FlavorLadder;
import net.citizensnpcs.api.npc.NPC;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.geysermc.cumulus.form.CustomForm;
import org.geysermc.cumulus.form.SimpleForm;
import org.geysermc.floodgate.api.FloodgateApi;
import org.geysermc.floodgate.api.player.FloodgatePlayer;

/**
 * Bedrock's equivalent of {@link JavaJobDialog} — a native Form instead of a chest-style
 * inventory. Bedrock's touch controls treat any inventory click as a two-step "pick up,
 * then place" gesture (that's just how Bedrock players interact with any inventory, not
 * a bug), which makes a custom chest GUI awkward to use there. A Form is a single
 * submission with no pickup/place semantics at all, so this is the real fix rather than
 * a workaround.
 */
public final class BedrockJobForm {

    private final Plugin plugin;
    private final RegionSelectionService regionService;
    private final DeathRecordStore deathRecordStore;
    private final MilestoneChoiceStore choiceStore;
    private final HelperLevelService levelService;

    public BedrockJobForm(Plugin plugin, RegionSelectionService regionService, DeathRecordStore deathRecordStore,
                           MilestoneChoiceStore choiceStore, HelperLevelService levelService) {
        this.plugin = plugin;
        this.regionService = regionService;
        this.deathRecordStore = deathRecordStore;
        this.choiceStore = choiceStore;
        this.levelService = levelService;
    }

    public static boolean isBedrockPlayer(Player player) {
        return FloodgateApi.getInstance().isFloodgatePlayer(player.getUniqueId());
    }

    public void open(Player player, NPC npc, Specialization specialization, int level) {
        FloodgatePlayer floodgatePlayer = floodgatePlayer(player);
        if (floodgatePlayer == null) {
            return;
        }

        // Bracket tags (scar choice, Choice-slot picks) are built by the shared
        // HelperTitleFormatter — see it for why this isn't inlined here.
        String title = HelperTitleFormatter.dispatchTitleOf(npc, specialization, deathRecordStore, choiceStore);
        String flavor = FlavorLadder.flavorFor(specialization, level);
        String hearts = HelperTitleFormatter.heartsFor(npc);
        // Same shared line JavaJobDialog uses — see xpBarFor. Absent for an unassigned Helper.
        String xpBar = HelperTitleFormatter.xpBarFor(npc, specialization, levelService);
        // Same shared line the report command and JavaJobDialog use — see rustLineFor.
        // Replaces the always-on world-space boss bar removed 2026-08-19 (Kyle's call).
        String rustLine = HelperTitleFormatter.rustLineFor(npc, deathRecordStore);

        CustomForm.Builder form = CustomForm.builder().title(title);
        // Absent for an unassigned/still-green Helper (flavor/XP bar) or a non-rusted one
        // (rust line) — no empty label in either case. A label OCCUPIES a response slot (it
        // comes back as null), so its presence shifts every following component's index —
        // hence answerOffset below. Verified against Cumulus' own response implementation;
        // getDropdown/getToggle index absolutely and do not skip labels.
        int answerOffset = 0;
        if (flavor != null) {
            form.label(flavor);
            answerOffset++;
        }
        if (hearts != null) {
            form.label(hearts);
            answerOffset++;
        }
        if (xpBar != null) {
            form.label(xpBar);
            answerOffset++;
        }
        if (rustLine != null) {
            form.label(rustLine);
            answerOffset++;
        }
        int offset = answerOffset;
        // Task Type and Target are both dropdowns on this ONE form, submitted together —
        // unlike JavaJobDialog's step-by-step wizard, there's no point where Task Type has
        // already been picked before Target renders. "Anything" (Groundworker's level-3
        // verb) is gated on specialization/level only, not on whichever Task Type the
        // player ends up picking — the same looseness this form already has for any other
        // Task Type/Target mismatch (e.g. Wheat on a Groundworker), which already just
        // executes without earning Toil rather than being blocked.
        Target[] availableTargets = targetsFor(specialization, level);
        // Depth section — Quarrying only, but Cumulus's CustomForm has no conditional/
        // reactive fields at all (confirmed against the real jar, 2026-08-20: no
        // "enabled if" hook anywhere, everything submits as one flat atomic batch), and
        // Task Type itself is just another dropdown on this SAME form, not decided until
        // submit. So these fields always render regardless of which Task Type ends up
        // picked, and onSubmit() below only reads/uses them when Task Type resolves to
        // QUARRY — same trade-off as no true divider existing either (see the "— Depth —"
        // label immediately below).
        floodgatePlayer.sendForm(
                form.dropdown("Task Type", labelsOf(TaskType.values(), specialization))
                        .dropdown("Target", labelsOf(availableTargets))
                        .toggle("Surface Only", true)
                        .toggle("Store in Chest", true)
                        .label("— Depth — (Quarrying only)")
                        .dropdown("Depth Mode", "Level", "Coordinates")
                        .input("Blocks deep", "e.g. 12")
                        .input("Target Y coordinate", "e.g. 64")
                        .validResultHandler(response -> onSubmit(player, npc, response, offset, availableTargets))
                        .closedOrInvalidResultHandler(() -> onClosed(player))
                        .build());
    }

    /** Every {@link Target} the dropdown should offer — "Anything" only for a level-3+ Groundworker. */
    private Target[] targetsFor(Specialization specialization, int level) {
        boolean showAnything = specialization == Specialization.GROUNDWORKER && level >= 3;
        if (showAnything) {
            return Target.values();
        }
        Target[] withoutAny = new Target[Target.values().length - 1];
        int i = 0;
        for (Target candidate : Target.values()) {
            if (candidate != Target.ANY_EARTH) {
                withoutAny[i++] = candidate;
            }
        }
        return withoutAny;
    }

    /**
     * Shown instead of the usual task dropdowns when this Helper is already mid-job — a
     * read-only status view (flavour/hearts/XP bar/Rust), same content JavaJobDialog's
     * equivalent shows, since there's nothing to dispatch right now. Kyle's report,
     * 2026-08-20: right-clicking a busy Helper was still opening the full job form.
     */
    public void showStatusOnly(Player player, NPC npc, Specialization specialization, int level) {
        FloodgatePlayer floodgatePlayer = floodgatePlayer(player);
        if (floodgatePlayer == null) {
            return;
        }

        String title = HelperTitleFormatter.dispatchTitleOf(npc, specialization, deathRecordStore, choiceStore);
        StringBuilder content = new StringBuilder(BuilderNpcService.baseNameOf(npc) + " is busy working right now.");
        String flavor = FlavorLadder.flavorFor(specialization, level);
        String hearts = HelperTitleFormatter.heartsFor(npc);
        String xpBar = HelperTitleFormatter.xpBarFor(npc, specialization, levelService);
        String rustLine = HelperTitleFormatter.rustLineFor(npc, deathRecordStore);
        if (flavor != null) {
            content.append("\n\n").append(flavor);
        }
        if (hearts != null) {
            content.append("\n").append(hearts);
        }
        if (xpBar != null) {
            content.append("\n").append(xpBar);
        }
        if (rustLine != null) {
            content.append("\n").append(rustLine);
        }

        floodgatePlayer.sendForm(
                SimpleForm.builder()
                        .title(title)
                        .content(content.toString())
                        .button("Okay")
                        .build());
    }

    /** Shown instead of the usual flow when the concurrency ceiling is full. */
    public void showBusy(Player player, NPC npc, String eta) {
        FloodgatePlayer floodgatePlayer = floodgatePlayer(player);
        if (floodgatePlayer == null) {
            return;
        }

        floodgatePlayer.sendForm(
                SimpleForm.builder()
                        .title(BuilderNpcService.baseNameOf(npc) + " is busy")
                        .content("Can't help you right now, kiddo — every pair of hands is spoken "
                                + "for. Check back in " + eta + ".")
                        .button("Okay")
                        .build());
    }

    private FloodgatePlayer floodgatePlayer(Player player) {
        return FloodgateApi.getInstance().getPlayer(player.getUniqueId());
    }

    private void onSubmit(Player player, NPC npc, org.geysermc.cumulus.response.CustomFormResponse response,
                           int offset, Target[] availableTargets) {
        // A dropdown's answer is the selected index (an int), not its label — reading it
        // as a String is what actually threw the ClassCastException on submit.
        // `offset` accounts for the optional flavour label ahead of these — see open().
        // Read back against the SAME target list the form was built from (availableTargets),
        // not the full Target.values() — "Anything" may have been omitted at build time.
        TaskType taskType = TaskType.values()[response.getDropdown(offset)];
        Target target = availableTargets[response.getDropdown(offset + 1)];
        boolean surfaceOnly = response.getToggle(offset + 2);
        boolean storeInChest = response.getToggle(offset + 3);

        // Depth fields always render (see open()'s comment on why) but only mean anything
        // once Task Type has actually resolved to QUARRY — reading them for any other
        // task type would just be acting on values the player had no reason to fill in.
        Integer requestedLevels = null;
        Integer requestedTargetY = null;
        if (taskType == TaskType.QUARRY) {
            // offset+4 is the "— Depth —" label itself — occupies a slot exactly like the
            // flavour/hearts/xpBar/rustLine labels above, confirmed against the real
            // Cumulus response implementation the same way those already were.
            boolean coordinatesMode = response.getDropdown(offset + 5) == 1;
            if (coordinatesMode) {
                requestedTargetY = parseFormInt(player, npc, response.getInput(offset + 7));
            } else {
                requestedLevels = parseFormInt(player, npc, response.getInput(offset + 6));
            }
            if (requestedLevels == null && requestedTargetY == null) {
                // parseFormInt already messaged the player about the bad number.
                return;
            }
        }

        Integer finalRequestedLevels = requestedLevels;
        Integer finalRequestedTargetY = requestedTargetY;
        // The form response arrives off the main thread — beginJob() hands out an item
        // and drives the region-selection flow, both of which need to run on it.
        Bukkit.getScheduler().runTask(plugin, () ->
                regionService.beginJob(player, npc, taskType, target, storeInChest, surfaceOnly,
                        finalRequestedLevels, finalRequestedTargetY));
    }

    /**
     * Cumulus's input fields are unvalidated free text (confirmed against the real jar,
     * 2026-08-20 — no numeric-only mode exists on InputComponent) — parses it, or
     * messages the player and returns null on anything non-numeric.
     */
    private Integer parseFormInt(Player player, NPC npc, String rawText) {
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

    private void onClosed(Player player) {
        Bukkit.getScheduler().runTask(plugin, () ->
                player.sendMessage(Component.text("No job configured.", NamedTextColor.RED)));
    }

    /**
     * Spec-distinct styling's Bedrock half: {@code CustomForm} dropdown options are plain
     * text with no color/tooltip lever (see the Custom GUI Pathway reference note), so the
     * task type matching this Helper's specialization gets a text marker instead of
     * JavaJobDialog's color+tooltip. Absent entirely for an unassigned Helper.
     */
    private String[] labelsOf(TaskType[] types, Specialization specialization) {
        String[] labels = new String[types.length];
        for (int i = 0; i < types.length; i++) {
            boolean isSpecialty = specialization != null && types[i] == specialization.taskType();
            labels[i] = isSpecialty ? types[i].label() + " ★ (specialty)" : types[i].label();
        }
        return labels;
    }

    private String[] labelsOf(Target[] targets) {
        String[] labels = new String[targets.length];
        for (int i = 0; i < targets.length; i++) {
            labels[i] = targets[i].label();
        }
        return labels;
    }
}
