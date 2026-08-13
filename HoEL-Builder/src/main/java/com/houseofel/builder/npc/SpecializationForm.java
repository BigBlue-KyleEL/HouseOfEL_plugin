package com.houseofel.builder.npc;

import net.citizensnpcs.api.npc.NPC;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.geysermc.cumulus.form.SimpleForm;
import org.geysermc.floodgate.api.FloodgateApi;
import org.geysermc.floodgate.api.player.FloodgatePlayer;

/**
 * Bedrock's equivalent of {@link SpecializationDialog} — a native Form shown from
 * {@code /builder spawn} before the Helper actually exists.
 */
public final class SpecializationForm {

    private final Plugin plugin;
    private final BuilderNpcService npcService;

    public SpecializationForm(Plugin plugin, BuilderNpcService npcService) {
        this.plugin = plugin;
        this.npcService = npcService;
    }

    public void open(Player player, Location location) {
        FloodgatePlayer floodgatePlayer = FloodgateApi.getInstance().getPlayer(player.getUniqueId());
        if (floodgatePlayer == null) {
            return;
        }

        var builder = SimpleForm.builder()
                .title("New Helper — Specialization")
                .content("Pick this Helper's specialization.");
        for (Specialization specialization : Specialization.values()) {
            builder.button(specialization.label());
        }
        builder.validResultHandler(response ->
                        onPick(player, location, Specialization.values()[response.clickedButtonId()]))
                .closedOrInvalidResultHandler(() -> Bukkit.getScheduler().runTask(plugin, () ->
                        player.sendMessage(Component.text("No Helper spawned.", NamedTextColor.RED))));
        floodgatePlayer.sendForm(builder.build());
    }

    private void onPick(Player player, Location location, Specialization specialization) {
        // The form response arrives off the main thread — recruitHelper() creates a real
        // NPC/entity (on success), which needs to happen on it.
        Bukkit.getScheduler().runTask(plugin, () -> {
            NPC npc = npcService.recruitHelper(player, location, specialization);
            if (npc == null) {
                // RecruitmentCost already told the player exactly what they're short on.
                return;
            }
            player.sendMessage(Component.text("Spawned Helper NPC '" + BuilderNpcService.baseNameOf(npc) + "' (#" + npc.getId()
                    + ") as a " + specialization.label() + ".", NamedTextColor.GREEN));
        });
    }
}
