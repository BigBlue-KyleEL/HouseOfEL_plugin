package com.houseofel.builder.npc;

import com.houseofel.builder.gui.ForkScreenHandler;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.geysermc.cumulus.form.CustomForm;
import org.geysermc.floodgate.api.FloodgateApi;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Shared final recruitment step. No charge or spawn until a valid name is submitted. */
public final class RecruitmentNameFlow implements Listener {
    private record Request(UUID token, Location location, Specialization specialization, boolean bedrock) {}
    private final Plugin plugin;
    private final BuilderNpcService npcs;
    private final ForkScreenHandler forks;
    private final Map<UUID,Request> pending = new HashMap<>();

    public RecruitmentNameFlow(Plugin plugin, BuilderNpcService npcs, ForkScreenHandler forks) {
        this.plugin = plugin;
        this.npcs = npcs;
        this.forks = forks;
    }
    public void clear(Player player) { pending.remove(player.getUniqueId()); }
    @EventHandler public void onQuit(PlayerQuitEvent event) { clear(event.getPlayer()); }

    public void begin(Player player, Location location, Specialization specialization, boolean bedrock) {
        if (!allowed(player,specialization)) return;
        show(player,location,specialization,bedrock,npcs.suggestedName(),null);
    }

    private boolean allowed(Player player, Specialization specialization) {
        if (!player.isOnline()) return false;
        if (!player.hasPermission("houseofel.builder.spawn")) {
            player.sendMessage(Component.text("You don't have permission to recruit a Helper.",NamedTextColor.RED));
            return false;
        }
        if (!RecruitmentAvailability.available(specialization)) {
            player.sendMessage(RecruitmentAvailability.refusal());
            return false;
        }
        return true;
    }

    private void show(Player player, Location location, Specialization specialization, boolean bedrock,
                      String initial, String error) {
        Request request = new Request(UUID.randomUUID(),location.clone(),specialization,bedrock);
        pending.put(player.getUniqueId(),request);
        String value = initial == null ? "" : initial.substring(0,Math.min(initial.length(),64));
        if (!bedrock && forks.openName(player,value,error,name -> submit(player,request,name))) return;
        if (bedrock) {
            var floodgate = FloodgateApi.getInstance().getPlayer(player.getUniqueId());
            if (floodgate == null) { pending.remove(player.getUniqueId(),request); return; }
            var form = CustomForm.builder().title("New Helper — Name")
                    .input("Helper name (1–24 characters)","Helper name",value);
            if (error != null) form.label(error);
            form.validResultHandler(response -> {
                String name = response.getInput(0);
                Bukkit.getScheduler().runTask(plugin,() -> submit(player,request,name));
            }).closedOrInvalidResultHandler(() -> Bukkit.getScheduler().runTask(plugin,
                    () -> pending.remove(player.getUniqueId(),request)));
            floodgate.sendForm(form.build());
            return;
        }
        var recruit = ActionButton.create(Component.text("Recruit"),null,150,DialogAction.customClick(
                (view,audience) -> {
                    if (audience instanceof Player p && p.getUniqueId().equals(player.getUniqueId())) {
                        String name = view.getText("name");
                        Bukkit.getScheduler().runTask(plugin,() -> submit(p,request,name));
                    }
                },ClickCallback.Options.builder().build()));
        var cancel = ActionButton.create(Component.text("Cancel"),null,150,DialogAction.customClick(
                (view,audience) -> Bukkit.getScheduler().runTask(plugin,
                        () -> pending.remove(player.getUniqueId(),request)),ClickCallback.Options.builder().build()));
        player.showDialog(Dialog.create(factory -> factory.empty()
                .base(DialogBase.builder(Component.text("New Helper — Name"))
                        .body(List.of(DialogBody.plainMessage(Component.text(error == null
                                ? "Choose a name (1–24 characters)." : error))))
                        .inputs(List.of(DialogInput.text("name",Component.text("Helper name"))
                                .initial(value).maxLength(64).width(240).build())).build())
                .type(DialogType.multiAction(List.of(recruit,cancel)).columns(1).build())));
    }

    private void submit(Player player, Request request, String rawName) {
        if (!pending.remove(player.getUniqueId(),request) || !allowed(player,request.specialization())) return;
        HelperNames.Result name = npcs.validateName(rawName);
        if (!name.valid()) {
            show(player,request.location(),request.specialization(),request.bedrock(),rawName,name.error());
            return;
        }
        // Service validates again immediately before charging; other players may have
        // claimed the suggested name while this screen was open.
        var npc = npcs.recruitHelper(player,request.location(),request.specialization(),name.name());
        if (npc == null) return; // Existing cost/validation refusal already sent.
        player.sendMessage(Component.text("Spawned Helper NPC '"+BuilderNpcService.baseNameOf(npc)
                +"' (#"+npc.getId()+") as a "+request.specialization().label()+".",NamedTextColor.GREEN));
    }
}
