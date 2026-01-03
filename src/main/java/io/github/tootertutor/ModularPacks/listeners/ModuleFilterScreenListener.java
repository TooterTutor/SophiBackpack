package io.github.tootertutor.ModularPacks.listeners;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import io.github.tootertutor.ModularPacks.ModularPacksPlugin;
import io.github.tootertutor.ModularPacks.config.ScreenType;
import io.github.tootertutor.ModularPacks.data.BackpackData;
import io.github.tootertutor.ModularPacks.data.ItemStackCodec;
import io.github.tootertutor.ModularPacks.gui.ModuleScreenHolder;
import io.github.tootertutor.ModularPacks.item.Keys;

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

        String moduleType = resolveModuleType(msh);

        // Jukebox uses a real inventory that stores discs (not a ghost filter).
        if (moduleType != null && moduleType.equalsIgnoreCase("Jukebox")) {
            handleJukeboxClick(e, player, top);
            return;
        }

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

            ItemStack ghost = cursor.clone();
            ghost.setAmount(1);

            // Toggle off if already set to the same material
            if (!currentEmpty && current.isSimilar(ghost)) {
                top.setItem(raw, null);
            } else {
                top.setItem(raw, ghost);
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

        String moduleType = resolveModuleType(msh);

        // Jukebox inventory stores real discs; allow drags, but only of discs into the
        // top inventory.
        if (moduleType != null && moduleType.equalsIgnoreCase("Jukebox")) {
            ItemStack cursor = e.getOldCursor();
            if (cursor == null || cursor.getType().isAir())
                return;
            if (!isMusicDisc(cursor.getType())) {
                int topSize = top.getSize();
                for (int raw : e.getRawSlots()) {
                    if (raw >= 0 && raw < topSize) {
                        e.setCancelled(true);
                        Bukkit.getScheduler().runTask(plugin, ((Player) e.getWhoClicked())::updateInventory);
                        return;
                    }
                }
            }
            return;
        }

        // Prevent dragging into the filter inventory (ghost config is click-based).
        int topSize = top.getSize();
        for (int raw : e.getRawSlots()) {
            if (raw >= 0 && raw < topSize) {
                e.setCancelled(true);
                return;
            }
        }
    }

    private void handleJukeboxClick(InventoryClickEvent e, Player player, Inventory top) {
        if (player == null || top == null)
            return;

        int topSize = top.getSize();
        int raw = e.getRawSlot();

        boolean clickedTop = e.getClickedInventory() != null && e.getClickedInventory().equals(top);

        // Shift-click: implement ourselves using raw slots (prevents desync/dupes).
        if (e.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
            if (!clickedTop) {
                ItemStack moving = e.getCurrentItem();
                if (moving == null || moving.getType().isAir())
                    return;
                if (!isMusicDisc(moving.getType())) {
                    e.setCancelled(true);
                    Bukkit.getScheduler().runTask(plugin, player::updateInventory);
                    return;
                }

                int sourceRawSlot = raw;
                e.setCancelled(true);
                Bukkit.getScheduler().runTask(plugin, () -> shiftFromBottomIntoJukebox(player, sourceRawSlot));
                return;
            }

            if (raw >= 0 && raw < topSize) {
                e.setCancelled(true);
                Bukkit.getScheduler().runTask(plugin, () -> shiftFromJukeboxToBottom(player, raw));
                return;
            }
        }

        // Any placement into the top inventory must be a music disc.
        if (clickedTop && raw >= 0 && raw < topSize) {
            InventoryAction action = e.getAction();

            if (action == InventoryAction.PLACE_ALL
                    || action == InventoryAction.PLACE_ONE
                    || action == InventoryAction.PLACE_SOME
                    || action == InventoryAction.SWAP_WITH_CURSOR) {
                ItemStack cursor = e.getCursor();
                if (cursor != null && !cursor.getType().isAir() && !isMusicDisc(cursor.getType())) {
                    e.setCancelled(true);
                    Bukkit.getScheduler().runTask(plugin, player::updateInventory);
                    return;
                }
            }

            if (action == InventoryAction.HOTBAR_SWAP) {
                int btn = e.getHotbarButton();
                if (btn >= 0 && btn <= 8) {
                    ItemStack hotbar = player.getInventory().getItem(btn);
                    if (hotbar != null && !hotbar.getType().isAir() && !isMusicDisc(hotbar.getType())) {
                        e.setCancelled(true);
                        Bukkit.getScheduler().runTask(plugin, player::updateInventory);
                        return;
                    }
                }
            }

            // Also block number-key swaps that would move non-discs into the top.
            if (e.getClick() == ClickType.NUMBER_KEY) {
                int btn = e.getHotbarButton();
                if (btn >= 0 && btn <= 8) {
                    ItemStack hotbar = player.getInventory().getItem(btn);
                    if (hotbar != null && !hotbar.getType().isAir() && !isMusicDisc(hotbar.getType())) {
                        e.setCancelled(true);
                        Bukkit.getScheduler().runTask(plugin, player::updateInventory);
                        return;
                    }
                }
            }
        }
    }

    private void shiftFromBottomIntoJukebox(Player player, int sourceRawSlot) {
        if (player == null)
            return;

        InventoryView view = player.getOpenInventory();
        if (view == null)
            return;

        Inventory top = view.getTopInventory();
        if (!(top.getHolder() instanceof ModuleScreenHolder msh))
            return;

        if (msh.screenType() != ScreenType.DROPPER && msh.screenType() != ScreenType.HOPPER)
            return;

        String moduleType = resolveModuleType(msh);
        if (moduleType == null || !moduleType.equalsIgnoreCase("Jukebox"))
            return;

        if (sourceRawSlot < 0 || sourceRawSlot >= view.countSlots())
            return;

        ItemStack source = view.getItem(sourceRawSlot);
        if (source == null || source.getType().isAir())
            return;
        if (!isMusicDisc(source.getType()))
            return;

        int[] slots = new int[top.getSize()];
        for (int i = 0; i < slots.length; i++) {
            slots[i] = i;
        }

        ItemStack remainder = ModuleClickHandler.insertIntoSlots(top, slots, 0, source.clone());
        view.setItem(sourceRawSlot, (remainder == null || remainder.getType().isAir() || remainder.getAmount() <= 0)
                ? null
                : remainder);

        player.updateInventory();
    }

    private void shiftFromJukeboxToBottom(Player player, int topRawSlot) {
        if (player == null)
            return;

        InventoryView view = player.getOpenInventory();
        if (view == null)
            return;

        Inventory top = view.getTopInventory();
        if (!(top.getHolder() instanceof ModuleScreenHolder msh))
            return;

        if (msh.screenType() != ScreenType.DROPPER && msh.screenType() != ScreenType.HOPPER)
            return;

        String moduleType = resolveModuleType(msh);
        if (moduleType == null || !moduleType.equalsIgnoreCase("Jukebox"))
            return;

        if (topRawSlot < 0 || topRawSlot >= top.getSize())
            return;

        ItemStack moving = view.getItem(topRawSlot);
        if (moving == null || moving.getType().isAir())
            return;

        var leftovers = player.getInventory().addItem(moving.clone());
        if (leftovers.isEmpty()) {
            view.setItem(topRawSlot, null);
        } else {
            view.setItem(topRawSlot, leftovers.values().iterator().next());
        }

        player.updateInventory();
    }

    private boolean isMusicDisc(Material mat) {
        return mat != null && mat.name().startsWith("MUSIC_DISC_");
    }

    private String resolveModuleType(ModuleScreenHolder msh) {
        if (msh == null)
            return null;

        BackpackData data = plugin.repo().loadOrCreate(msh.backpackId(), msh.backpackType());
        if (data == null)
            return null;

        byte[] snap = data.installedSnapshots().get(msh.moduleId());
        if (snap == null || snap.length == 0)
            return null;

        ItemStack[] arr;
        try {
            arr = ItemStackCodec.fromBytes(snap);
        } catch (Exception ex) {
            return null;
        }
        if (arr.length == 0 || arr[0] == null || !arr[0].hasItemMeta())
            return null;

        ItemMeta meta = arr[0].getItemMeta();
        if (meta == null)
            return null;

        Keys keys = plugin.keys();
        return meta.getPersistentDataContainer().get(keys.MODULE_TYPE, PersistentDataType.STRING);
    }
}
