package io.github.tootertutor.ModularPacks.listeners;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import io.github.tootertutor.ModularPacks.ModularPacksPlugin;
import io.github.tootertutor.ModularPacks.item.Keys;

/**
 * Prevents module items from being used as their underlying material (e.g. water
 * bucket placing water, milk bucket drinking, XP bottle throwing).
 */
public final class PreventModuleUseListener implements Listener {

    private final ModularPacksPlugin plugin;

    public PreventModuleUseListener(ModularPacksPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        if (isModuleItem(e.getItem())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent e) {
        ItemStack inHand = e.getPlayer().getInventory().getItem(e.getHand());
        if (isModuleItem(inHand)) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent e) {
        ItemStack inHand = e.getPlayer().getInventory().getItem(e.getHand());
        if (isModuleItem(inHand)) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent e) {
        if (isModuleItem(e.getItem())) {
            e.setCancelled(true);
        }
    }

    private boolean isModuleItem(ItemStack item) {
        if (item == null || !item.hasItemMeta())
            return false;
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        Keys keys = plugin.keys();
        return pdc.has(keys.MODULE_ID, PersistentDataType.STRING)
                && pdc.has(keys.MODULE_TYPE, PersistentDataType.STRING);
    }
}
