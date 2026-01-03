package io.github.tootertutor.ModularPacks.item;

import java.util.List;
import java.util.UUID;

import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import io.github.tootertutor.ModularPacks.ModularPacksPlugin;
import io.github.tootertutor.ModularPacks.config.BackpackTypeDef;
import io.github.tootertutor.ModularPacks.config.Placeholders;
import io.github.tootertutor.ModularPacks.data.BackpackData;
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

        UUID id = UUID.randomUUID();
        return createExisting(id, typeId);
    }

    public ItemStack createExisting(UUID id, String typeId) {
        if (id == null)
            throw new IllegalArgumentException("id cannot be null");

        BackpackTypeDef type = plugin.cfg().findType(typeId);
        if (type == null)
            throw new IllegalArgumentException("Unknown backpack type: " + typeId);

        ItemStack item = new ItemStack(type.outputMaterial());
        ItemMeta meta = item.getItemMeta();
        if (meta == null)
            return item;

        meta.displayName(Text.c(type.displayName()));
        meta.getPersistentDataContainer().set(plugin.keys().BACKPACK_ID, PersistentDataType.STRING, id.toString());
        meta.getPersistentDataContainer().set(plugin.keys().BACKPACK_TYPE, PersistentDataType.STRING, type.id());

        if (type.customModelData() > 0) {
            CustomModelDataUtil.setCustomModelData(meta, type.customModelData());
        }

        List<String> lore = type.lore();
        if (lore != null && !lore.isEmpty()) {
            List<String> expanded = Placeholders.expandBackpackLore(plugin, type, id, lore);
            meta.lore(Text.lore(expanded));
        }

        item.setItemMeta(meta);
        return item;
    }

    public boolean refreshInPlace(ItemStack item, BackpackTypeDef type, UUID backpackId, BackpackData data,
            int totalSlots) {
        if (item == null || item.getType().isAir() || type == null || backpackId == null)
            return false;

        ItemMeta meta = item.getItemMeta();
        if (meta == null)
            return false;

        meta.displayName(Text.c(type.displayName()));
        meta.getPersistentDataContainer().set(plugin.keys().BACKPACK_ID, PersistentDataType.STRING, backpackId.toString());
        meta.getPersistentDataContainer().set(plugin.keys().BACKPACK_TYPE, PersistentDataType.STRING, type.id());

        if (type.customModelData() > 0) {
            CustomModelDataUtil.setCustomModelData(meta, type.customModelData());
        } else if (meta.hasCustomModelDataComponent()) {
            CustomModelDataUtil.setCustomModelData(meta, null);
        }

        List<String> lore = type.lore();
        if (lore != null && !lore.isEmpty()) {
            List<String> expanded = Placeholders.expandBackpackLore(plugin, type, backpackId, data, totalSlots, lore);
            meta.lore(Text.lore(expanded));
        } else {
            meta.lore(null);
        }

        item.setItemMeta(meta);
        return true;
    }

    public boolean isBackpack(ItemStack item) {
        if (item == null || !item.hasItemMeta())
            return false;
        var pdc = item.getItemMeta().getPersistentDataContainer();
        return pdc.has(plugin.keys().BACKPACK_ID, PersistentDataType.STRING)
                && pdc.has(plugin.keys().BACKPACK_TYPE, PersistentDataType.STRING);
    }
}
