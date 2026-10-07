package com.houseofel.builder.npc;

import com.houseofel.builder.gui.ForkScreenHandler;
import com.houseofel.builder.gui.ForkScreenLayout;
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
 * Java's custom picker (with native-Dialog fallback), equivalent of {@link SpecializationForm} — a single direct-pick
 * screen shown from {@code /builder spawn} before the Helper actually exists, since
 * specialization is a fixed identity chosen at creation time rather than something
 * assigned after the fact.
 */
public final class SpecializationDialog {

    private final ForkScreenHandler forkScreens;
    private final Plugin plugin;
    private final BuilderNpcService npcService;

    public SpecializationDialog(Plugin plugin, BuilderNpcService npcService, ForkScreenHandler forkScreens) {
        this.forkScreens = forkScreens;
        this.plugin = plugin;
        this.npcService = npcService;
    }

    public void open(Player player, Location location) {
        Location spawnLocation = location.clone();
        List<ForkScreenLayout.Option> options = new ArrayList<>();
        java.util.Map<String, Runnable> actions = new java.util.HashMap<>();
        for (Specialization specialization : Specialization.values()) {
            String action = "pick:" + specialization.name();
            options.add(ForkScreenLayout.specializationOption(specialization));
            actions.put(action, () -> onPick(player, spawnLocation, specialization));
        }
        if (forkScreens.open(player, "New Helper — Specialization", options, actions)) return;

        List<ActionButton> buttons = new ArrayList<>();
        for (Specialization specialization : Specialization.values()) {
            buttons.add(ActionButton.create(Component.text(RecruitmentAvailability.label(specialization)), null, 200,
                    DialogAction.customClick(
                            (view, audience) -> {
                                if (audience instanceof Player p) {
                                    // showDialog sends a packet — stays on the main thread,
                                    // same as every other Bukkit call triggered from a click.
                                    Bukkit.getScheduler().runTask(plugin, () -> onPick(p, spawnLocation, specialization));
                                }
                            },
                            ClickCallback.Options.builder().build())));
        }

        player.showDialog(Dialog.create(factory -> factory.empty()
                .base(DialogBase.builder(Component.text("New Helper — Specialization")).build())
                .type(DialogType.multiAction(buttons).build())));
    }

    private void onPick(Player player, Location location, Specialization specialization) {
        if (!player.isOnline() || !player.hasPermission("houseofel.builder.spawn")) {
            player.sendMessage(Component.text("You don't have permission to recruit a Helper.", NamedTextColor.RED));
            return;
        }
        if (!RecruitmentAvailability.available(specialization)) {
            player.sendMessage(RecruitmentAvailability.refusal());
            open(player,location);
            return;
        }
        NPC npc = npcService.recruitHelper(player, location, specialization);
        if (npc == null) {
            // RecruitmentCost already told the player exactly what they're short on.
            return;
        }
        player.sendMessage(Component.text("Spawned Helper NPC '" + BuilderNpcService.baseNameOf(npc) + "' (#" + npc.getId()
                + ") as a " + specialization.label() + ".", NamedTextColor.GREEN));
    }
}
