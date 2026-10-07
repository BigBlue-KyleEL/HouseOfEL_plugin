package com.houseofel.builder.death;

import com.houseofel.builder.npc.Specialization;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/**
 * Flat, explicitly-untuned recruitment price. All three V0 specializations sit in the
 * framework's K=1.00 "muscle specs" band, so they currently share one placeholder price —
 * the framework's own "iron-equivalent" L1 baseline. Real per-spec pricing is a future
 * pass; {@link #basePriceAmount} is the one place to split it later.
 *
 * <p>Scaled by whichever is higher of the player's rolling-7-day recent-death escalation
 * (x1 / x1.5 at 1-2 recent deaths / x2 at 3+) or a permanent x1.5 floor once any of their
 * Helpers has ever gone {@link ScarChoice#HARDENED} — {@code max()}, not multiplication, so
 * the two don't compound past x2.
 */
public final class RecruitmentCost {

    private static final Material BASE_MATERIAL = Material.IRON_INGOT;
    private static final String BASE_MATERIAL_LABEL = "Iron Ingots";
    private static final int BASE_AMOUNT = 8;
    private static final long ESCALATION_WINDOW_MILLIS = 7L * 24 * 60 * 60 * 1000;
    private static final double HARDENED_SURCHARGE = 1.5;

    private final DeathRecordStore store;

    public RecruitmentCost(DeathRecordStore store) {
        this.store = store;
    }

    /**
     * Checks and deducts the price for recruiting this specialization. Refuses cleanly —
     * telling the player exactly what's missing — and takes nothing if they're short.
     */
    public boolean tryCharge(Player player, Specialization specialization) {
        int amount = amountFor(specialization, player.getUniqueId());
        ItemStack cost = new ItemStack(BASE_MATERIAL, amount);
        if (!player.getInventory().containsAtLeast(cost, amount)) {
            player.sendMessage(Component.text(
                    "Recruiting a " + specialization.label() + " costs " + amount + " " + BASE_MATERIAL_LABEL
                            + " right now — you don't have enough.", NamedTextColor.RED));
            return false;
        }
        player.getInventory().removeItem(cost);
        return true;
    }

    private int amountFor(Specialization specialization, UUID ownerUuid) {
        double multiplier = Math.max(deathEscalationFor(ownerUuid), hardenedFloorFor(ownerUuid));
        return (int) Math.ceil(basePriceAmount(specialization) * multiplier);
    }

    private static int basePriceAmount(Specialization specialization) {
        // All V0 specs are the same K=1.00 band, so one flat amount for now regardless of
        // which specialization was passed in — this is the seam to split by spec later.
        return BASE_AMOUNT;
    }

    /** Death #1 in the window costs nothing extra (bad luck), #2 is x1.5, #3+ is x2 — punishes reckless use, not luck. */
    private double deathEscalationFor(UUID ownerUuid) {
        int recentDeaths = store.recentDeathCount(ownerUuid, System.currentTimeMillis() - ESCALATION_WINDOW_MILLIS);
        if (recentDeaths >= 2) {
            return 2.0;
        }
        if (recentDeaths >= 1) {
            return 1.5;
        }
        return 1.0;
    }

    private double hardenedFloorFor(UUID ownerUuid) {
        return store.hasHardenedHelper(ownerUuid) ? HARDENED_SURCHARGE : 1.0;
    }
}
