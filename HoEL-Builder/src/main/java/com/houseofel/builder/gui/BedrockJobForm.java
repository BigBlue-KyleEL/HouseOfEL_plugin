package com.houseofel.builder.gui;

import com.houseofel.builder.choice.GroundworkerL16Choice;
import com.houseofel.builder.choice.GroundworkerL8Choice;
import com.houseofel.builder.choice.MilestoneChoiceRecord;
import com.houseofel.builder.choice.MilestoneChoiceStore;
import com.houseofel.builder.death.DeathRecordStore;
import com.houseofel.builder.job.LandscapeBiome;
import com.houseofel.builder.job.LandscapeMode;
import com.houseofel.builder.npc.BuilderNpcService;
import com.houseofel.builder.npc.HelperLevelService;
import com.houseofel.builder.npc.Specialization;
import com.houseofel.builder.region.RegionSelectionService;
import com.houseofel.builder.title.FlavorLadder;
import net.citizensnpcs.api.npc.NPC;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.geysermc.cumulus.form.CustomForm;
import org.geysermc.cumulus.form.SimpleForm;
import org.geysermc.cumulus.util.FormImage;
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
        java.util.List<TaskType> jobs = taskOptionsFor(npc, specialization, level);
        SimpleForm.Builder form = SimpleForm.builder()
                .title(HelperTitleFormatter.dispatchTitleOf(npc, specialization, deathRecordStore, choiceStore))
                .content(statusContent(npc, specialization, level));
        for (TaskType type : jobs) {
            String label = switch (type) {
                case QUARRY -> "Lvl.8: Quarryman";
                case LANDSCAPE -> "Lvl.8: Landscaper";
                case COFFERDAM -> "Lvl.16: Cofferdam";
                case SHAFT_MINER -> "Lvl.16: Shaft Miner";
                default -> type.label();
            };
            if (specialization != null && type == specialization.taskType()) label += " ★ (specialty)";
            form.button(label);
        }
        form.validResultHandler(response -> onMain(player, () -> {
            int index = response.clickedButtonId();
            if (index < 0 || index >= jobs.size()) return;
            TaskType type = jobs.get(index);
            // Recheck unlocks in case the Helper changed while this form was open.
            Specialization currentSpec = levelService.specializationOf(npc);
            int currentLevel = levelService.levelOf(npc);
            if (!taskOptionsFor(npc, currentSpec, currentLevel).contains(type)) return;
            switch (type) {
                case QUARRY, SHAFT_MINER -> showDepth(player, npc, type);
                case LANDSCAPE -> showLandscapeMode(player, npc);
                case COFFERDAM -> regionService.beginCofferdamJob(player, npc);
                default -> showTargets(player, npc, type, currentSpec, currentLevel);
            }
        }));
        form.closedOrInvalidResultHandler(() -> onClosed(player));
        send(player, form.build());
    }

    private String statusContent(NPC npc, Specialization specialization, int level) {
        java.util.List<String> lines = new java.util.ArrayList<>();
        String flavor = FlavorLadder.flavorFor(specialization, level);
        if (flavor != null && !flavor.isBlank()) lines.add(flavor);
        String hearts = HelperTitleFormatter.heartsFor(npc);
        if (hearts != null) lines.add(hearts);
        lines.add("Level " + level
                + (level >= com.houseofel.builder.toil.LevelCurve.MAX_LEVEL ? " (max)" : "")
                + " — " + levelService.bankedToilOf(npc) + " Toil banked.");
        if (level < com.houseofel.builder.toil.LevelCurve.MAX_LEVEL) {
            String progress = HelperTitleFormatter.xpBarFor(npc, specialization, levelService);
            if (progress != null) lines.add(progress);
        }
        String rust = HelperTitleFormatter.rustLineFor(npc, deathRecordStore);
        if (rust != null) lines.add(rust);
        return String.join("\n", lines);
    }

    public void showStatusOnly(Player player, NPC npc, Specialization specialization, int level) {
        send(player, SimpleForm.builder()
                .title(HelperTitleFormatter.dispatchTitleOf(npc, specialization, deathRecordStore, choiceStore))
                .content("Busy working right now.\n\n" + statusContent(npc, specialization, level))
                .button("Okay").build());
    }

    public void showBusy(Player player, NPC npc, String eta) {
        send(player, SimpleForm.builder().title(npc.getName() + " is busy")
                .content("Can't help you right now, kiddo — every pair of hands is spoken for. "
                        + "Check back in " + eta + ".")
                .button("Okay").build());
    }

    private void showClearingTargets(Player player, NPC npc) {
        ClearingPicker.EverythingState state = ClearingPicker.everythingState(
                levelService.specializationOf(npc), levelService.levelOf(npc));
        SimpleForm.Builder form = SimpleForm.builder()
                .title(BuilderNpcService.baseNameOf(npc) + " — Clearing").button("Specific Block");
        boolean showEverything = state != ClearingPicker.EverythingState.HIDDEN;
        if (showEverything) form.button(state == ClearingPicker.EverythingState.AVAILABLE
                ? "Everything" : "Everything (Unlocks at L3)");
        form.button("Cancel");
        form.validResultHandler(response -> onMain(player, () -> {
            int index = response.clickedButtonId();
            if (index == 0) showClearingResults(player, npc, "");
            else if (showEverything && index == 1 && validateClearingPick(player, npc, Target.ANY_EARTH)) {
                showConfirm(player, npc, TaskType.CLEAR, Target.ANY_EARTH, null, null);
            }
        }));
        form.closedOrInvalidResultHandler(() -> onClosed(player));
        send(player, form.build());
    }

    private void showClearingSearch(Player player, NPC npc, String query) {
        send(player, CustomForm.builder().title(BuilderNpcService.baseNameOf(npc) + " — Search Blocks")
                .input("Block name (submit to search)", "e.g. stone", query)
                .validResultHandler(response -> {
                    String submitted = response.getInput(0);
                    onMain(player, () -> showClearingResults(player, npc, submitted));
                }).closedOrInvalidResultHandler(() -> onClosed(player)).build());
    }

    /** SimpleForm supplies native scrolling; no matches are truncated. */
    private void showClearingResults(Player player, NPC npc, String query) {
        String search = ClearingPicker.query(query);
        java.util.List<Material> results = ClearingPicker.search(search);
        SimpleForm.Builder form = clearingResultsForm(
                BuilderNpcService.baseNameOf(npc) + " \u2014 Specific Block", search, results);
        form.validResultHandler(response -> onMain(player, () -> {
            int index = response.clickedButtonId();
            if (index == 0) {
                showClearingSearch(player, npc, search);
            } else if (index >= 1 && index <= results.size()) {
                try {
                    Target target = Target.specificBlock(results.get(index - 1));
                    if (validateClearingPick(player, npc, target)) {
                        showConfirm(player, npc, TaskType.CLEAR, target, null, null);
                    }
                } catch (IllegalArgumentException invalid) {
                    player.sendMessage(Component.text("That block is no longer available for Clearing.", NamedTextColor.RED));
                    showClearingResults(player, npc, search);
                }
            } else if (index == results.size() + 1) {
                showClearingTargets(player, npc);
            }
        }));
        form.closedOrInvalidResultHandler(() -> onClosed(player));
        send(player, form.build());
    }

    /** Presentation only: image availability must not change button indices or selection validation. */
    static SimpleForm.Builder clearingResultsForm(String title, String search, java.util.List<Material> results) {
        String status = results.isEmpty() ? "No matching blocks. Try another name."
                : search.isBlank() ? "Common picks — choose one block." : results.size() + " matching blocks — choose one.";
        SimpleForm.Builder form = SimpleForm.builder().title(title)
                .content(status).button("Search");
        for (Material material : results) {
            String path = BedrockBlockTextures.pathFor(material);
            if (path == null) form.button(Target.blockLabel(material));
            else form.button(Target.blockLabel(material), FormImage.Type.PATH, path);
        }
        form.button("Back").button("Cancel");
        return form;
    }

    private boolean validateClearingPick(Player player, NPC npc, Target target) {
        if (ClearingPicker.isAllowed(target, levelService.specializationOf(npc), levelService.levelOf(npc))) return true;
        player.sendMessage(Component.text(target == Target.ANY_EARTH
                ? "Everything unlocks at Groundworker L3." : "That Clearing target is no longer available.", NamedTextColor.YELLOW));
        showClearingTargets(player, npc);
        return false;
    }

    private void showTargets(Player player, NPC npc, TaskType type, Specialization specialization, int level) {
        if (type == TaskType.CLEAR) {
            showClearingTargets(player, npc);
            return;
        }
        // Match the Java wizard: Anything is only available for Clearing at Groundworker L3+.
        java.util.List<Target> targets = java.util.Arrays.stream(Target.values())
                .filter(t -> t != Target.ANY_EARTH || (type == TaskType.CLEAR
                        && specialization == Specialization.GROUNDWORKER && level >= 3)).toList();
        SimpleForm.Builder form = SimpleForm.builder().title(BuilderNpcService.baseNameOf(npc) + " — Target");
        for (Target target : targets) form.button(target.label());
        form.button("Cancel");
        form.validResultHandler(response -> onMain(player, () -> {
            int index = response.clickedButtonId();
            if (index >= 0 && index < targets.size()) {
                showConfirm(player, npc, type, targets.get(index), null, null);
            }
        }));
        form.closedOrInvalidResultHandler(() -> onClosed(player));
        send(player, form.build());
    }

    private void showConfirm(Player player, NPC npc, TaskType type, Target target,
                               Integer levels, Integer targetY) {
        if (type == TaskType.CLEAR && !validateClearingPick(player, npc, target)) return;
        CustomForm.Builder form = CustomForm.builder()
                .title(BuilderNpcService.baseNameOf(npc) + " — " + type.label() + " " + target.label());
        int answerOffset = 0;
        if (levels != null || targetY != null) {
            form.label(levels != null ? "Depth: " + levels + " blocks" : "Target Y: " + targetY);
            answerOffset++;
        }
        final int offset = answerOffset;
        form.toggle("Surface Only", true).toggle("Store in Chest", true);
        // Cumulus labels occupy response slots; the captured offset tracks the optional depth label.
        form.validResultHandler(response -> {
            boolean surfaceOnly = response.getToggle(offset);
            boolean storeInChest = response.getToggle(offset + 1);
            onMain(player, () -> send(player, org.geysermc.cumulus.form.ModalForm.builder()
                    .title("Confirm job")
                    .content(BuilderNpcService.baseNameOf(npc) + " — " + type.label() + " " + target.label()
                            + "\nSurface Only: " + surfaceOnly + "\nStore in Chest: " + storeInChest)
                    .button1("Send to Work").button2("Cancel")
                    .validResultHandler(answer -> {
                        if (answer.clickedFirst()) onMain(player, () -> {
                            if (type == TaskType.CLEAR && !validateClearingPick(player, npc, target)) return;
                            regionService.beginJob(player, npc, type, target, storeInChest, surfaceOnly, levels, targetY);
                        });
                    }).build()));
        });
        form.closedOrInvalidResultHandler(() -> onClosed(player));
        send(player, form.build());
    }

    private void showDepth(Player player, NPC npc, TaskType type) {
        send(player, SimpleForm.builder().title(BuilderNpcService.baseNameOf(npc) + " — Depth")
                .button("Level").button("Coordinates").button("Cancel")
                .validResultHandler(response -> onMain(player, () -> {
                    int index = response.clickedButtonId();
                    if (index == 0 || index == 1) showDepthInput(player, npc, type, index == 1);
                }))
                .closedOrInvalidResultHandler(() -> onClosed(player)).build());
    }

    private void showDepthInput(Player player, NPC npc, TaskType type, boolean coordinates) {
        send(player, CustomForm.builder().title(BuilderNpcService.baseNameOf(npc) + " — Depth")
                .input(coordinates ? "Target Y coordinate" : "Blocks deep", coordinates ? "e.g. 64" : "e.g. 12")
                .validResultHandler(response -> {
                    String input = response.getInput(0); // No labels precede this input.
                    onMain(player, () -> {
                        Integer value = parseFormInt(player, npc, input);
                        if (value == null) {
                            showDepthInput(player, npc, type, coordinates);
                            return;
                        }
                        Integer levels = coordinates ? null : value;
                        Integer targetY = coordinates ? value : null;
                        if (type == TaskType.SHAFT_MINER) {
                            regionService.beginJob(player, npc, type, Target.ANY_EARTH, true, false, levels, targetY);
                        } else {
                            showConfirm(player, npc, type, Target.ANY_EARTH, levels, targetY);
                        }
                    });
                }).closedOrInvalidResultHandler(() -> onClosed(player)).build());
    }

    private void showLandscapeMode(Player player, NPC npc) {
        LandscapeMode[] modes = LandscapeMode.values();
        SimpleForm.Builder form = SimpleForm.builder().title(BuilderNpcService.baseNameOf(npc) + " — Landscaping");
        for (LandscapeMode mode : modes) form.button(mode.label());
        form.button("Cancel").validResultHandler(response -> onMain(player, () -> {
            int index = response.clickedButtonId();
            if (index < 0 || index >= modes.length) return;
            if (modes[index] == LandscapeMode.REDESIGN) showLandscapeBiome(player, npc);
            else regionService.beginLandscapeJob(player, npc, modes[index], null);
        })).closedOrInvalidResultHandler(() -> onClosed(player));
        send(player, form.build());
    }

    private void showLandscapeBiome(Player player, NPC npc) {
        LandscapeBiome[] biomes = LandscapeBiome.values();
        SimpleForm.Builder form = SimpleForm.builder().title(BuilderNpcService.baseNameOf(npc) + " — Choose Biome");
        for (LandscapeBiome biome : biomes) form.button(biome.label());
        form.button("Cancel").validResultHandler(response -> onMain(player, () -> {
            int index = response.clickedButtonId();
            if (index >= 0 && index < biomes.length) {
                regionService.beginLandscapeJob(player, npc, LandscapeMode.REDESIGN, biomes[index]);
            }
        })).closedOrInvalidResultHandler(() -> onClosed(player));
        send(player, form.build());
    }

    private java.util.List<TaskType> taskOptionsFor(NPC npc, Specialization specialization, int level) {
        java.util.List<TaskType> jobs = new java.util.ArrayList<>(java.util.List.of(
                TaskType.MINE, TaskType.LUMBERJACK, TaskType.FARM, TaskType.CLEAR));
        if (specialization != Specialization.GROUNDWORKER || level < 8) return jobs;
        MilestoneChoiceRecord l8 = choiceStore.find(npc.getUniqueId(), 8);
        if (l8 == null) return jobs;
        if (GroundworkerL8Choice.QUARRYMAN.name().equals(l8.choice())) jobs.add(TaskType.QUARRY);
        else if (GroundworkerL8Choice.LANDSCAPER.name().equals(l8.choice())) jobs.add(TaskType.LANDSCAPE);
        else return jobs;
        if (level >= 16) {
            MilestoneChoiceRecord l16 = choiceStore.find(npc.getUniqueId(), 16);
            if (l16 != null) {
                if (GroundworkerL16Choice.COFFERDAM.name().equals(l16.choice())) jobs.add(TaskType.COFFERDAM);
                else if (GroundworkerL16Choice.SHAFT_MINER.name().equals(l16.choice())) jobs.add(TaskType.SHAFT_MINER);
            }
        }
        return jobs;
    }

    private Integer parseFormInt(Player player, NPC npc, String rawText) {
        if (rawText != null) {
            try {
                return Integer.parseInt(rawText.trim());
            } catch (NumberFormatException ignored) {}
        }
        player.sendMessage(Component.text(
                BuilderNpcService.baseNameOf(npc) + ": That's not a whole number — try again.", NamedTextColor.RED));
        return null;
    }

    /** All Bukkit/NPC reads, job starts and next-screen construction run on the server thread. */
    private void onMain(Player player, Runnable action) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) action.run();
        });
    }

    private void send(Player player, org.geysermc.cumulus.form.Form form) {
        FloodgatePlayer floodgatePlayer = FloodgateApi.getInstance().getPlayer(player.getUniqueId());
        if (floodgatePlayer != null) floodgatePlayer.sendForm(form);
    }

    private void onClosed(Player player) {
        onMain(player, () -> player.sendMessage(Component.text("No job configured.", NamedTextColor.RED)));
    }
}