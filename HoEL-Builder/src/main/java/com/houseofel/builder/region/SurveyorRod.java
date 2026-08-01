package com.houseofel.builder.region;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/** The tool a Helper NPC hands over for marking a job region: left-click sets point A, right-click sets point B. */
public final class SurveyorRod {

    private static final List<String> FLAVOR_LINES = List.of(
            "Here you go — mark two corners and I'll know exactly where to work.",
            "Careful with this one, it's older than the mausoleum foundations.",
            "Two clicks and you've got yourself a job site.",
            "Point A, point B — simple as that.",
            "Don't lose it, I only carry the one."
    );

    private final NamespacedKey key;

    public SurveyorRod(Plugin plugin) {
        this.key = new NamespacedKey(plugin, "surveyor_rod");
    }

    public ItemStack create() {
        ItemStack item = new ItemStack(Material.BLAZE_ROD);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Surveyor's Rod", NamedTextColor.GOLD));
        meta.lore(List.of(
                Component.text("Left-click: mark point A", NamedTextColor.GRAY),
                Component.text("Right-click: mark point B", NamedTextColor.GRAY)
        ));
        meta.getPersistentDataContainer().set(key, PersistentDataType.BOOLEAN, true);
        item.setItemMeta(meta);
        return item;
    }

    public boolean isSurveyorRod(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return false;
        }
        Boolean tagged = item.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.BOOLEAN);
        return Boolean.TRUE.equals(tagged);
    }

    /** Removes any rod(s) already carried, gives a fresh one, and sends a flavor line. */
    public void giveTo(Player player) {
        removeAllFrom(player);
        player.getInventory().addItem(create());
        String line = FLAVOR_LINES.get(ThreadLocalRandom.current().nextInt(FLAVOR_LINES.size()));
        player.sendMessage(Component.text("Helper: ", NamedTextColor.DARK_AQUA)
                .append(Component.text(line, NamedTextColor.WHITE)));
    }

    /** One-time-use: called once a marking attempt reaches a terminal outcome (confirm/cancel/timeout/reject). */
    public void removeAllFrom(Player player) {
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            if (isSurveyorRod(contents[i])) {
                player.getInventory().setItem(i, null);
            }
        }
    }
}
