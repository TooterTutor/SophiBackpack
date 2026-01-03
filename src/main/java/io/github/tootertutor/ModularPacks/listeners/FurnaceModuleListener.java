package io.github.tootertutor.ModularPacks.listeners;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import io.github.tootertutor.ModularPacks.ModularPacksPlugin;
import io.github.tootertutor.ModularPacks.item.Keys;
import io.github.tootertutor.ModularPacks.modules.FurnaceModuleLogic;

public final class FurnaceModuleListener implements Listener {

    private final ModularPacksPlugin plugin;

    public FurnaceModuleListener(ModularPacksPlugin plugin) {
        this.plugin = plugin;
    }

    private boolean isFurnaceTop(Player player, InventoryType type) {
        if (player == null)
            return false;
        var top = player.getOpenInventory().getTopInventory();
        if (top == null)
            return false;
        return top.getType() == type;
    }

    @EventHandler(ignoreCancelled = true)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player player))
            return;
        if (!FurnaceModuleLogic.hasSession(player))
            return;

        InventoryType topType = e.getView().getTopInventory().getType();
        if (topType != InventoryType.FURNACE && topType != InventoryType.BLAST_FURNACE && topType != InventoryType.SMOKER)
            return;

        // Prevent putting backpacks into the module storage slots.
        ItemStack cursor = e.getCursor();
        if (isBackpack(cursor)) {
            int raw = e.getRawSlot();
            if (raw >= 0 && raw < e.getView().getTopInventory().getSize()) {
                e.setCancelled(true);
                return;
            }
        }

        // Respect container rules for module storage too.
        int topSize = e.getView().getTopInventory().getSize();
        int raw = e.getRawSlot();
        boolean clickedTop = e.getClickedInventory() != null && e.getClickedInventory().equals(e.getView().getTopInventory());

        if (clickedTop && raw >= 0 && raw < topSize) {
            InventoryAction action = e.getAction();
            if (action == InventoryAction.PLACE_ALL
                    || action == InventoryAction.PLACE_ONE
                    || action == InventoryAction.PLACE_SOME
                    || action == InventoryAction.SWAP_WITH_CURSOR) {
                if (cursor != null && !cursor.getType().isAir() && !plugin.cfg().isAllowedInBackpack(cursor)) {
                    e.setCancelled(true);
                    return;
                }
            }
            if (action == InventoryAction.HOTBAR_SWAP) {
                int btn = e.getHotbarButton();
                if (btn >= 0 && btn <= 8) {
                    ItemStack hotbar = player.getInventory().getItem(btn);
                    if (hotbar != null && !hotbar.getType().isAir() && !plugin.cfg().isAllowedInBackpack(hotbar)) {
                        e.setCancelled(true);
                        return;
                    }
                }
            }
        }

        if (!clickedTop && e.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
            ItemStack moving = e.getCurrentItem();
            if (moving != null && !moving.getType().isAir() && !plugin.cfg().isAllowedInBackpack(moving)) {
                e.setCancelled(true);
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getWhoClicked() instanceof Player player))
            return;
        if (!FurnaceModuleLogic.hasSession(player))
            return;

        InventoryType topType = e.getView().getTopInventory().getType();
        if (topType != InventoryType.FURNACE && topType != InventoryType.BLAST_FURNACE && topType != InventoryType.SMOKER)
            return;

        ItemStack cursor = e.getOldCursor();
        if (cursor == null || cursor.getType().isAir())
            return;
        if (plugin.cfg().isAllowedInBackpack(cursor))
            return;

        int topSize = e.getView().getTopInventory().getSize();
        for (int raw : e.getRawSlots()) {
            if (raw >= 0 && raw < topSize) {
                e.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getPlayer() instanceof Player player))
            return;
        if (!FurnaceModuleLogic.hasSession(player))
            return;

        InventoryType type = e.getInventory().getType();
        if (type != InventoryType.FURNACE && type != InventoryType.BLAST_FURNACE && type != InventoryType.SMOKER)
            return;

        FurnaceModuleLogic.handleClose(plugin, player, e.getInventory());
    }

    private boolean isBackpack(ItemStack item) {
        if (item == null || !item.hasItemMeta())
            return false;
        Keys keys = plugin.keys();
        var pdc = item.getItemMeta().getPersistentDataContainer();
        return pdc.has(keys.BACKPACK_ID, PersistentDataType.STRING)
                && pdc.has(keys.BACKPACK_TYPE, PersistentDataType.STRING);
    }
}

