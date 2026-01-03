package io.github.tootertutor.ModularPacks.modules;

import java.util.Set;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import io.github.tootertutor.ModularPacks.ModularPacksPlugin;
import io.github.tootertutor.ModularPacks.data.ItemStackCodec;
import io.github.tootertutor.ModularPacks.data.SQLiteBackpackRepository.VoidedItemRecord;
import io.github.tootertutor.ModularPacks.item.Keys;

final class MagnetVoidEngine {

    private final ModularPacksPlugin plugin;

    MagnetVoidEngine(ModularPacksPlugin plugin) {
        this.plugin = plugin;
    }

    boolean applyMagnet(
            Player player,
            ItemStack[] contents,
            Set<Material> whitelist,
            UUID backpackId,
            String backpackType,
            UUID voidModuleId,
            Set<Material> voidWhitelist) {
        if (player == null || contents == null || whitelist == null)
            return false;

        double range = plugin.getConfig().getDouble("Upgrades.Magnet.Range", 6.0);
        if (range <= 0.1)
            return false;
        int maxEntities = Math.max(1,
                Math.min(256, plugin.getConfig().getInt("Upgrades.Magnet.MaxItemsPerTick", 32)));

        boolean changed = false;
        int processed = 0;

        boolean voidActive = backpackId != null && voidModuleId != null && voidWhitelist != null
                && !voidWhitelist.isEmpty();

        for (Entity ent : player.getNearbyEntities(range, range, range)) {
            if (processed >= maxEntities)
                break;
            if (!(ent instanceof Item itemEnt))
                continue;
            if (itemEnt.getPickupDelay() > 0)
                continue;

            ItemStack stack = itemEnt.getItemStack();
            if (stack == null || stack.getType().isAir())
                continue;
            // Never auto-pickup backpacks/modules (prevents nesting, module loss, etc.)
            if (isProtectedFromVoid(stack))
                continue;
            // Container rules + admin blacklist
            if (!plugin.cfg().isAllowedInBackpack(stack))
                continue;
            if (!whitelist.isEmpty() && !whitelist.contains(stack.getType()))
                continue;

            if (voidActive && voidWhitelist.contains(stack.getType()) && !isProtectedFromVoid(stack)) {
                boolean logged = tryLogVoidedItem(player, backpackId, backpackType, voidModuleId, stack,
                        itemEnt.getLocation());
                if (logged) {
                    itemEnt.remove();
                    changed = true;
                    processed++;
                }
                // Only affects magnet pickups; do not fall through to insertion.
                continue;
            }

            ItemStack remainder = BackpackInventoryUtil.insertIntoContents(contents, stack.clone());
            if (remainder == null || remainder.getType().isAir() || remainder.getAmount() <= 0) {
                itemEnt.remove();
                changed = true;
                processed++;
                continue;
            }

            // Partial insert; update entity
            if (remainder.getAmount() != stack.getAmount()) {
                itemEnt.setItemStack(remainder);
                changed = true;
                processed++;
            }
        }

        return changed;
    }

    private boolean isProtectedFromVoid(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || !stack.hasItemMeta())
            return false;
        ItemMeta meta = stack.getItemMeta();
        if (meta == null)
            return false;
        var pdc = meta.getPersistentDataContainer();
        Keys keys = plugin.keys();
        return pdc.has(keys.BACKPACK_ID, PersistentDataType.STRING)
                || pdc.has(keys.MODULE_ID, PersistentDataType.STRING);
    }

    private boolean tryLogVoidedItem(
            Player player,
            UUID backpackId,
            String backpackType,
            UUID voidModuleId,
            ItemStack stack,
            Location loc) {
        if (player == null || backpackId == null || voidModuleId == null || stack == null || stack.getType().isAir())
            return false;

        byte[] bytes;
        try {
            bytes = ItemStackCodec.toBytes(new ItemStack[] { stack.clone() });
        } catch (Exception ex) {
            plugin.getLogger().warning("Failed to serialize voided item: " + ex.getMessage());
            return false;
        }

        String world = (loc == null || loc.getWorld() == null) ? null : loc.getWorld().getName();
        Double x = loc == null ? null : loc.getX();
        Double y = loc == null ? null : loc.getY();
        Double z = loc == null ? null : loc.getZ();

        try {
            long id = plugin.repo().logVoidedItem(new VoidedItemRecord(
                    null,
                    System.currentTimeMillis(),
                    player.getUniqueId().toString(),
                    player.getName(),
                    backpackId.toString(),
                    backpackType,
                    voidModuleId.toString(),
                    stack.getType().name(),
                    stack.getAmount(),
                    bytes,
                    world,
                    x,
                    y,
                    z,
                    null,
                    null,
                    null));
            return id > 0;
        } catch (Exception ex) {
            plugin.getLogger().warning("Failed to log voided item to DB: " + ex.getMessage());
            return false;
        }
    }
}

