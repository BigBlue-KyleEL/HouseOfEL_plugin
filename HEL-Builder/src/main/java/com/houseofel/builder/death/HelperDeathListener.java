package com.houseofel.builder.death;

import com.houseofel.builder.npc.BuilderNpcService;
import com.houseofel.builder.npc.HelperLevelService;
import com.houseofel.builder.npc.Specialization;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.citizensnpcs.api.event.NPCDeathEvent;
import net.citizensnpcs.api.npc.NPC;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Orchestrates a Helper's death: writes its scar, drops its Work Ledger, starts Rust, and —
 * once it's earned its 3rd scar and has a resolvable owner — presents the WARY/HARDENED
 * choice to its owner. Citizens' {@link NPCDeathEvent} doesn't carry the damage cause
 * directly, so this caches the last {@link EntityDamageEvent} cause per entity to read back
 * once death actually fires — the same reason {@code HelperLevelService} keys off the
 * Citizens UUID rather than the entity's, this listener keys the cause cache off the entity
 * UUID since that's what {@link EntityDamageEvent} carries.
 */
public final class HelperDeathListener implements Listener {

    private static final String WARY_WORD = "wary";
    private static final String HARDENED_WORD = "hardened";

    private final Plugin plugin;
    private final BuilderNpcService npcService;
    private final HelperLevelService levelService;
    private final DeathRecordStore store;
    private final WorkLedgerBook ledgerBook;
    private final Map<UUID, DamageCause> lastDamageCause = new HashMap<>();

    public HelperDeathListener(Plugin plugin, BuilderNpcService npcService, HelperLevelService levelService,
                                DeathRecordStore store, WorkLedgerBook ledgerBook) {
        this.plugin = plugin;
        this.npcService = npcService;
        this.levelService = levelService;
        this.store = store;
        this.ledgerBook = ledgerBook;
    }

    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        lastDamageCause.put(event.getEntity().getUniqueId(), event.getCause());
    }

    @EventHandler
    public void onNpcDeath(NPCDeathEvent event) {
        NPC npc = event.getNPC();
        if (!npcService.isHelper(npc)) {
            return;
        }

        EntityDeathEvent bukkitEvent = event.getEvent();
        LivingEntity entity = bukkitEvent.getEntity();
        DamageCause cause = lastDamageCause.remove(entity.getUniqueId());
        Location site = entity.getLocation();
        UUID npcUuid = npc.getUniqueId();
        long now = System.currentTimeMillis();

        ScarRecord scar = new ScarRecord(npcUuid, cause == null ? DamageCause.CUSTOM : cause,
                site.getWorld().getName(), site.getBlockX(), site.getBlockY(), site.getBlockZ(), now);
        store.addScar(scar);

        Specialization specialization = levelService.specializationOf(npc);
        int level = levelService.levelOf(npc);
        int bankedToil = levelService.bankedToilOf(npc);
        List<ScarRecord> scars = store.scarsFor(npcUuid);
        ScarChoice existingChoice = store.scarChoiceOf(npcUuid);

        ItemStack book = ledgerBook.create(npc, specialization, level, bankedToil, scars, existingChoice);
        ledgerBook.drop(site, book);
        store.markPending(npcUuid, now);

        store.saveRust(new RustState(npcUuid, HelperRust.TOTAL_RUST_TOIL, site.getWorld().getName(),
                site.getBlockX(), site.getBlockY(), site.getBlockZ(), now));

        String name = BuilderNpcService.baseNameOf(npc);
        // A Helper's death is a server-wide event, not a private one — everyone online
        // hears about it, same as they'd notice if they were standing right there.
        Bukkit.broadcast(Component.text(
                name + " has fallen. Its Work Ledger is on the ground — right-click it to bring "
                        + name + " back.", NamedTextColor.RED));

        UUID ownerUuid = store.ownerOf(npcUuid);
        Player owner = ownerUuid == null ? null : Bukkit.getPlayer(ownerUuid);
        // Unlike the announcement above, the WARY/HARDENED choice stays targeted at the
        // owner specifically — it's a decision about that player's own future recruitment
        // costs and Helper behavior, not something any online player should get to make.
        //
        // >= 3, not == 3: the choice needs a resolvable owner to present to, and ownership
        // can lag behind scar count (e.g. a pre-Death-Policy Helper, or one nobody's
        // dispatched a job to yet under the per-operator ownership model). An exact-match
        // check would silently and permanently miss the window the moment scar count
        // passed 3 while ownerless — confirmed live 2026-08-14, Bartholomew reached 5
        // scars with a null owner and never got prompted. >= lets it catch up on the very
        // next death once an owner exists, rather than losing the opportunity forever.
        if (owner != null && scars.size() >= 3 && existingChoice == null) {
            owner.sendMessage(Component.text(
                    name + " has earned its third scar. Type \"" + name + " " + WARY_WORD + "\" or \""
                            + name + " " + HARDENED_WORD + "\" to choose.", NamedTextColor.LIGHT_PURPLE));
        }
    }

    /**
     * Reads the WARY/HARDENED choice back via chat — matches every other player-facing
     * choice in this codebase ({@code HelperCommandListener}'s pause/resume/cancel words,
     * {@code HelperStatusListener}'s "report"), rather than a clickable chat component.
     * {@link net.kyori.adventure.text.event.ClickEvent#callback} was tried first and
     * confirmed NOT to work in a plain chat message on this server (verified live
     * 2026-08-14 — the click never reached the server at all, {@code scar_choice} stayed
     * null) — genuinely new, unproven ground for this codebase, unlike the chat-trigger
     * pattern below, which is already proven working cross-platform everywhere else.
     */
    @EventHandler
    public void onChat(AsyncChatEvent event) {
        String message = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        NPC npc = npcService.matchHelper(message);
        if (npc == null) {
            return;
        }
        String rest = message.substring(BuilderNpcService.baseNameOf(npc).length()).trim().toLowerCase();
        ScarChoice choice;
        if (rest.equals(WARY_WORD)) {
            choice = ScarChoice.WARY;
        } else if (rest.equals(HARDENED_WORD)) {
            choice = ScarChoice.HARDENED;
        } else {
            return;
        }

        event.setCancelled(true);
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> applyScarChoice(player, npc, choice));
    }

    private void applyScarChoice(Player player, NPC npc, ScarChoice choice) {
        UUID npcUuid = npc.getUniqueId();
        String name = BuilderNpcService.baseNameOf(npc);
        UUID ownerUuid = store.ownerOf(npcUuid);
        if (ownerUuid == null || !ownerUuid.equals(player.getUniqueId())) {
            player.sendMessage(Component.text("That's not your call to make for " + name + ".", NamedTextColor.RED));
            return;
        }
        if (store.scarsFor(npcUuid).size() < 3) {
            player.sendMessage(Component.text(name + " hasn't earned that choice yet.", NamedTextColor.RED));
            return;
        }
        if (store.scarChoiceOf(npcUuid) != null) {
            player.sendMessage(Component.text(name + " has already made its choice.", NamedTextColor.RED));
            return;
        }

        store.setScarChoice(npcUuid, choice);
        if (choice == ScarChoice.WARY) {
            player.sendMessage(Component.text(
                    name + " will be more careful from now on.", NamedTextColor.AQUA));
        } else {
            player.sendMessage(Component.text(
                    name + " won't slow down for anything now — recruiting costs more from here on.",
                    NamedTextColor.GOLD));
        }
    }
}
