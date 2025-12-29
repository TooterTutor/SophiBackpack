package io.github.tootertutor.ModularPacks.listeners;

import java.util.HashMap;
import java.util.List;
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
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import io.github.tootertutor.ModularPacks.ModularPacksPlugin;
import io.github.tootertutor.ModularPacks.data.BackpackData;
import io.github.tootertutor.ModularPacks.data.ItemStackCodec;
import io.github.tootertutor.ModularPacks.item.Keys;

public final class BackpackEverlastingListener implements Listener {

    private static final int CACHE_TTL_TICKS = 100;

    private final ModularPacksPlugin plugin;
    private final Map<UUID, CacheEntry> cache = new HashMap<>();
    private final Map<UUID, UUID> droppedBy = new HashMap<>(); // itemEntityId -> playerUuid
    private final Map<UUID, List<ItemStack>> pendingRestore = new HashMap<>(); // playerUuid -> saved backpacks

    @SuppressWarnings("unused")
    private final BukkitTask voidRescueTask;

    public BackpackEverlastingListener(ModularPacksPlugin plugin) {
        this.plugin = plugin;
        this.voidRescueTask = Bukkit.getScheduler().runTaskTimer(plugin, this::scanVoidRescues, 10L, 10L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        Item item = e.getItemDrop();
        BackpackRef ref = readBackpackRef(item.getItemStack());
        if (ref == null)
            return;

        // best-effort: keep owner info fresh for recovery
        plugin.repo().ensureBackpackExists(ref.backpackId, ref.backpackType, e.getPlayer().getUniqueId(),
                e.getPlayer().getName());

        if (hasEverlasting(ref.backpackId, ref.backpackType)) {
            droppedBy.put(item.getUniqueId(), e.getPlayer().getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        droppedBy.remove(e.getItem().getUniqueId());
    }

    private void scanVoidRescues() {
        if (droppedBy.isEmpty())
            return;

        int minYGuard = 8;

        var it = droppedBy.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            UUID itemId = entry.getKey();
            UUID ownerId = entry.getValue();

            var ent = Bukkit.getEntity(itemId);
            if (!(ent instanceof Item item) || item.isDead()) {
                it.remove();
                continue;
            }

            BackpackRef ref = readBackpackRef(item.getItemStack());
            if (ref == null || !hasEverlasting(ref.backpackId, ref.backpackType)) {
                it.remove();
                continue;
            }

            Location loc = item.getLocation();
            if (loc == null || loc.getWorld() == null)
                continue;

            int minY = loc.getWorld().getMinHeight();
            if (loc.getY() <= (minY - minYGuard)) {
                rescueToOwnerOrSurface(item, ownerId);
                it.remove();
            }
        }
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
            case VOID -> {
                e.setCancelled(true);
                rescueToOwnerOrSurface(item, droppedBy.get(item.getUniqueId()));
                droppedBy.remove(item.getUniqueId());
            }
            case LAVA, FIRE, FIRE_TICK, HOT_FLOOR, CONTACT, ENTITY_EXPLOSION, BLOCK_EXPLOSION -> {
                // Protect the item from being destroyed, but do not move it. Only VOID
                // damage/death should "return" it to the player.
                e.setCancelled(true);
            }
            default -> {
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onVoidDeath(PlayerDeathEvent e) {
        Player player = e.getEntity();
        if (player == null)
            return;

        var last = player.getLastDamageCause();
        if (last == null || last.getCause() != EntityDamageEvent.DamageCause.VOID)
            return;

        if (e.getDrops() == null || e.getDrops().isEmpty())
            return;

        List<ItemStack> keep = new java.util.ArrayList<>();
        var it = e.getDrops().iterator();
        while (it.hasNext()) {
            ItemStack drop = it.next();
            BackpackRef ref = readBackpackRef(drop);
            if (ref == null)
                continue;
            if (!hasEverlasting(ref.backpackId, ref.backpackType))
                continue;

            it.remove();
            keep.add(drop);
        }

        if (!keep.isEmpty()) {
            pendingRestore.put(player.getUniqueId(), keep);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent e) {
        UUID playerId = e.getPlayer().getUniqueId();
        List<ItemStack> restore = pendingRestore.remove(playerId);
        if (restore == null || restore.isEmpty())
            return;

        Bukkit.getScheduler().runTask(plugin, () -> {
            Player p = Bukkit.getPlayer(playerId);
            if (p == null || !p.isOnline())
                return;

            for (ItemStack it : restore) {
                if (it == null || it.getType().isAir())
                    continue;
                var leftovers = p.getInventory().addItem(it);
                if (!leftovers.isEmpty()) {
                    for (ItemStack left : leftovers.values()) {
                        if (left == null || left.getType().isAir())
                            continue;
                        p.getWorld().dropItemNaturally(p.getLocation(), left);
                    }
                }
            }
        });
    }

    private void rescueToOwnerOrSurface(Item item, UUID ownerId) {
        if (item == null || item.isDead())
            return;

        ItemStack stack = item.getItemStack();
        if (stack == null || stack.getType().isAir())
            return;

        Player owner = ownerId == null ? null : Bukkit.getPlayer(ownerId);
        if (owner != null && owner.isOnline()) {
            var leftovers = owner.getInventory().addItem(stack);
            if (!leftovers.isEmpty()) {
                for (ItemStack left : leftovers.values()) {
                    if (left == null || left.getType().isAir())
                        continue;
                    owner.getWorld().dropItemNaturally(owner.getLocation(), left);
                }
            }
            item.remove();
            return;
        }

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

        String effectiveType = plugin.repo().findBackpackType(backpackId);
        if (effectiveType == null || effectiveType.isBlank())
            effectiveType = backpackType;
        BackpackData data = plugin.repo().loadOrCreate(backpackId, effectiveType);

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
