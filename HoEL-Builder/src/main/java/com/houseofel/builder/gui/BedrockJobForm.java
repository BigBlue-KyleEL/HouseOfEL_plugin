package com.houseofel.builder.gui;

import com.houseofel.builder.npc.BuilderNpcService;
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

    public BedrockJobForm(Plugin plugin, RegionSelectionService regionService) {
        this.plugin = plugin;
        this.regionService = regionService;
    }

    public static boolean isBedrockPlayer(Player player) {
        return FloodgateApi.getInstance().isFloodgatePlayer(player.getUniqueId());
    }

    public void open(Player player, NPC npc, Specialization specialization, int level) {
        FloodgatePlayer floodgatePlayer = floodgatePlayer(player);
        if (floodgatePlayer == null) {
            return;
        }

        String name = BuilderNpcService.baseNameOf(npc);
        String title = specialization == null ? name : name + " — " + specialization.label();
        String flavor = FlavorLadder.flavorFor(specialization, level);

        CustomForm.Builder form = CustomForm.builder().title(title);
        // Absent for an unassigned or still-green Helper — no empty label in that case.
        // A label OCCUPIES a response slot (it comes back as null), so its presence
        // shifts every following component's index — hence answerOffset below. Verified
        // against Cumulus' own response implementation; getDropdown/getToggle index
        // absolutely and do not skip labels.
        int answerOffset = 0;
        if (flavor != null) {
            form.label(flavor);
            answerOffset = 1;
        }
        int offset = answerOffset;
        floodgatePlayer.sendForm(
                form.dropdown("Task Type", labelsOf(TaskType.values()))
                        .dropdown("Target", labelsOf(Target.values()))
                        .toggle("Surface Only", true)
                        .toggle("Store in Chest", true)
                        .validResultHandler(response -> onSubmit(player, npc, response, offset))
                        .closedOrInvalidResultHandler(() -> onClosed(player))
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
                           int offset) {
        // A dropdown's answer is the selected index (an int), not its label — reading it
        // as a String is what actually threw the ClassCastException on submit.
        // `offset` accounts for the optional flavour label ahead of these — see open().
        TaskType taskType = TaskType.values()[response.getDropdown(offset)];
        Target target = Target.values()[response.getDropdown(offset + 1)];
        boolean surfaceOnly = response.getToggle(offset + 2);
        boolean storeInChest = response.getToggle(offset + 3);

        // The form response arrives off the main thread — beginJob() hands out an item
        // and drives the region-selection flow, both of which need to run on it.
        Bukkit.getScheduler().runTask(plugin, () ->
                regionService.beginJob(player, npc, taskType, target, storeInChest, surfaceOnly));
    }

    private void onClosed(Player player) {
        Bukkit.getScheduler().runTask(plugin, () ->
                player.sendMessage(Component.text("No job configured.", NamedTextColor.RED)));
    }

    private String[] labelsOf(TaskType[] types) {
        String[] labels = new String[types.length];
        for (int i = 0; i < types.length; i++) {
            labels[i] = types[i].label();
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
