package io.github.tootertutor.ModularPacks.item;

import java.util.UUID;

import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import io.github.tootertutor.ModularPacks.ModularPacksPlugin;
import io.github.tootertutor.ModularPacks.config.BackpackTypeDef;
import io.github.tootertutor.ModularPacks.text.Text;

public final class BackpackItems {

    private final ModularPacksPlugin plugin;

    public BackpackItems(ModularPacksPlugin plugin) {
        this.plugin = plugin;
    }

    public ItemStack create(String typeId) {
        BackpackTypeDef type = plugin.cfg().findType(typeId);
        if (type == null)
            throw new IllegalArgumentException("Unknown backpack type: " + typeId);

        ItemStack item = new ItemStack(type.outputMaterial());

        ItemMeta meta = item.getItemMeta();
        UUID id = UUID.randomUUID();

        meta.displayName(Text.c(type.displayName()));

        meta.getPersistentDataContainer().set(plugin.keys().BACKPACK_ID, PersistentDataType.STRING, id.toString());
        meta.getPersistentDataContainer().set(plugin.keys().BACKPACK_TYPE, PersistentDataType.STRING, type.id());

        item.setItemMeta(meta);
        return item;
    }

    public ItemStack createExisting(UUID id, String typeId) {
        if (id == null)
            throw new IllegalArgumentException("id cannot be null");

        BackpackTypeDef type = plugin.cfg().findType(typeId);
        if (type == null)
            throw new IllegalArgumentException("Unknown backpack type: " + typeId);

        ItemStack item = new ItemStack(type.outputMaterial());
        ItemMeta meta = item.getItemMeta();

        meta.displayName(Text.c(type.displayName()));
        meta.getPersistentDataContainer().set(plugin.keys().BACKPACK_ID, PersistentDataType.STRING, id.toString());
        meta.getPersistentDataContainer().set(plugin.keys().BACKPACK_TYPE, PersistentDataType.STRING, type.id());

        item.setItemMeta(meta);
        return item;
    }

    public boolean isBackpack(ItemStack item) {
        if (item == null || !item.hasItemMeta())
            return false;
        var pdc = item.getItemMeta().getPersistentDataContainer();
        return pdc.has(plugin.keys().BACKPACK_ID, PersistentDataType.STRING)
                && pdc.has(plugin.keys().BACKPACK_TYPE, PersistentDataType.STRING);
    }
}
