package io.github.tootertutor.ModularPacks.listeners;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import io.github.tootertutor.ModularPacks.ModularPacksPlugin;
import io.github.tootertutor.ModularPacks.modules.StonecutterModuleUi;

public final class StonecutterModuleListener implements Listener {

    private final ModularPacksPlugin plugin;

    public StonecutterModuleListener(ModularPacksPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player player))
            return;
        if (!StonecutterModuleUi.hasSession(player))
            return;

        Inventory top = e.getView().getTopInventory();
        if (top == null || top.getType() != InventoryType.STONECUTTER)
            return;

        boolean clickedTop = e.getClickedInventory() != null && e.getClickedInventory().equals(top);
        int raw = e.getRawSlot();
        if (clickedTop && raw >= 0 && raw < top.getSize()) {
            InventoryAction action = e.getAction();
            if (action == InventoryAction.PLACE_ALL
                    || action == InventoryAction.PLACE_ONE
                    || action == InventoryAction.PLACE_SOME
                    || action == InventoryAction.SWAP_WITH_CURSOR) {
                ItemStack cursor = e.getCursor();
                if (cursor != null && !cursor.getType().isAir() && !plugin.cfg().isAllowedInBackpack(cursor)) {
                    e.setCancelled(true);
                    Bukkit.getScheduler().runTask(plugin, player::updateInventory);
                    return;
                }
            }
            if (action == InventoryAction.HOTBAR_SWAP) {
                int btn = e.getHotbarButton();
                if (btn >= 0 && btn <= 8) {
                    ItemStack hotbar = player.getInventory().getItem(btn);
                    if (hotbar != null && !hotbar.getType().isAir() && !plugin.cfg().isAllowedInBackpack(hotbar)) {
                        e.setCancelled(true);
                        Bukkit.getScheduler().runTask(plugin, player::updateInventory);
                        return;
                    }
                }
            }
        }

        if (!clickedTop && e.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
            ItemStack moving = e.getCurrentItem();
            if (moving != null && !moving.getType().isAir() && !plugin.cfg().isAllowedInBackpack(moving)) {
                e.setCancelled(true);
                Bukkit.getScheduler().runTask(plugin, player::updateInventory);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getWhoClicked() instanceof Player player))
            return;
        if (!StonecutterModuleUi.hasSession(player))
            return;

        Inventory top = e.getView().getTopInventory();
        if (top == null || top.getType() != InventoryType.STONECUTTER)
            return;

        ItemStack cursor = e.getOldCursor();
        if (cursor != null && !cursor.getType().isAir() && !plugin.cfg().isAllowedInBackpack(cursor)) {
            int topSize = top.getSize();
            for (int raw : e.getRawSlots()) {
                if (raw >= 0 && raw < topSize) {
                    e.setCancelled(true);
                    Bukkit.getScheduler().runTask(plugin, player::updateInventory);
                    return;
                }
            }
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getPlayer() instanceof Player player))
            return;
        if (!StonecutterModuleUi.hasSession(player))
            return;
        if (e.getInventory().getType() != InventoryType.STONECUTTER)
            return;

        StonecutterModuleUi.handleClose(plugin, player, e.getInventory());
    }
}

