package com.houseofel.builder.death;

import com.houseofel.builder.npc.BuilderNpcService;
import com.houseofel.builder.npc.HelperLevelService;
import com.houseofel.builder.npc.Specialization;
import net.citizensnpcs.api.event.NPCDeathEvent;
import net.citizensnpcs.api.npc.NPC;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Orchestrates a Helper's death: writes its scar, drops its Work Ledger, starts Rust, and —
 * at exactly the 3rd scar — presents the WARY/HARDENED choice to its owner. Citizens'
 * {@link NPCDeathEvent} doesn't carry the damage cause directly, so this caches the last
 * {@link EntityDamageEvent} cause per entity to read back once death actually fires — the
 * same reason {@code HelperLevelService} keys off the Citizens UUID rather than the
 * entity's, this listener keys the cause cache off the entity UUID since that's what
 * {@link EntityDamageEvent} carries.
 */
public final class HelperDeathListener implements Listener {

    private final BuilderNpcService npcService;
    private final HelperLevelService levelService;
    private final DeathRecordStore store;
    private final WorkLedgerBook ledgerBook;
    private final Map<UUID, DamageCause> lastDamageCause = new HashMap<>();

    public HelperDeathListener(BuilderNpcService npcService, HelperLevelService levelService,
                                DeathRecordStore store, WorkLedgerBook ledgerBook) {
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
        if (owner != null && scars.size() == 3 && existingChoice == null) {
            presentScarChoice(owner, npcUuid, name);
        }
    }

    /**
     * Chat-based choice (not a Dialog/Form) so it works identically for Java and Bedrock
     * players without needing a second platform-specific UI class the way
     * {@code SpecializationDialog}/{@code SpecializationForm} do — a lower-stakes,
     * lower-frequency choice than picking a brand-new Helper's identity.
     */
    private void presentScarChoice(Player owner, UUID npcUuid, String name) {
        Component wary = Component.text("[WARY]", NamedTextColor.AQUA)
                .clickEvent(ClickEvent.callback(audience -> {
                    store.setScarChoice(npcUuid, ScarChoice.WARY);
                    owner.sendMessage(Component.text(
                            name + " will be more careful from now on.", NamedTextColor.AQUA));
                }, ClickCallback.Options.builder().build()));
        Component hardened = Component.text("[HARDENED]", NamedTextColor.GOLD)
                .clickEvent(ClickEvent.callback(audience -> {
                    store.setScarChoice(npcUuid, ScarChoice.HARDENED);
                    owner.sendMessage(Component.text(
                            name + " won't slow down for anything now — recruiting costs more from here on.",
                            NamedTextColor.GOLD));
                }, ClickCallback.Options.builder().build()));

        owner.sendMessage(Component.text(name + " has earned its third scar. Choose: ", NamedTextColor.LIGHT_PURPLE)
                .append(wary).append(Component.text("  ")).append(hardened));
    }
}
