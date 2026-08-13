package com.houseofel.builder.npc;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.citizensnpcs.api.npc.NPC;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;

/**
 * Java's native-Dialog equivalent of {@link SpecializationForm} — a single direct-pick
 * screen shown from {@code /builder spawn} before the Helper actually exists, since
 * specialization is a fixed identity chosen at creation time rather than something
 * assigned after the fact.
 */
public final class SpecializationDialog {

    private final Plugin plugin;
    private final BuilderNpcService npcService;

    public SpecializationDialog(Plugin plugin, BuilderNpcService npcService) {
        this.plugin = plugin;
        this.npcService = npcService;
    }

    public void open(Player player, Location location) {
        List<ActionButton> buttons = new ArrayList<>();
        for (Specialization specialization : Specialization.values()) {
            buttons.add(ActionButton.create(Component.text(specialization.label()), null, 150,
                    DialogAction.customClick(
                            (view, audience) -> {
                                if (audience instanceof Player p) {
                                    // showDialog sends a packet — stays on the main thread,
                                    // same as every other Bukkit call triggered from a click.
                                    Bukkit.getScheduler().runTask(plugin, () -> onPick(p, location, specialization));
                                }
                            },
                            ClickCallback.Options.builder().build())));
        }

        player.showDialog(Dialog.create(factory -> factory.empty()
                .base(DialogBase.builder(Component.text("New Helper — Specialization")).build())
                .type(DialogType.multiAction(buttons).build())));
    }

    private void onPick(Player player, Location location, Specialization specialization) {
        NPC npc = npcService.spawnHelper(location, specialization);
        player.sendMessage(Component.text("Spawned Helper NPC '" + npc.getName() + "' (#" + npc.getId()
                + ") as a " + specialization.label() + ".", NamedTextColor.GREEN));
    }
}
