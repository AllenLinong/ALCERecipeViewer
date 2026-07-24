package com.linong.recipelookup.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.UUID;

public final class RecipeGUIHolder implements InventoryHolder {
    private final UUID owner;
    private final String type;
    private Inventory inventory;

    public RecipeGUIHolder(UUID owner, String type) {
        this.owner = owner;
        this.type = type;
    }

    public UUID owner() { return owner; }
    public String type() { return type; }
    public void bind(Inventory inventory) { this.inventory = inventory; }

    @Override
    public Inventory getInventory() { return inventory; }
}
