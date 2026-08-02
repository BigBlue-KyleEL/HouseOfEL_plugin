package com.houseofel.builder.gui;

import com.houseofel.builder.region.RegionSelectionService;
import net.citizensnpcs.api.npc.NPC;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.geysermc.cumulus.form.CustomForm;
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

    public void open(Player player, NPC npc) {
        FloodgatePlayer floodgatePlayer = FloodgateApi.getInstance().getPlayer(player.getUniqueId());
        if (floodgatePlayer == null) {
            return;
        }

        floodgatePlayer.sendForm(
                CustomForm.builder()
                        .title(npc.getName() + " — New Job")
                        .dropdown("Task Type", labelsOf(TaskType.values()))
                        .dropdown("Target", labelsOf(Target.values()))
                        .toggle("Surface Only", true)
                        .toggle("Store in Chest", true)
                        .validResultHandler(response -> onSubmit(player, npc, response))
                        .closedOrInvalidResultHandler(() -> onClosed(player))
                        .build());
    }

    private void onSubmit(Player player, NPC npc, org.geysermc.cumulus.response.CustomFormResponse response) {
        // A dropdown's answer is the selected index (an int), not its label — reading it
        // as a String is what actually threw the ClassCastException on submit.
        TaskType taskType = TaskType.values()[response.getDropdown(0)];
        Target target = Target.values()[response.getDropdown(1)];
        boolean surfaceOnly = response.getToggle(2);
        boolean storeInChest = response.getToggle(3);

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
