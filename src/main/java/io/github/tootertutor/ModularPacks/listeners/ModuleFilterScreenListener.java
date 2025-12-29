package io.github.tootertutor.ModularPacks.listeners;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import io.github.tootertutor.ModularPacks.ModularPacksPlugin;
import io.github.tootertutor.ModularPacks.config.ScreenType;
import io.github.tootertutor.ModularPacks.gui.ModuleScreenHolder;

/**
 * Implements "ghost item" whitelist config for modules backed by DROPPER/HOPPER
 * screens (Feeding/Void).
 */
public final class ModuleFilterScreenListener implements Listener {

    private final ModularPacksPlugin plugin;

    public ModuleFilterScreenListener(ModularPacksPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player player))
            return;

        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof ModuleScreenHolder msh))
            return;

        if (msh.screenType() != ScreenType.DROPPER && msh.screenType() != ScreenType.HOPPER)
            return;

        int raw = e.getRawSlot();
        int topSize = top.getSize();

        // Block all shift-click transfers (so players don't accidentally dump items in)
        if (e.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
            e.setCancelled(true);
            Bukkit.getScheduler().runTask(plugin, player::updateInventory);
            return;
        }

        // Allow normal interaction with player inventory
        if (raw < 0 || raw >= topSize)
            return;

        e.setCancelled(true);

        Bukkit.getScheduler().runTask(plugin, () -> {
            ItemStack cursor = player.getItemOnCursor();
            ItemStack current = top.getItem(raw);

            boolean cursorEmpty = cursor == null || cursor.getType().isAir();
            boolean currentEmpty = current == null || current.getType().isAir();

            if (cursorEmpty) {
                if (!currentEmpty) {
                    top.setItem(raw, null);
                }
                player.updateInventory();
                return;
            }

            Material mat = cursor.getType();
            if (mat.isAir()) {
                player.updateInventory();
                return;
            }

            // Toggle off if already set to the same material
            if (!currentEmpty && current.getType() == mat) {
                top.setItem(raw, null);
            } else {
                top.setItem(raw, new ItemStack(mat, 1));
            }

            player.updateInventory();
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onDrag(InventoryDragEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof ModuleScreenHolder msh))
            return;
        if (msh.screenType() != ScreenType.DROPPER && msh.screenType() != ScreenType.HOPPER)
            return;

        // Prevent dragging into the filter inventory (ghost config is click-based).
        int topSize = top.getSize();
        for (int raw : e.getRawSlots()) {
            if (raw >= 0 && raw < topSize) {
                e.setCancelled(true);
                return;
            }
        }
    }
}
