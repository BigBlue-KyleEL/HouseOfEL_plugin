package com.houseofel.builder.gui;

import net.citizensnpcs.api.npc.NPC;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Task-configuration GUI for a Helper NPC: task-type and target selection only.
 * No job dispatch yet — selections just live in memory per NPC for this session.
 */
public final class BuilderGui implements Listener {

    /** Placeholder roster — full task-type set arrives in Phase 1-G. */
    public enum TaskType {
        MINE("Mining", Material.IRON_PICKAXE, 10),
        LUMBERJACK("Lumberjacking", Material.IRON_AXE, 11),
        FARM("Farming", Material.IRON_HOE, 12),
        CLEAR("Clearing", Material.IRON_SHOVEL, 13);

        private final String label;
        private final Material icon;
        private final int slot;

        TaskType(String label, Material icon, int slot) {
            this.label = label;
            this.icon = icon;
            this.slot = slot;
        }
    }

    public enum Target {
        STONE("Stone", Material.STONE, 19),
        DIRT("Dirt", Material.DIRT, 20),
        OAK_LOG("Oak Log", Material.OAK_LOG, 21),
        WHEAT("Wheat", Material.WHEAT, 22);

        private final String label;
        private final Material icon;
        private final int slot;

        Target(String label, Material icon, int slot) {
            this.label = label;
            this.icon = icon;
            this.slot = slot;
        }
    }

    private final Map<UUID, Selection> selections = new HashMap<>();

    public void open(Player player, NPC npc) {
        Selection selection = selections.computeIfAbsent(npc.getUniqueId(), id -> new Selection());

        BuilderGuiHolder holder = new BuilderGuiHolder(npc.getUniqueId());
        Inventory inventory = Bukkit.createInventory(holder, 27,
                Component.text("Helper: " + npc.getName(), NamedTextColor.DARK_AQUA));
        holder.setInventory(inventory);

        render(inventory, selection);
        player.openInventory(inventory);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof BuilderGuiHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof BuilderGuiHolder holder)) {
            return;
        }
        event.setCancelled(true);

        if (event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }

        Selection selection = selections.computeIfAbsent(holder.npcId(), id -> new Selection());
        int slot = event.getSlot();

        for (TaskType type : TaskType.values()) {
            if (type.slot == slot) {
                selection.taskType = (selection.taskType == type) ? null : type;
                render(event.getInventory(), selection);
                return;
            }
        }
        for (Target target : Target.values()) {
            if (target.slot == slot) {
                selection.target = (selection.target == target) ? null : target;
                render(event.getInventory(), selection);
                return;
            }
        }
    }

    private void render(Inventory inventory, Selection selection) {
        for (TaskType type : TaskType.values()) {
            inventory.setItem(type.slot, buildIcon(type.icon, type.label, selection.taskType == type));
        }
        for (Target target : Target.values()) {
            inventory.setItem(target.slot, buildIcon(target.icon, target.label, selection.target == target));
        }
    }

    private ItemStack buildIcon(Material material, String label, boolean selected) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(label, selected ? NamedTextColor.GREEN : NamedTextColor.WHITE));
        meta.lore(List.of(selected
                ? Component.text("» Selected «", NamedTextColor.GREEN)
                : Component.text("Click to select", NamedTextColor.GRAY)));
        item.setItemMeta(meta);
        return item;
    }

    private static final class Selection {
        private TaskType taskType;
        private Target target;
    }
}
