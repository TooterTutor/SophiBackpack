package io.github.tootertutor.ModularPacks.listeners;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Item;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import io.github.tootertutor.ModularPacks.ModularPacksPlugin;
import io.github.tootertutor.ModularPacks.data.BackpackData;
import io.github.tootertutor.ModularPacks.data.ItemStackCodec;
import io.github.tootertutor.ModularPacks.item.Keys;

public final class BackpackEverlastingListener implements Listener {

    private static final int CACHE_TTL_TICKS = 100;

    private final ModularPacksPlugin plugin;
    private final Map<UUID, CacheEntry> cache = new HashMap<>();

    public BackpackEverlastingListener(ModularPacksPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDespawn(ItemDespawnEvent e) {
        Item item = e.getEntity();
        BackpackRef ref = readBackpackRef(item.getItemStack());
        if (ref == null)
            return;
        if (hasEverlasting(ref.backpackId, ref.backpackType)) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCombust(EntityCombustEvent e) {
        if (!(e.getEntity() instanceof Item item))
            return;
        BackpackRef ref = readBackpackRef(item.getItemStack());
        if (ref == null)
            return;
        if (hasEverlasting(ref.backpackId, ref.backpackType)) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Item item))
            return;

        BackpackRef ref = readBackpackRef(item.getItemStack());
        if (ref == null)
            return;

        if (!hasEverlasting(ref.backpackId, ref.backpackType))
            return;

        switch (e.getCause()) {
            case LAVA, FIRE, FIRE_TICK, HOT_FLOOR, VOID -> {
                e.setCancelled(true);
                if (e.getCause() == EntityDamageEvent.DamageCause.VOID) {
                    rescueFromVoid(item);
                }
            }
            default -> {
            }
        }
    }

    private void rescueFromVoid(Item item) {
        if (item == null || item.isDead())
            return;

        Location loc = item.getLocation();
        if (loc == null || loc.getWorld() == null)
            return;

        int y = loc.getWorld().getHighestBlockYAt(loc) + 1;
        int minY = loc.getWorld().getMinHeight() + 1;
        y = Math.max(y, minY);

        Location target = loc.clone();
        target.setY(y);
        item.teleport(target);
        item.setVelocity(new org.bukkit.util.Vector(0, 0.1, 0));
    }

    private BackpackRef readBackpackRef(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || !stack.hasItemMeta())
            return null;
        ItemMeta meta = stack.getItemMeta();
        if (meta == null)
            return null;

        Keys keys = plugin.keys();
        String idStr = meta.getPersistentDataContainer().get(keys.BACKPACK_ID, PersistentDataType.STRING);
        String type = meta.getPersistentDataContainer().get(keys.BACKPACK_TYPE, PersistentDataType.STRING);
        if (idStr == null || type == null || idStr.isBlank() || type.isBlank())
            return null;

        try {
            return new BackpackRef(UUID.fromString(idStr), type);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private boolean hasEverlasting(UUID backpackId, String backpackType) {
        if (backpackId == null || backpackType == null)
            return false;

        int now = Bukkit.getCurrentTick();
        CacheEntry cached = cache.get(backpackId);
        if (cached != null && now - cached.atTick <= CACHE_TTL_TICKS) {
            return cached.enabled;
        }

        boolean enabled = computeHasEverlasting(backpackId, backpackType);
        cache.put(backpackId, new CacheEntry(now, enabled));
        return enabled;
    }

    private boolean computeHasEverlasting(UUID backpackId, String backpackType) {
        var def = plugin.cfg().findUpgrade("Everlasting");
        if (def == null || !def.enabled())
            return false;

        BackpackData data = plugin.repo().loadOrCreate(backpackId, backpackType);

        for (UUID moduleId : data.installedModules().values()) {
            if (moduleId == null)
                continue;

            byte[] snap = data.installedSnapshots().get(moduleId);
            if (snap == null)
                continue;

            ItemStack[] arr = ItemStackCodec.fromBytes(snap);
            if (arr.length == 0 || arr[0] == null)
                continue;

            ItemMeta meta = arr[0].getItemMeta();
            if (meta == null)
                continue;

            String moduleType = meta.getPersistentDataContainer().get(plugin.keys().MODULE_TYPE, PersistentDataType.STRING);
            if (moduleType == null || !moduleType.equalsIgnoreCase("Everlasting"))
                continue;

            Byte en = meta.getPersistentDataContainer().get(plugin.keys().MODULE_ENABLED, PersistentDataType.BYTE);
            if (en != null && en == 0)
                return false;

            return true;
        }

        return false;
    }

    private record BackpackRef(UUID backpackId, String backpackType) {
    }

    private record CacheEntry(int atTick, boolean enabled) {
    }
}
