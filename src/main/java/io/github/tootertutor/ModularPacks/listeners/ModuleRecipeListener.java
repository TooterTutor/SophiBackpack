package io.github.tootertutor.ModularPacks.listeners;

import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;

import io.github.tootertutor.ModularPacks.ModularPacksPlugin;
import io.github.tootertutor.ModularPacks.config.ScreenType;
import io.github.tootertutor.ModularPacks.gui.ModuleScreenHolder;
import io.github.tootertutor.ModularPacks.modules.CraftingModuleLogic;
import io.github.tootertutor.ModularPacks.modules.SmithingModuleLogic;
import io.github.tootertutor.ModularPacks.modules.StonecutterModuleLogic;

public final class ModuleRecipeListener implements Listener {

    private final ModularPacksPlugin plugin;

    public ModuleRecipeListener(ModularPacksPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player player))
            return;

        var top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof ModuleScreenHolder msh))
            return;

        ScreenType screen = msh.screenType();
        int topSize = top.getSize();

        // Respect container rules (AllowShulkerBoxes / AllowBundles) for all module
        // screens that persist items. Exempt DROPPER/HOPPER ghost filter UIs.
        if (screen != ScreenType.DROPPER && screen != ScreenType.HOPPER) {
            boolean clickedTop = e.getClickedInventory() != null && e.getClickedInventory().equals(top);
            int raw = e.getRawSlot();

            if (clickedTop && raw >= 0 && raw < topSize) {
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
                    return;
                }
            }
        }

        if (screen == ScreenType.CRAFTING) {
            // Shift-click behavior for WORKBENCH inventories created by plugins is
            // inconsistent across versions. We implement it ourselves, but MUST do
            // it using InventoryView raw slots to avoid dupes.
            if (e.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
                int raw = e.getRawSlot();
                boolean clickedTop = e.getClickedInventory() != null && e.getClickedInventory().equals(top);

                if (!clickedTop) {
                    // bottom -> top (player inventory -> crafting matrix)
                    e.setCancelled(true);
                    UUID moduleId = msh.moduleId();
                    Bukkit.getScheduler().runTask(plugin,
                            () -> shiftFromBottomIntoCraftingMatrix(player, moduleId, raw));
                    return;
                }

                // top -> bottom (crafting matrix -> player inventory)
                if (isCraftingMatrixSlot(raw)) {
                    e.setCancelled(true);
                    UUID moduleId = msh.moduleId();
                    Bukkit.getScheduler().runTask(plugin, () -> shiftFromCraftingMatrixToBottom(player, moduleId, raw));
                    return;
                }
            }

            // Handle result slot crafting; cancel and apply our own consumption logic.
            if (CraftingModuleLogic.handleResultClick(plugin.recipes(), e, player))
                return;
        }

        if (screen == ScreenType.STONECUTTER) {
            if (ModuleClickHandler.handleShiftClickIntoInputs(plugin, e, player, top, new int[] { 0 }, item -> 0,
                    () -> StonecutterModuleLogic.updateResult(top))) {
                return;
            }

            if (StonecutterModuleLogic.handleClick(plugin, e, player))
                return;

            if (ModuleClickHandler.handleShiftClickOutOfInputs(plugin, e, player, top, raw -> raw == 0,
                    () -> StonecutterModuleLogic.updateResult(top))) {
                return;
            }
        }

        if (screen == ScreenType.SMITHING) {
            if (ModuleClickHandler.handleShiftClickIntoInputs(plugin, e, player, top, new int[] { 0, 1, 2 },
                    SmithingModuleLogic::preferredInsertSlot, () -> SmithingModuleLogic.updateResult(top))) {
                return;
            }

            if (SmithingModuleLogic.handleClick(plugin, e, player))
                return;

            if (ModuleClickHandler.handleShiftClickOutOfInputs(plugin, e, player, top, raw -> raw >= 0 && raw <= 2,
                    () -> SmithingModuleLogic.updateResult(top))) {
                return;
            }
        }

        // After any matrix change, update the result next tick (the click hasn't
        // applied yet).
        int raw = e.getRawSlot();
        if (raw >= 0 && raw < top.getSize()) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                switch (screen) {
                    case CRAFTING -> CraftingModuleLogic.updateResult(plugin.recipes(), player, top);
                    case STONECUTTER -> StonecutterModuleLogic.updateResult(top);
                    case SMITHING -> SmithingModuleLogic.updateResult(top);
                    default -> {
                    }
                }
            });
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getWhoClicked() instanceof Player player))
            return;

        var top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof ModuleScreenHolder msh))
            return;

        ScreenType screen = msh.screenType();

        if (screen != ScreenType.DROPPER && screen != ScreenType.HOPPER) {
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

        int outputSlot = switch (screen) {
            case CRAFTING -> 0;
            case STONECUTTER -> 1;
            case SMITHING -> 3;
            default -> -1;
        };
        if (outputSlot < 0 || outputSlot >= top.getSize())
            return;

        // Prevent dragging into the output slot.
        Set<Integer> rawSlots = e.getRawSlots();
        if (rawSlots.contains(outputSlot)) {
            e.setCancelled(true);
            return;
        }

        // Any drag affecting the top inventory should refresh output next tick.
        for (int raw : rawSlots) {
            if (raw >= 0 && raw < top.getSize()) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    switch (screen) {
                        case CRAFTING -> CraftingModuleLogic.updateResult(plugin.recipes(), player, top);
                        case STONECUTTER -> StonecutterModuleLogic.updateResult(top);
                        case SMITHING -> SmithingModuleLogic.updateResult(top);
                        default -> {
                        }
                    }
                });
                return;
            }
        }
    }

    private static int[] craftingMatrixSlots() {
        return new int[] { 1, 2, 3, 4, 5, 6, 7, 8, 9 };
    }

    private static boolean isCraftingMatrixSlot(int raw) {
        return raw >= 1 && raw <= 9;
    }

    private void shiftFromBottomIntoCraftingMatrix(Player player, UUID expectedModuleId, int sourceRawSlot) {
        if (player == null)
            return;

        InventoryView view = player.getOpenInventory();
        var top = view.getTopInventory();
        if (!(top.getHolder() instanceof ModuleScreenHolder msh))
            return;
        if (msh.screenType() != ScreenType.CRAFTING)
            return;
        if (expectedModuleId != null && !expectedModuleId.equals(msh.moduleId()))
            return;

        ItemStack source = view.getItem(sourceRawSlot);
        if (source == null || source.getType().isAir())
            return;
        if (!plugin.cfg().isAllowedInBackpack(source))
            return;

        ItemStack moving = source.clone();
        ItemStack remainder = ModuleClickHandler.insertIntoSlots(top, craftingMatrixSlots(), 1, moving);

        view.setItem(sourceRawSlot,
                (remainder == null || remainder.getType().isAir() || remainder.getAmount() <= 0) ? null : remainder);
        CraftingModuleLogic.updateResult(plugin.recipes(), player, top);
        player.updateInventory();
    }

    private void shiftFromCraftingMatrixToBottom(Player player, UUID expectedModuleId, int sourceRawSlot) {
        if (player == null)
            return;

        InventoryView view = player.getOpenInventory();
        var top = view.getTopInventory();
        if (!(top.getHolder() instanceof ModuleScreenHolder msh))
            return;
        if (msh.screenType() != ScreenType.CRAFTING)
            return;
        if (expectedModuleId != null && !expectedModuleId.equals(msh.moduleId()))
            return;

        ItemStack moving = view.getItem(sourceRawSlot);
        if (moving == null || moving.getType().isAir())
            return;

        var leftovers = player.getInventory().addItem(moving.clone());
        if (leftovers.isEmpty()) {
            view.setItem(sourceRawSlot, null);
        } else {
            // If partial, keep the remainder in the matrix slot.
            view.setItem(sourceRawSlot, leftovers.values().iterator().next());
        }

        CraftingModuleLogic.updateResult(plugin.recipes(), player, top);
        player.updateInventory();
    }
}
