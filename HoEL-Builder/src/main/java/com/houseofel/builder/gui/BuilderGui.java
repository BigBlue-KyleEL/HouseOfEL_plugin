package com.houseofel.builder.gui;

import com.houseofel.builder.region.RegionSelectionService;
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
 * Task-configuration GUI for a Helper NPC: task-type and target selection.
 * Picking both hands the player a Surveyor's Rod and closes the GUI — no dispatch logic here.
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

        public String label() {
            return label;
        }

        /** The tool the NPC equips while performing this task in the world. */
        public Material tool() {
            return icon;
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

        public String label() {
            return label;
        }

        /** Whether a world block counts as this target — Dirt also covers Grass Block. */
        public boolean matches(Material candidate) {
            if (this == DIRT) {
                return candidate == Material.DIRT || candidate == Material.GRASS_BLOCK;
            }
            return candidate == icon;
        }
    }

    private static final int SURFACE_TOGGLE_SLOT = 2;
    private static final int STORE_TOGGLE_SLOT = 4;
    private static final int SEND_SLOT = 26;

    private final Map<UUID, Selection> selections = new HashMap<>();
    private final RegionSelectionService regionService;

    public BuilderGui(RegionSelectionService regionService) {
        this.regionService = regionService;
    }

    public void open(Player player, NPC npc) {
        Selection selection = selections.computeIfAbsent(npc.getUniqueId(), id -> new Selection());
        selection.npc = npc;

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
        Player player = (Player) event.getWhoClicked();

        if (slot == SURFACE_TOGGLE_SLOT) {
            selection.surfaceOnly = !selection.surfaceOnly;
            render(event.getInventory(), selection);
            return;
        }
        if (slot == STORE_TOGGLE_SLOT) {
            selection.storeInChest = !selection.storeInChest;
            render(event.getInventory(), selection);
            return;
        }
        if (slot == SEND_SLOT) {
            handOff(player, holder.npcId(), selection);
            return;
        }

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

    private void handOff(Player player, UUID npcId, Selection selection) {
        if (selection.taskType == null || selection.target == null) {
            player.sendMessage(Component.text("Pick a task type and a target first.", NamedTextColor.RED));
            return;
        }
        selections.remove(npcId);
        player.closeInventory();
        regionService.beginJob(player, selection.npc, selection.taskType, selection.target,
                selection.storeInChest, selection.surfaceOnly);
    }

    private void render(Inventory inventory, Selection selection) {
        for (TaskType type : TaskType.values()) {
            inventory.setItem(type.slot, buildIcon(type.icon, type.label, selection.taskType == type));
        }
        for (Target target : Target.values()) {
            inventory.setItem(target.slot, buildIcon(target.icon, target.label, selection.target == target));
        }

        inventory.setItem(SURFACE_TOGGLE_SLOT, buildSurfaceToggle(selection.surfaceOnly));
        inventory.setItem(STORE_TOGGLE_SLOT, buildToggle(selection.storeInChest));
        inventory.setItem(SEND_SLOT, buildSendButton(
                selection.taskType != null && selection.target != null));
    }

    private ItemStack buildSurfaceToggle(boolean surfaceOnly) {
        ItemStack item = new ItemStack(surfaceOnly ? Material.GRASS_BLOCK : Material.DEEPSLATE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Surface Only",
                surfaceOnly ? NamedTextColor.GREEN : NamedTextColor.GOLD));
        meta.lore(List.of(surfaceOnly
                ? Component.text("On — only clears open-air blocks", NamedTextColor.GREEN)
                : Component.text("Off — hollows out buried blocks too", NamedTextColor.GOLD)));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack buildToggle(boolean enabled) {
        ItemStack item = new ItemStack(enabled ? Material.CHEST : Material.BARRIER);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Store in Chest",
                enabled ? NamedTextColor.GREEN : NamedTextColor.GRAY));
        meta.lore(List.of(enabled
                ? Component.text("On — hauls drops to a chest", NamedTextColor.GREEN)
                : Component.text("Off — cleared blocks are discarded", NamedTextColor.GRAY)));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack buildSendButton(boolean ready) {
        ItemStack item = new ItemStack(ready ? Material.LIME_WOOL : Material.GRAY_WOOL);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Send to work",
                ready ? NamedTextColor.GREEN : NamedTextColor.GRAY));
        meta.lore(List.of(ready
                ? Component.text("Click for the Surveyor's Rod", NamedTextColor.GREEN)
                : Component.text("Pick a task type and target first", NamedTextColor.GRAY)));
        item.setItemMeta(meta);
        return item;
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
        private NPC npc;
        private TaskType taskType;
        private Target target;
        private boolean storeInChest = true;
        private boolean surfaceOnly = true;
    }
}
