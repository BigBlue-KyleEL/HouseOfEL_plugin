package com.houseofel.builder.npc;

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
    private final RecruitmentNameFlow naming;

    public SpecializationForm(Plugin plugin, BuilderNpcService npcService) {
        this(plugin,npcService,new RecruitmentNameFlow(plugin,npcService,null));
    }
    public SpecializationForm(Plugin plugin, BuilderNpcService npcService, RecruitmentNameFlow naming) {
        this.plugin = plugin;
        this.naming = naming;
    }

    public void open(Player player, Location location) {
        naming.clear(player);
        FloodgatePlayer floodgatePlayer = FloodgateApi.getInstance().getPlayer(player.getUniqueId());
        if (floodgatePlayer == null) {
            return;
        }

        var builder = SimpleForm.builder()
                .title("New Helper — Specialization")
                .content("Pick this Helper's specialization.");
        for (Specialization specialization : Specialization.values()) {
            builder.button(optionLabel(specialization));
        }
        builder.validResultHandler(response ->
                        onPick(player, location, Specialization.values()[response.clickedButtonId()]))
                .closedOrInvalidResultHandler(() -> Bukkit.getScheduler().runTask(plugin, () ->
                        player.sendMessage(Component.text("No Helper spawned.", NamedTextColor.RED))));
        floodgatePlayer.sendForm(builder.build());
    }

    public static String optionLabel(Specialization specialization) {
        return RecruitmentAvailability.label(specialization);
    }

    private void onPick(Player player, Location location, Specialization specialization) {
        // The form response arrives off the main thread — recruitHelper() creates a real
        // NPC/entity (on success), which needs to happen on it.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;
            if (!RecruitmentAvailability.available(specialization)) {
                player.sendMessage(RecruitmentAvailability.refusal());
                open(player,location);
                return;
            }
            naming.begin(player,location,specialization,true);
        });
    }
}
