package com.houseofel.builder.death;

import com.houseofel.builder.npc.BuilderNpcService;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.UUID;

/**
 * Right-clicking a tagged Work Ledger book brings its Helper back: {@code npc.spawn(...)}
 * on the same NPC object (Citizens despawns on death but never deregisters — same UUID,
 * same SQLite row, survives untouched), consumes the book, and clears the pending-expiry
 * row. Also clears Rust's site-refusal specifically, not the whole Rust penalty — the
 * 200-Toil pacing penalty continues; the owner physically returning to recover the Helper
 * is treated as "an owner clears the site" per the framework. Not owner-locked — whoever
 * holds the tagged book can recover it, matching vanilla item-ownership semantics.
 */
public final class WorkLedgerRecoveryListener implements Listener {

    private final Plugin plugin;
    private final WorkLedgerBook ledgerBook;
    private final DeathRecordStore store;

    public WorkLedgerRecoveryListener(Plugin plugin, WorkLedgerBook ledgerBook, DeathRecordStore store) {
        this.plugin = plugin;
        this.ledgerBook = ledgerBook;
        this.store = store;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_BLOCK && action != Action.RIGHT_CLICK_AIR) {
            return;
        }

        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();
        UUID npcUuid = ledgerBook.npcUuidOf(item);
        if (npcUuid == null) {
            return;
        }
        // Deliberately NOT cancelled, and deliberately deferred one tick before touching
        // anything. Java opens the vanilla book-reading GUI as part of processing this
        // same interaction, reading whatever is currently in the player's hand — mutating
        // that item synchronously in this handler (removing/shrinking it) raced that
        // process and won, so the book never actually opened even once the event stopped
        // being cancelled. Letting the tick finish first (so vanilla's own handling
        // completes against the untouched book), then recovering the Helper a moment
        // later, lets both happen: the book opens to read, and the Helper comes back
        // regardless of whether the player is still looking at it.
        Bukkit.getScheduler().runTask(plugin, () -> recover(player, npcUuid));
    }

    private void recover(Player player, UUID npcUuid) {
        NPC npc = CitizensAPI.getNPCRegistry().getByUniqueId(npcUuid);
        if (npc == null) {
            player.sendMessage(Component.text("This Work Ledger's Helper no longer exists.", NamedTextColor.RED));
            return;
        }

        npc.spawn(player.getLocation());
        // Citizens only writes saves.yml on its own ~1-hour autosave or a graceful
        // shutdown — this dev environment only ever force-kills (no console access), so
        // without an explicit save here a recovered Helper's new position could be
        // silently lost on the next restart (confirmed live: this is exactly what happened
        // to Bartholomew before this fix existed).
        CitizensAPI.getNPCRegistry().saveToStore();

        RustState rust = store.rustFor(npcUuid);
        if (rust != null) {
            // startedAt pushed to the epoch so HelperRust.blocksReentry always reads as
            // expired from here on — the 200-Toil pacing penalty (toilRemaining) is
            // untouched and continues until HelperLevelService works it off.
            store.saveRust(new RustState(rust.npcUuid(), rust.toilRemaining(), rust.deathWorld(),
                    rust.deathX(), rust.deathY(), rust.deathZ(), 0L));
        }
        store.clearPending(npcUuid);

        // Re-fetch rather than reuse the ItemStack captured a tick ago — a stale
        // reference risks touching whatever the player happens to be holding now if
        // they swapped hotbar slots in the meantime, however unlikely in one tick.
        ItemStack heldNow = player.getInventory().getItemInMainHand();
        if (npcUuid.equals(ledgerBook.npcUuidOf(heldNow))) {
            if (heldNow.getAmount() <= 1) {
                player.getInventory().setItemInMainHand(null);
            } else {
                heldNow.setAmount(heldNow.getAmount() - 1);
            }
        }

        String name = BuilderNpcService.baseNameOf(npc);
        player.sendMessage(Component.text(
                name + " is back on its feet — a little rusty, but back.", NamedTextColor.GREEN));
    }
}
