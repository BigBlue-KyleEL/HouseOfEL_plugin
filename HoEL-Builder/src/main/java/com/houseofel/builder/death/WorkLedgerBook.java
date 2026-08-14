package com.houseofel.builder.death;

import com.houseofel.builder.npc.BuilderNpcService;
import com.houseofel.builder.npc.Specialization;
import net.citizensnpcs.api.npc.NPC;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Item;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Builds and drops a Helper's Work Ledger — a fireproof book carrying its name/spec/level/
 * banked Toil/scars, PDC-tagged with the Helper's Citizens UUID for
 * {@code WorkLedgerRecoveryListener} to match a right-click against. First {@code BookMeta}
 * usage in this codebase — no existing page/title/author precedent to follow — but the
 * PDC-tagging itself follows {@code SurveyorRod}'s established convention.
 *
 * <p>Only handles the physical item — fireproof ({@link Item#setInvulnerable}) and
 * despawn-immune ({@link Item#setUnlimitedLifetime}), since true unlimited lifetime never
 * expires on its own. The real 7-real-day window is tracked separately, as a persisted drop
 * timestamp in {@code helper_ledger_pending} that {@code LedgerExpiryTask} checks — see
 * {@link DeathRecordStore#markPending}.
 */
public final class WorkLedgerBook {

    private final NamespacedKey npcUuidKey;

    public WorkLedgerBook(Plugin plugin) {
        this.npcUuidKey = new NamespacedKey(plugin, "helper-work-ledger");
    }

    public ItemStack create(NPC npc, Specialization specialization, int level, int bankedToil,
                             List<ScarRecord> scars, ScarChoice scarChoice) {
        String name = BuilderNpcService.baseNameOf(npc);
        // Null for a pre-1-F Helper that never got a Specialization assigned (confirmed
        // live 2026-08-14 — Thaddeus crashed this whole method, NPCDeathEvent included,
        // since specialization.label() isn't null-safe). Same "unassigned" fallback
        // HelperStatusListener already uses for the same case.
        String specLabel = specialization == null ? "unassigned" : specialization.label();
        ItemStack item = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta) item.getItemMeta();
        meta.setTitle(name + "'s Work Ledger");
        meta.setAuthor("House of EL");
        meta.addPages(Component.text(name, NamedTextColor.BLACK)
                .append(Component.newline())
                .append(Component.text(specLabel, NamedTextColor.DARK_GRAY))
                .append(Component.newline())
                .append(Component.newline())
                .append(Component.text("Level " + level, NamedTextColor.BLACK))
                .append(Component.newline())
                .append(Component.text(bankedToil + " Toil banked", NamedTextColor.BLACK)));
        meta.addPages(scarsPage(scars, scarChoice));
        meta.getPersistentDataContainer().set(npcUuidKey, PersistentDataType.STRING, npc.getUniqueId().toString());
        item.setItemMeta(meta);
        return item;
    }

    private Component scarsPage(List<ScarRecord> scars, ScarChoice scarChoice) {
        Component page = Component.text("Scars", NamedTextColor.DARK_RED).append(Component.newline());
        if (scars.isEmpty()) {
            page = page.append(Component.text("None yet.", NamedTextColor.BLACK));
        } else {
            for (ScarRecord scar : scars) {
                page = page.append(Component.text(
                                prettyCause(scar.cause().name()) + " at " + scar.world() + " "
                                        + scar.x() + "," + scar.y() + "," + scar.z(),
                                NamedTextColor.BLACK))
                        .append(Component.newline());
            }
        }
        if (scarChoice != null) {
            page = page.append(Component.newline())
                    .append(Component.text(scarChoice.name(), NamedTextColor.DARK_PURPLE));
        }
        return page;
    }

    private static String prettyCause(String enumName) {
        String name = enumName.toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    /** Drops a fireproof, despawn-immune Work Ledger at the given location. */
    public Item drop(Location location, ItemStack book) {
        Item item = location.getWorld().dropItemNaturally(location, book);
        item.setInvulnerable(true);
        item.setUnlimitedLifetime(true);
        return item;
    }

    /** The Citizens NPC UUID this book recovers, or null if it isn't a tagged Work Ledger. */
    public UUID npcUuidOf(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        String stored = item.getItemMeta().getPersistentDataContainer().get(npcUuidKey, PersistentDataType.STRING);
        return stored == null ? null : UUID.fromString(stored);
    }
}
