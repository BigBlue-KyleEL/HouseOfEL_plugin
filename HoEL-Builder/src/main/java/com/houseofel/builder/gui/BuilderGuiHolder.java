package com.houseofel.builder.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.UUID;

/** Marks an Inventory as belonging to a Helper NPC's task-configuration GUI. */
final class BuilderGuiHolder implements InventoryHolder {

    private final UUID npcId;
    private Inventory inventory;

    BuilderGuiHolder(UUID npcId) {
        this.npcId = npcId;
    }

    UUID npcId() {
        return npcId;
    }

    void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
