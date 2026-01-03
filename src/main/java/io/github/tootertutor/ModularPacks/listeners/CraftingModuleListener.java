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
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.event.inventory.PrepareItemCraftEvent;

import io.github.tootertutor.ModularPacks.ModularPacksPlugin;
import io.github.tootertutor.ModularPacks.modules.CraftingModuleLogic;
import io.github.tootertutor.ModularPacks.modules.CraftingModuleUi;

public final class CraftingModuleListener implements Listener {

    private final ModularPacksPlugin plugin;

    public CraftingModuleListener(ModularPacksPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player player))
            return;
        if (!CraftingModuleUi.hasSession(player))
            return;

        Inventory top = e.getView().getTopInventory();
        if (top == null || top.getSize() < 10)
            return;

        // Block disallowed items from entering the crafting module's persistent storage.
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

        // Our custom crafting result handling (dynamic outputs, anti-dupe).
        if (CraftingModuleLogic.handleResultClick(plugin.recipes(), e, player)) {
            return;
        }

        // Any matrix change should refresh output next tick (covers recipe book auto-fill too).
        if (raw >= 0 && raw < top.getSize()) {
            Bukkit.getScheduler().runTask(plugin, () -> CraftingModuleLogic.updateResult(plugin.recipes(), player, top));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getWhoClicked() instanceof Player player))
            return;
        if (!CraftingModuleUi.hasSession(player))
            return;

        Inventory top = e.getView().getTopInventory();
        if (top == null || top.getSize() < 10)
            return;

        // Prevent dragging into the output slot.
        if (e.getRawSlots().contains(0)) {
            e.setCancelled(true);
            return;
        }

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

        // Any drag into the top inventory should refresh output next tick.
        for (int raw : e.getRawSlots()) {
            if (raw >= 0 && raw < top.getSize()) {
                Bukkit.getScheduler().runTask(plugin, () -> CraftingModuleLogic.updateResult(plugin.recipes(), player, top));
                return;
            }
        }
    }

    @EventHandler
    public void onPrepare(PrepareItemCraftEvent e) {
        if (e == null || e.getView() == null)
            return;
        if (!(e.getView().getPlayer() instanceof Player player))
            return;
        if (!CraftingModuleUi.hasSession(player))
            return;

        Inventory top = e.getInventory();
        if (top == null || top.getSize() < 10)
            return;

        // Ensure result reflects dynamic recipes when recipe book fills the grid.
        CraftingModuleLogic.updateResult(plugin.recipes(), player, top);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getPlayer() instanceof Player player))
            return;
        if (!CraftingModuleUi.hasSession(player))
            return;

        // e.getInventory() is the top inventory being closed for crafting views.
        CraftingModuleUi.handleClose(plugin, player, e.getInventory());
    }
}
