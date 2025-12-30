package io.github.tootertutor.ModularPacks.listeners;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import io.github.tootertutor.ModularPacks.ModularPacksPlugin;
import io.github.tootertutor.ModularPacks.config.Placeholders;
import io.github.tootertutor.ModularPacks.config.ScreenType;
import io.github.tootertutor.ModularPacks.data.BackpackData;
import io.github.tootertutor.ModularPacks.data.ItemStackCodec;
import io.github.tootertutor.ModularPacks.gui.BackpackMenuHolder;
import io.github.tootertutor.ModularPacks.gui.BackpackMenuRenderer;
import io.github.tootertutor.ModularPacks.gui.BackpackSortMode;
import io.github.tootertutor.ModularPacks.gui.ModuleScreenHolder;
import io.github.tootertutor.ModularPacks.gui.ScreenRouter;
import io.github.tootertutor.ModularPacks.gui.SlotLayout;
import io.github.tootertutor.ModularPacks.item.BackpackItems;
import io.github.tootertutor.ModularPacks.item.Keys;
import io.github.tootertutor.ModularPacks.modules.FurnaceStateCodec;
import io.github.tootertutor.ModularPacks.modules.TankModuleLogic;
import io.github.tootertutor.ModularPacks.modules.TankStateCodec;
import io.github.tootertutor.ModularPacks.text.Text;

public final class BackpackMenuListener implements Listener {

    private final ModularPacksPlugin plugin;
    private final BackpackMenuRenderer renderer;
    private final BackpackItems backpackItems;
    private final ScreenRouter screens;

    private final Map<UUID, Integer> lastStorageInteractionTick = new HashMap<>();
    private final Map<UUID, Integer> dirtySinceTick = new HashMap<>();
    private final Map<UUID, Integer> ignoreCloseUntilTick = new HashMap<>();

    // Best-effort guard against client-side sorting mods that spam inventory clicks
    // within the same tick/frame.
    private static final int SORT_MOD_CANCEL_WINDOW_TICKS = 8; // "frame" window
    private static final int SORT_MOD_PER_TICK_THRESHOLD = 4;
    private static final int SORT_MOD_WINDOW_TICKS = 8;
    private static final int SORT_MOD_WINDOW_THRESHOLD = 14;
    private static final int SAVE_QUIET_TICKS = 8;
    private final Map<UUID, Integer> sortBurstTick = new HashMap<>();
    private final Map<UUID, Integer> sortBurstCount = new HashMap<>();
    private final Map<UUID, Integer> cancelClicksUntilTick = new HashMap<>();
    private final Map<UUID, ArrayDeque<Integer>> sortWindowTicks = new HashMap<>();
    private final Map<UUID, Integer> sortBurstNotifiedAtTick = new HashMap<>();

    // tune this: 8–15 ticks is a good start
    private static final long SAVE_DEBOUNCE_TICKS = 10;
    private final Map<SaveKey, BukkitTask> pendingSaves = new HashMap<>();

    private record SaveKey(UUID playerId, UUID backpackId) {
    }

    public BackpackMenuListener(ModularPacksPlugin plugin) {
        this(plugin, new BackpackMenuRenderer(plugin));
    }

    public BackpackMenuListener(ModularPacksPlugin plugin, BackpackMenuRenderer renderer) {
        this.plugin = plugin;
        this.renderer = renderer;
        this.backpackItems = new BackpackItems(plugin);
        this.screens = new ScreenRouter(plugin);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player player))
            return;

        var topHolder = e.getView().getTopInventory().getHolder();

        if (!(topHolder instanceof BackpackMenuHolder holder))
            return;

        Inventory top = e.getView().getTopInventory();
        int topSize = top.getSize();

        if (e.getClickedInventory() == null)
            return;

        int now = Bukkit.getCurrentTick();

        boolean clickedTop = e.getClickedInventory().equals(top);
        int rawSlot = e.getRawSlot();

        boolean hasNavRow = holder.paginated() || holder.type().upgradeSlots() > 0;
        int visibleStorage = SlotLayout.storageAreaSize(topSize, hasNavRow);

        // Hard block: never allow swapping a backpack item into an open backpack via
        // number keys / hotbar swap actions.
        if (isBackpackHotbarSwap(player, e) || isBackpack(e.getCursor()) || isBackpack(e.getCurrentItem())) {
            e.setCancelled(true);
            Bukkit.getScheduler().runTask(plugin, player::updateInventory);
            return;
        }

        // Respect container rules (AllowShulkerBoxes / AllowBundles).
        // Only block inserting disallowed items into the backpack; allow removing them
        // (in case config changed after items were stored previously).
        if (clickedTop && rawSlot >= 0 && rawSlot < visibleStorage) {
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

        // Sorting mods (ex: Inventory Profiles Next) can spam many click packets in a
        // tiny window (including nav-row spam). If we let any of these through, it can
        // leave items "in-flight" on the cursor and effectively delete them from the
        // persisted snapshot. Only count events that target the backpack inventory (or
        // transfers into it) so normal fast clicks in the player's own inventory don't
        // get punished.
        boolean relevantToBackpack = clickedTop || e.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY
                || e.getAction() == InventoryAction.COLLECT_TO_CURSOR
                || e.getAction() == InventoryAction.HOTBAR_SWAP;

        if (relevantToBackpack && isSortingModBurst(player, now)) {
            e.setCancelled(true);
            stabilizeAfterBurst(player, holder);
            return;
        }

        if (clickedTop && rawSlot >= 0 && rawSlot < visibleStorage && e.getAction() != InventoryAction.NOTHING) {
            lastStorageInteractionTick.put(player.getUniqueId(), now);
        }

        if (!clickedTop && e.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
            lastStorageInteractionTick.put(player.getUniqueId(), now);
        }

        if (clickedTop && holder.paginated()) {
            int raw = e.getRawSlot();

            // Only protect the STORAGE area (0 .. visibleStorage-1), never the nav row
            if (raw >= 0 && raw < visibleStorage) {
                int remaining = holder.logicalSlots() - holder.page() * 45;
                int valid = Math.max(0, Math.min(45, remaining)); // logical slots remaining on this page
                valid = Math.min(valid, visibleStorage); // can't exceed this inventory's storage area

                if (raw >= valid) {
                    e.setCancelled(true);
                    return;
                }
            }
        }

        // ------------------------------------------------------------
        // Shift-click handling
        // ------------------------------------------------------------
        if (e.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY) {

            // Shift-click from top -> player is okay, except nav row
            if (clickedTop) {
                // Upgrade sockets live in the nav row; route shift-clicks to the socket
                // handler so SHIFT_RIGHT (remove) / SHIFT_LEFT (toggle) works.
                if (holder.upgradeSlots().contains(rawSlot)) {
                    handleUpgradeSocketClick(e, player, holder, rawSlot);
                    return;
                }
                if (rawSlot >= visibleStorage) {
                    e.setCancelled(true);
                }
                return;
            }

            // Shift-click from player -> top: insert into logical storage (never into nav
            // row)
            e.setCancelled(true);

            ItemStack moving = e.getCurrentItem();
            if (moving == null || moving.getType().isAir())
                return;
            if (isBackpack(moving)) {
                Bukkit.getScheduler().runTask(plugin, player::updateInventory);
                return;
            }
            if (!plugin.cfg().isAllowedInBackpack(moving)) {
                Bukkit.getScheduler().runTask(plugin, player::updateInventory);
                return;
            }

            // sync current page -> data (no DB save now)
            Bukkit.getScheduler().runTask(plugin, player::updateInventory);
            renderer.saveVisibleStorageToData(holder);

            ItemStack remainder = insertIntoBackpackLogical(holder, moving.clone());

            if (remainder == null || remainder.getAmount() <= 0) {
                e.setCurrentItem(null);
            } else {
                e.setCurrentItem(remainder);
            }

            renderer.render(holder);

            // mark + debounce-save
            markInteraction(player, holder);
            return;

        }

        // ------------------------------------------------------------
        // Protect nav row (filler/buttons), but allow upgrade sockets
        // ------------------------------------------------------------
        if (clickedTop && hasNavRow && rawSlot >= visibleStorage) {

            if (holder.upgradeSlots().contains(rawSlot)) {
                handleUpgradeSocketClick(e, player, holder, rawSlot);
                return;
            }

            int sortSlot = SlotLayout.sortButtonSlot(topSize, holder.upgradeSlots(), holder.paginated());
            if (sortSlot >= 0 && rawSlot == sortSlot) {
                e.setCancelled(true);

                ItemStack cursor = player.getItemOnCursor();
                if (cursor != null && !cursor.getType().isAir())
                    return;

                // Block sort-mode changes while inventory is actively being mutated.
                int last = lastStorageInteractionTick.getOrDefault(player.getUniqueId(), -9999);
                if (now - last <= 5)
                    return;

                if (e.getClick() == ClickType.RIGHT) {
                    holder.sortMode(holder.sortMode().next());
                    renderer.render(holder);
                    return;
                }

                if (e.getClick() == ClickType.LEFT) {
                    sortBackpack(holder);
                    scheduleSave(player, holder);
                    renderer.render(holder);
                    return;
                }

                return;
            }

            if (holder.paginated()) {
                int prevSlot = SlotLayout.prevButtonSlot(topSize);
                int nextSlot = SlotLayout.nextButtonSlot(topSize);

                if (rawSlot == prevSlot || rawSlot == nextSlot) {
                    e.setCancelled(true);

                    // must not hold item
                    ItemStack cursor = player.getItemOnCursor();
                    if (cursor != null && !cursor.getType().isAir())
                        return;

                    // Block page changes while inventory is actively being mutated.
                    // Allow it again after a short quiet period.
                    int last = lastStorageInteractionTick.getOrDefault(player.getUniqueId(), -9999);
                    if (now - last <= 5) { // quiet window (2..5 ticks)
                        return;
                    }

                    // recent activity guard
                    if (now - last <= 5)
                        return;

                    // Reject transfer-like actions (sorting mods) on nav buttons
                    switch (e.getAction()) {
                        case MOVE_TO_OTHER_INVENTORY,
                                COLLECT_TO_CURSOR,
                                HOTBAR_SWAP,
                                SWAP_WITH_CURSOR -> {
                            return;
                        }
                        default -> {
                        }
                    }

                    int targetPage = holder.page();
                    if (rawSlot == prevSlot && holder.page() > 0) {
                        targetPage = holder.page() - 1;
                    } else if (rawSlot == nextSlot) {
                        int pageCount = pageCount(holder);
                        if (holder.page() < pageCount - 1) {
                            targetPage = holder.page() + 1;
                        } else {
                            return;
                        }
                    } else {
                        return;
                    }

                    // Delay 1 tick so we can reject automated click spam (IPN) that hits
                    // nav buttons as part of its sort routine.
                    int clickTick = now;
                    UUID playerId = player.getUniqueId();
                    UUID backpackId = holder.backpackId();
                    int finalTargetPage = targetPage;

                    Bukkit.getScheduler().runTask(plugin, () -> {
                        Integer burstTick = sortBurstTick.get(playerId);
                        int burstCount = sortBurstCount.getOrDefault(playerId, 0);
                        if (burstTick == null || burstTick != clickTick || burstCount != 1)
                            return;

                        Inventory top2 = player.getOpenInventory().getTopInventory();
                        if (!(top2.getHolder() instanceof BackpackMenuHolder current))
                            return;
                        if (!current.backpackId().equals(backpackId))
                            return;

                        changePage(player, current, finalTargetPage);
                    });

                    return;
                }

            }

            e.setCancelled(true);
            return;
        }

        // For normal storage moves (pickup/place/etc), schedule a debounced save
        if (clickedTop && rawSlot >= 0 && rawSlot < visibleStorage) {
            if (e.getAction() != InventoryAction.NOTHING) {
                markInteraction(player, holder);
            }
        }

    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getWhoClicked() instanceof Player player))
            return;

        var topHolder = e.getView().getTopInventory().getHolder();

        if (!(topHolder instanceof BackpackMenuHolder holder))
            return;

        int now = Bukkit.getCurrentTick();

        Inventory top = e.getView().getTopInventory();
        int topSize = top.getSize();

        boolean hasNavRow = holder.paginated() || holder.type().upgradeSlots() > 0;
        int visibleStorage = SlotLayout.storageAreaSize(topSize, hasNavRow);

        ItemStack cursor = e.getOldCursor();
        if (cursor != null && !cursor.getType().isAir() && !plugin.cfg().isAllowedInBackpack(cursor)) {
            for (int rawSlot : e.getRawSlots()) {
                if (rawSlot >= 0 && rawSlot < visibleStorage) {
                    e.setCancelled(true);
                    Bukkit.getScheduler().runTask(plugin, player::updateInventory);
                    return;
                }
            }
        }

        boolean targetsTop = false;
        for (int rawSlot : e.getRawSlots()) {
            if (rawSlot >= 0 && rawSlot < topSize) {
                targetsTop = true;
                break;
            }
        }
        if (targetsTop && isSortingModBurst(player, now)) {
            e.setCancelled(true);
            stabilizeAfterBurst(player, holder);
            return;
        }

        for (int rawSlot : e.getRawSlots()) {
            if (rawSlot < topSize && hasNavRow && rawSlot >= visibleStorage) {
                e.setCancelled(true);
                return;
            } else if (rawSlot >= 0 && rawSlot < topSize) {
                // any drag into top inventory counts
                markInteraction(player, holder);
                break;
            }
        }

        // Cancel any drag that targets invalid storage slots on the last page
        if (holder.paginated()) {
            int remaining = holder.logicalSlots() - holder.page() * 45;
            int valid = Math.max(0, Math.min(45, remaining));
            valid = Math.min(valid, visibleStorage);

            for (int rawSlot : e.getRawSlots()) {
                if (rawSlot < topSize && rawSlot >= 0 && rawSlot < visibleStorage && rawSlot >= valid) {
                    e.setCancelled(true);
                    return;
                }
            }
        }

    }

    @EventHandler(ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent e) {
        if (!(e.getPlayer() instanceof Player player))
            return;
        if (!(e.getView().getTopInventory().getHolder() instanceof BackpackMenuHolder holder))
            return;

        // If a backpack somehow ended up inside a backpack (hotbar swap, automation,
        // etc), the generic nesting guard will prevent removing it, so proactively
        // eject it now to avoid permanent loss.
        Bukkit.getScheduler().runTask(plugin, () -> {
            Inventory top = player.getOpenInventory().getTopInventory();
            if (!(top.getHolder() instanceof BackpackMenuHolder openHolder))
                return;
            if (!openHolder.backpackId().equals(holder.backpackId()))
                return;

            int moved = 0;

            // Scan logical storage (all pages), not just currently-visible slots.
            ItemStack[] logical = ItemStackCodec.fromBytes(openHolder.data().contentsBytes());
            int logicalSize = openHolder.logicalSlots();
            if (logical.length != logicalSize) {
                ItemStack[] resized = new ItemStack[logicalSize];
                System.arraycopy(logical, 0, resized, 0, Math.min(logical.length, logicalSize));
                logical = resized;
            }

            for (int i = 0; i < logical.length; i++) {
                ItemStack it = logical[i];
                if (!isBackpack(it))
                    continue;
                logical[i] = null;
                giveOrDrop(player, it);
                moved++;
            }

            if (moved > 0) {
                openHolder.data().contentsBytes(ItemStackCodec.toBytes(logical));
                renderer.render(openHolder);
                scheduleSave(player, openHolder);
                player.sendMessage(Text.c("&cBackpacks can't be stored inside backpacks. Moved " + moved
                        + " backpack(s) back to you."));
                player.updateInventory();
            }
        });
    }

    private boolean isBackpackHotbarSwap(Player player, InventoryClickEvent e) {
        if (player == null || e == null)
            return false;
        int btn = e.getHotbarButton();
        if (btn < 0)
            return false;
        if (btn > 8)
            return false;
        // Covers ClickType.NUMBER_KEY and actions like
        // HOTBAR_SWAP/HOTBAR_MOVE_AND_READD
        ItemStack hotbar = player.getInventory().getItem(btn);
        return isBackpack(hotbar);
    }

    private boolean isBackpack(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta())
            return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null)
            return false;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        Keys keys = plugin.keys();
        return pdc.has(keys.BACKPACK_ID, PersistentDataType.STRING)
                && pdc.has(keys.BACKPACK_TYPE, PersistentDataType.STRING);
    }

    private UUID readBackpackId(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta())
            return null;
        ItemMeta meta = item.getItemMeta();
        if (meta == null)
            return null;
        String idStr = meta.getPersistentDataContainer().get(plugin.keys().BACKPACK_ID, PersistentDataType.STRING);
        if (idStr == null || idStr.isBlank())
            return null;
        try {
            return UUID.fromString(idStr);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private void refreshBackpackItemsFor(Player player, BackpackMenuHolder holder) {
        if (player == null || holder == null)
            return;
        var inv = player.getInventory();
        ItemStack[] contents = inv.getContents();
        if (contents == null || contents.length == 0)
            return;

        boolean changed = false;
        UUID target = holder.backpackId();
        for (int i = 0; i < contents.length; i++) {
            ItemStack it = contents[i];
            if (!isBackpack(it))
                continue;
            UUID id = readBackpackId(it);
            if (id == null || !id.equals(target))
                continue;

            if (backpackItems.refreshInPlace(it, holder.type(), target, holder.data(), holder.logicalSlots())) {
                inv.setItem(i, it);
                changed = true;
            }
        }

        if (changed) {
            Bukkit.getScheduler().runTask(plugin, player::updateInventory);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getPlayer() instanceof Player player))
            return;

        // MODULE SCREEN CLOSE
        if (e.getInventory().getHolder() instanceof ModuleScreenHolder msh) {
            BackpackData data = plugin.repo().loadOrCreate(msh.backpackId(), msh.backpackType());
            Inventory inv = e.getInventory();

            // Furnace-like: save as FurnaceStateCodec (preserve progress)
            if (msh.screenType() == ScreenType.SMELTING
                    || msh.screenType() == ScreenType.BLASTING
                    || msh.screenType() == ScreenType.SMOKING) {

                byte[] existing = data.moduleStates().get(msh.moduleId());
                FurnaceStateCodec.State old = FurnaceStateCodec.decode(existing);

                FurnaceStateCodec.State fs = new FurnaceStateCodec.State();
                fs.input = inv.getItem(0);
                fs.fuel = inv.getItem(1);
                fs.output = inv.getItem(2);

                if (old != null) {
                    fs.burnTime = old.burnTime;
                    fs.burnTotal = old.burnTotal;
                    fs.cookTime = old.cookTime;
                    fs.cookTotal = old.cookTotal;
                }

                data.moduleStates().put(msh.moduleId(), FurnaceStateCodec.encode(fs));
                plugin.repo().saveBackpack(data);
                plugin.sessions().refreshLinkedBackpacksThrottled(msh.backpackId(), data);
                plugin.sessions().onRelatedInventoryClose(player, msh.backpackId());
                return;
            }

            // Everything else: save as ItemStackCodec, but do NOT persist derived outputs
            ItemStack[] items = new ItemStack[inv.getSize()];
            for (int i = 0; i < items.length; i++) {
                items[i] = inv.getItem(i);
            }

            // Clear derived output slots before saving (prevents stale/dupe)
            switch (msh.screenType()) {
                case CRAFTING -> {
                    if (items.length > 0)
                        items[0] = null;
                } // result slot
                case STONECUTTER -> {
                    if (items.length > 1)
                        items[1] = null;
                } // output slot
                case SMITHING -> {
                    if (items.length > 3)
                        items[3] = null;
                } // output slot
                case ANVIL -> {
                    if (items.length > 2)
                        items[2] = null;
                }

                default -> {
                }
            }

            data.moduleStates().put(msh.moduleId(), ItemStackCodec.toBytes(items));
            plugin.repo().saveBackpack(data);
            plugin.sessions().refreshLinkedBackpacksThrottled(msh.backpackId(), data);
            plugin.sessions().onRelatedInventoryClose(player, msh.backpackId());
            return;
        }

        // BACKPACK CLOSE
        if (e.getInventory().getHolder() instanceof BackpackMenuHolder holder) {
            int now = Bukkit.getCurrentTick();
            int ignoreUntil = ignoreCloseUntilTick.getOrDefault(player.getUniqueId(), -1);

            // If this close was caused by a page switch (resizeGui reopen), don't flush
            // here.
            if (now <= ignoreUntil) {
                return;
            }

            // Cancel any pending debounced save
            SaveKey key = new SaveKey(player.getUniqueId(), holder.backpackId());
            BukkitTask existing = pendingSaves.remove(key);
            if (existing != null) {
                existing.cancel();
            }

            // Save the current state directly from the inventory (still accessible during
            // close event)
            renderer.saveVisibleStorageToData(holder);
            plugin.repo().saveBackpack(holder.data());
            refreshBackpackItemsFor(player, holder);
            plugin.sessions().refreshLinkedBackpacksThrottled(holder.backpackId(), holder.data());
            plugin.sessions().onRelatedInventoryClose(player, holder.backpackId());

            dirtySinceTick.remove(player.getUniqueId());

            // cleanup burst guard tracking for this player
            UUID playerId = player.getUniqueId();
            sortBurstTick.remove(playerId);
            sortBurstCount.remove(playerId);
            cancelClicksUntilTick.remove(playerId);
            sortWindowTicks.remove(playerId);
            sortBurstNotifiedAtTick.remove(playerId);
        }

    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player player = e.getPlayer();
        if (player == null)
            return;
        plugin.sessions().releaseAllFor(player.getUniqueId());
    }

    // ------------------------------------------------------------
    // Upgrade socket click behavior (Map<Integer, UUID> installedModules)
    // ------------------------------------------------------------
    private void handleUpgradeSocketClick(
            InventoryClickEvent e,
            Player player,
            BackpackMenuHolder holder,
            int invSlot) {
        e.setCancelled(true);

        ItemStack clicked = e.getCurrentItem();
        ItemStack cursor = e.getCursor();
        ClickType click = e.getClick();

        // -----------------------------------------
        // Cursor has an item
        // -----------------------------------------
        if (cursor != null && !cursor.getType().isAir()) {
            // INSTALL: cursor has module, socket empty
            if (isModuleItem(cursor) && isEmptySocket(clicked)) {
                installModuleFromCursor(player, holder, invSlot, cursor);
                renderer.render(holder);
                return;
            }

            // TANK: bucket interactions on the installed tank module
            if (isTankModule(clicked)) {
                // If the storage area was modified, ensure we sync it into holder.data()
                // before re-rendering, otherwise a render can overwrite "unsaved" changes.
                renderer.saveVisibleStorageToData(holder);
                if (handleTankCursorClick(player, holder, clicked, cursor)) {
                    updateModuleSnapshot(holder, clicked);
                    scheduleSave(player, holder);
                    renderer.render(holder);
                }
                return;
            }
            return;
        }

        // -----------------------------------------
        // From here on: cursor is empty
        // -----------------------------------------
        if (clicked == null || clicked.getType().isAir())
            return;

        if (!isModuleItem(clicked))
            return;

        // -----------------------------------------
        // REMOVE (Shift+Right)
        // -----------------------------------------
        if (click == ClickType.SHIFT_RIGHT) {
            removeModuleToPlayer(player, holder, invSlot);
            renderer.saveVisibleStorageToData(holder);
            renderer.render(holder);
            return;
        }

        // -----------------------------------------
        // TOGGLE (Shift+Left)
        // -----------------------------------------
        if (click == ClickType.SHIFT_LEFT) {
            toggleModule(holder, clicked);
            updateModuleSnapshot(holder, clicked);
            scheduleSave(player, holder);
            renderer.saveVisibleStorageToData(holder);
            renderer.render(holder);
            return;
        }

        // -----------------------------------------
        // MODULE ACTIONS (Tank: +1/-1 levels)
        // -----------------------------------------
        if (isTankModule(clicked)) {
            // Only allow the secondary action (right-click) if the module opts in.
            if (click == ClickType.RIGHT) {
                String type = getModuleType(clicked);
                var def = plugin.cfg().findUpgrade(type);
                if (def == null || !def.secondaryAction())
                    return;
            }

            renderer.saveVisibleStorageToData(holder);
            if (handleTankEmptyCursorClick(player, holder, clicked, click)) {
                updateModuleSnapshot(holder, clicked);
                scheduleSave(player, holder);
                renderer.render(holder);
            }
            return;
        }

        // -----------------------------------------
        // SECONDARY ACTION (Feeding: cycle behavior settings)
        // -----------------------------------------
        if (click == ClickType.RIGHT && isFeedingModule(clicked)) {
            String type = getModuleType(clicked);
            var def = plugin.cfg().findUpgrade(type);
            if (def == null || !def.secondaryAction())
                return;

            if (cycleFeedingSettings(clicked)) {
                refreshModuleVisuals(holder, clicked);
                updateModuleSnapshot(holder, clicked);
                scheduleSave(player, holder);
                renderer.saveVisibleStorageToData(holder);
                renderer.render(holder);
                player.sendMessage(Text.c("&7Feeding: &f" + formatFeedingSettings(clicked)));
            }
            return;
        }

        // -----------------------------------------
        // SECONDARY ACTION (Jukebox: cycle playback mode)
        // -----------------------------------------
        if (click == ClickType.RIGHT && isJukeboxModule(clicked)) {
            String type = getModuleType(clicked);
            var def = plugin.cfg().findUpgrade(type);
            if (def == null || !def.secondaryAction())
                return;

            if (cycleJukeboxMode(clicked)) {
                refreshModuleVisuals(holder, clicked);
                updateModuleSnapshot(holder, clicked);
                scheduleSave(player, holder);
                renderer.saveVisibleStorageToData(holder);
                renderer.render(holder);
                player.sendMessage(Text.c("&7Jukebox: &f" + formatJukeboxMode(clicked)));
            }
            return;
        }

        // -----------------------------------------
        // OPEN MODULE UI
        // -----------------------------------------
        if (click == ClickType.LEFT) {
            openModuleScreen(player, holder, clicked);
            return;
        }

        // Only open on right-click if this module opts into a secondary action.
        if (click == ClickType.RIGHT) {
            String type = getModuleType(clicked);
            var def = plugin.cfg().findUpgrade(type);
            if (def != null && def.secondaryAction()) {
                openModuleScreen(player, holder, clicked);
            }
            return;
        }

    }

    private boolean isTankModule(ItemStack moduleItem) {
        String type = getModuleType(moduleItem);
        return type != null && type.equalsIgnoreCase("Tank");
    }

    private boolean isFeedingModule(ItemStack moduleItem) {
        String type = getModuleType(moduleItem);
        return type != null && type.equalsIgnoreCase("Feeding");
    }

    private boolean isJukeboxModule(ItemStack moduleItem) {
        String type = getModuleType(moduleItem);
        return type != null && type.equalsIgnoreCase("Jukebox");
    }

    private enum JukeboxMode {
        SHUFFLE("Shuffle"),
        REPEAT_ONE("Repeat One"),
        REPEAT_ALL("Repeat All");

        private final String displayName;

        JukeboxMode(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }

        static JukeboxMode fromString(String raw, String fallbackRaw) {
            JukeboxMode parsed = parse(raw);
            if (parsed != null)
                return parsed;
            parsed = parse(fallbackRaw);
            if (parsed != null)
                return parsed;
            return REPEAT_ALL;
        }

        private static JukeboxMode parse(String raw) {
            if (raw == null)
                return null;
            String s = raw.trim().toUpperCase(java.util.Locale.ROOT);
            if (s.isEmpty())
                return null;
            return switch (s) {
                case "SHUFFLE", "RANDOM" -> SHUFFLE;
                case "REPEAT_ONE", "REPEAT1", "ONE" -> REPEAT_ONE;
                case "REPEAT_ALL", "REPEATALL", "ALL" -> REPEAT_ALL;
                default -> null;
            };
        }

        public JukeboxMode next() {
            return switch (this) {
                case SHUFFLE -> REPEAT_ONE;
                case REPEAT_ONE -> REPEAT_ALL;
                case REPEAT_ALL -> SHUFFLE;
            };
        }
    }

    private boolean cycleJukeboxMode(ItemStack moduleItem) {
        if (moduleItem == null || !moduleItem.hasItemMeta())
            return false;

        ItemMeta meta = moduleItem.getItemMeta();
        if (meta == null)
            return false;

        Keys keys = plugin.keys();
        var pdc = meta.getPersistentDataContainer();

        JukeboxMode current = JukeboxMode.fromString(
                pdc.get(keys.MODULE_JUKEBOX_MODE, PersistentDataType.STRING),
                plugin.getConfig().getString("Upgrades.Jukebox.Mode", "RepeatAll"));
        JukeboxMode next = current.next();

        pdc.set(keys.MODULE_JUKEBOX_MODE, PersistentDataType.STRING, next.name());
        moduleItem.setItemMeta(meta);
        return true;
    }

    private String formatJukeboxMode(ItemStack moduleItem) {
        if (moduleItem == null || !moduleItem.hasItemMeta())
            return JukeboxMode.REPEAT_ALL.displayName();

        ItemMeta meta = moduleItem.getItemMeta();
        if (meta == null)
            return JukeboxMode.REPEAT_ALL.displayName();

        Keys keys = plugin.keys();
        var pdc = meta.getPersistentDataContainer();

        JukeboxMode current = JukeboxMode.fromString(
                pdc.get(keys.MODULE_JUKEBOX_MODE, PersistentDataType.STRING),
                plugin.getConfig().getString("Upgrades.Jukebox.Mode", "RepeatAll"));
        return current.displayName();
    }

    private boolean cycleFeedingSettings(ItemStack moduleItem) {
        if (moduleItem == null || !moduleItem.hasItemMeta())
            return false;

        ItemMeta meta = moduleItem.getItemMeta();
        if (meta == null)
            return false;

        Keys keys = plugin.keys();
        var pdc = meta.getPersistentDataContainer();

        FeedingSelectionMode mode = FeedingSelectionMode.fromString(
                pdc.get(keys.MODULE_FEEDING_SELECTION_MODE, PersistentDataType.STRING),
                plugin.getConfig().getString("Upgrades.Feeding.SelectionMode", "BestCandidate"));
        FeedingPreference pref = FeedingPreference.fromString(
                pdc.get(keys.MODULE_FEEDING_PREFERENCE, PersistentDataType.STRING),
                plugin.getConfig().getString("Upgrades.Feeding.Preference", "Nutrition"));

        FeedingSettings next = FeedingSettings.next(mode, pref);

        pdc.set(keys.MODULE_FEEDING_SELECTION_MODE, PersistentDataType.STRING, next.mode().name());
        pdc.set(keys.MODULE_FEEDING_PREFERENCE, PersistentDataType.STRING, next.preference().name());

        moduleItem.setItemMeta(meta);
        return true;
    }

    private String formatFeedingSettings(ItemStack moduleItem) {
        if (moduleItem == null || !moduleItem.hasItemMeta())
            return "Best Candidate / Prefer Nutrition";

        ItemMeta meta = moduleItem.getItemMeta();
        if (meta == null)
            return "Best Candidate / Prefer Nutrition";

        Keys keys = plugin.keys();
        var pdc = meta.getPersistentDataContainer();

        FeedingSelectionMode mode = FeedingSelectionMode.fromString(
                pdc.get(keys.MODULE_FEEDING_SELECTION_MODE, PersistentDataType.STRING),
                plugin.getConfig().getString("Upgrades.Feeding.SelectionMode", "BestCandidate"));
        FeedingPreference pref = FeedingPreference.fromString(
                pdc.get(keys.MODULE_FEEDING_PREFERENCE, PersistentDataType.STRING),
                plugin.getConfig().getString("Upgrades.Feeding.Preference", "Nutrition"));

        return mode.displayName() + " / " + pref.displayName();
    }

    private enum FeedingSelectionMode {
        BEST_CANDIDATE("Best Candidate"),
        WHITELIST_ORDER("Prefer First in Whitelist");

        private final String displayName;

        FeedingSelectionMode(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }

        static FeedingSelectionMode fromString(String raw, String fallbackRaw) {
            FeedingSelectionMode parsed = parse(raw);
            if (parsed != null)
                return parsed;
            parsed = parse(fallbackRaw);
            if (parsed != null)
                return parsed;
            return BEST_CANDIDATE;
        }

        private static FeedingSelectionMode parse(String raw) {
            if (raw == null)
                return null;
            String s = raw.trim().toUpperCase(java.util.Locale.ROOT);
            if (s.isEmpty())
                return null;
            return switch (s) {
                case "BEST", "BESTCANDIDATE", "BEST_CANDIDATE" -> BEST_CANDIDATE;
                case "WHITELIST", "WHITELISTORDER", "WHITELIST_ORDER", "PREFER_FIRST_IN_WHITELIST" -> WHITELIST_ORDER;
                default -> null;
            };
        }
    }

    private enum FeedingPreference {
        NUTRITION("Prefer Nutrition"),
        EFFECTS("Prefer Effects");

        private final String displayName;

        FeedingPreference(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }

        static FeedingPreference fromString(String raw, String fallbackRaw) {
            FeedingPreference parsed = parse(raw);
            if (parsed != null)
                return parsed;
            parsed = parse(fallbackRaw);
            if (parsed != null)
                return parsed;
            return NUTRITION;
        }

        private static FeedingPreference parse(String raw) {
            if (raw == null)
                return null;
            String s = raw.trim().toUpperCase(java.util.Locale.ROOT);
            if (s.isEmpty())
                return null;
            return switch (s) {
                case "NUTRITION" -> NUTRITION;
                case "EFFECT", "EFFECTS" -> EFFECTS;
                default -> null;
            };
        }
    }

    private record FeedingSettings(FeedingSelectionMode mode, FeedingPreference preference) {
        static FeedingSettings next(FeedingSelectionMode mode, FeedingPreference pref) {
            if (mode == null)
                mode = FeedingSelectionMode.BEST_CANDIDATE;
            if (pref == null)
                pref = FeedingPreference.NUTRITION;

            // Cycle order:
            // BestCandidate+Nutrition -> BestCandidate+Effects -> WhitelistOrder+Nutrition
            // -> WhitelistOrder+Effects -> ...
            if (mode == FeedingSelectionMode.BEST_CANDIDATE && pref == FeedingPreference.NUTRITION) {
                return new FeedingSettings(FeedingSelectionMode.BEST_CANDIDATE, FeedingPreference.EFFECTS);
            }
            if (mode == FeedingSelectionMode.BEST_CANDIDATE && pref == FeedingPreference.EFFECTS) {
                return new FeedingSettings(FeedingSelectionMode.WHITELIST_ORDER, FeedingPreference.NUTRITION);
            }
            if (mode == FeedingSelectionMode.WHITELIST_ORDER && pref == FeedingPreference.NUTRITION) {
                return new FeedingSettings(FeedingSelectionMode.WHITELIST_ORDER, FeedingPreference.EFFECTS);
            }
            return new FeedingSettings(FeedingSelectionMode.BEST_CANDIDATE, FeedingPreference.NUTRITION);
        }
    }

    private boolean handleTankCursorClick(Player player, BackpackMenuHolder holder, ItemStack moduleItem,
            ItemStack cursor) {
        if (player == null || holder == null || moduleItem == null || cursor == null)
            return false;

        UUID moduleId = readModuleId(moduleItem);
        if (moduleId == null)
            return false;

        TankStateCodec.State state = TankStateCodec.decode(readModuleState(holder, moduleId, moduleItem));

        Material cursorMat = cursor.getType();
        if (TankModuleLogic.isSupportedFluidBucket(cursorMat)) {
            return tankDepositFluid(player, holder, moduleId, moduleItem, cursor, state, cursorMat);
        }
        if (cursorMat == Material.BUCKET) {
            return tankWithdrawFluid(player, holder, moduleId, moduleItem, cursor, state);
        }
        return false;
    }

    private boolean handleTankEmptyCursorClick(Player player, BackpackMenuHolder holder, ItemStack moduleItem,
            ClickType click) {
        if (player == null || holder == null || moduleItem == null || click == null)
            return false;

        UUID moduleId = readModuleId(moduleItem);
        if (moduleId == null)
            return false;

        TankStateCodec.State state = TankStateCodec.decode(readModuleState(holder, moduleId, moduleItem));

        if (click == ClickType.RIGHT) {
            // Toggle EXP mode only if tank is truly empty; otherwise withdraw 1 exp level
            // if in exp mode.
            if (state.fluidBuckets <= 0 && state.expLevels <= 0) {
                state.expMode = !state.expMode;
                persistTankState(holder, moduleId, moduleItem, state);
                return true;
            }

            if (state.expMode && state.expLevels > 0) {
                state.expLevels--;
                player.giveExpLevels(1);
                persistTankState(holder, moduleId, moduleItem, state);
                return true;
            }

            return false;
        }

        if (click == ClickType.LEFT) {
            if (!state.expMode)
                return false;
            if (state.expLevels >= TankModuleLogic.MAX_EXP_LEVELS)
                return false;
            if (player.getLevel() <= 0)
                return false;

            state.expLevels++;
            player.giveExpLevels(-1);
            persistTankState(holder, moduleId, moduleItem, state);
            return true;
        }

        return false;
    }

    private boolean tankDepositFluid(
            Player player,
            BackpackMenuHolder holder,
            UUID moduleId,
            ItemStack moduleItem,
            ItemStack cursor,
            TankStateCodec.State state,
            Material fluidBucket) {
        if (state.expMode || state.expLevels > 0)
            return false;
        if (state.fluidBuckets >= TankModuleLogic.MAX_FLUID_BUCKETS)
            return false;

        String curFluid = state.fluidBucketMaterial;
        if (state.fluidBuckets > 0 && curFluid != null && !curFluid.equalsIgnoreCase(fluidBucket.name()))
            return false;

        state.fluidBucketMaterial = fluidBucket.name();
        state.fluidBuckets++;

        // Convert 1 fluid bucket -> 1 empty bucket.
        ItemStack newCursor = cursor.clone();
        if (newCursor.getAmount() <= 1) {
            player.setItemOnCursor(new ItemStack(Material.BUCKET, 1));
        } else {
            newCursor.setAmount(newCursor.getAmount() - 1);
            player.setItemOnCursor(newCursor);
            giveOrDrop(player, new ItemStack(Material.BUCKET, 1));
        }

        persistTankState(holder, moduleId, moduleItem, state);
        Bukkit.getScheduler().runTask(plugin, player::updateInventory);
        return true;
    }

    private boolean tankWithdrawFluid(
            Player player,
            BackpackMenuHolder holder,
            UUID moduleId,
            ItemStack moduleItem,
            ItemStack cursor,
            TankStateCodec.State state) {
        if (state.expMode || state.expLevels > 0)
            return false;
        if (state.fluidBuckets <= 0)
            return false;

        Material fluidBucket = state.fluidBucketMaterial == null ? null
                : Material.matchMaterial(state.fluidBucketMaterial);
        if (fluidBucket == null || !TankModuleLogic.isSupportedFluidBucket(fluidBucket))
            return false;

        // Convert 1 empty bucket -> 1 filled bucket.
        ItemStack newCursor = cursor.clone();
        if (newCursor.getAmount() <= 1) {
            player.setItemOnCursor(new ItemStack(fluidBucket, 1));
        } else {
            newCursor.setAmount(newCursor.getAmount() - 1);
            player.setItemOnCursor(newCursor);
            giveOrDrop(player, new ItemStack(fluidBucket, 1));
        }

        state.fluidBuckets--;
        if (state.fluidBuckets <= 0) {
            state.fluidBuckets = 0;
            state.fluidBucketMaterial = null;
        }

        persistTankState(holder, moduleId, moduleItem, state);
        Bukkit.getScheduler().runTask(plugin, player::updateInventory);
        return true;
    }

    private byte[] readModuleState(BackpackMenuHolder holder, UUID moduleId, ItemStack moduleItem) {
        byte[] fromHolder = holder.data().moduleStates().get(moduleId);
        if (fromHolder != null)
            return fromHolder;
        return readModuleStateFromItem(moduleItem);
    }

    private UUID readModuleId(ItemStack moduleItem) {
        if (moduleItem == null || !moduleItem.hasItemMeta())
            return null;
        ItemMeta meta = moduleItem.getItemMeta();
        if (meta == null)
            return null;
        String idStr = meta.getPersistentDataContainer().get(plugin.keys().MODULE_ID, PersistentDataType.STRING);
        if (idStr == null)
            return null;
        try {
            return UUID.fromString(idStr);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private void persistTankState(BackpackMenuHolder holder, UUID moduleId, ItemStack moduleItem,
            TankStateCodec.State state) {
        // Enforce mutual exclusivity
        if (state.expLevels > 0) {
            state.expMode = true;
            state.fluidBuckets = 0;
            state.fluidBucketMaterial = null;
        }
        if (state.fluidBuckets > 0) {
            state.expMode = false;
            state.expLevels = 0;
        }

        // clamp
        state.fluidBuckets = Math.max(0, Math.min(TankModuleLogic.MAX_FLUID_BUCKETS, state.fluidBuckets));
        state.expLevels = Math.max(0, Math.min(TankModuleLogic.MAX_EXP_LEVELS, state.expLevels));

        byte[] bytes = TankStateCodec.encode(state);
        holder.data().moduleStates().put(moduleId, bytes);

        // Store state on the physical module item too, so it carries across backpacks
        writeModuleStateToItem(moduleItem, bytes);

        // Visuals (material + lore)
        TankModuleLogic.applyVisuals(plugin, moduleItem, state);
    }

    private void giveOrDrop(Player player, ItemStack item) {
        if (player == null || item == null || item.getType().isAir())
            return;
        var leftovers = player.getInventory().addItem(item);
        if (!leftovers.isEmpty()) {
            player.getWorld().dropItemNaturally(player.getLocation(), item);
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

    private void installModuleFromCursor(Player player, BackpackMenuHolder holder, int invSlot, ItemStack cursor) {
        Keys keys = plugin.keys();

        ItemMeta meta = cursor.getItemMeta();
        if (meta == null)
            return;

        String moduleType = meta.getPersistentDataContainer().get(keys.MODULE_TYPE, PersistentDataType.STRING);
        if (moduleType == null)
            return;

        // prevent duplicate module types
        if (isModuleTypeInstalled(holder, moduleType)) {
            playSocketFail(player);
            return;
        }

        String idStr = meta.getPersistentDataContainer().get(keys.MODULE_ID, PersistentDataType.STRING);
        if (idStr == null)
            return;

        UUID moduleId = UUID.fromString(idStr);

        int socketIndex = holder.upgradeSlots().indexOf(invSlot);
        if (socketIndex < 0)
            return;

        renderer.saveVisibleStorageToData(holder);

        // link module to socket
        holder.data().installedModules().put(socketIndex, moduleId);

        // IMPORT module persistent state from the module item (so it carries across
        // backpacks)
        byte[] importedState = readModuleStateFromItem(cursor);
        if (moduleType.equalsIgnoreCase("Tank") && importedState == null) {
            importedState = TankStateCodec.encode(new TankStateCodec.State());
        }
        if (importedState != null) {
            holder.data().moduleStates().put(moduleId, importedState);
            if (moduleType.equalsIgnoreCase("Tank")) {
                writeModuleStateToItem(cursor, importedState);
                TankModuleLogic.applyVisuals(plugin, cursor, importedState);
            }
        }

        if (!moduleType.equalsIgnoreCase("Tank")) {
            applyModuleLore(cursor);
        }

        // snapshot (module item itself)
        holder.data().installedSnapshots().put(moduleId, ItemStackCodec.toBytes(new ItemStack[] { cursor.clone() }));

        // clear cursor
        player.setItemOnCursor(null);

        scheduleSave(player, holder);
        refreshBackpackItemsFor(player, holder);
        playSocketSuccess(player);
    }

    private void removeModuleToPlayer(Player player, BackpackMenuHolder holder, int invSlot) {
        int socketIndex = holder.upgradeSlots().indexOf(invSlot);
        if (socketIndex < 0)
            return;

        UUID moduleId = holder.data().installedModules().get(socketIndex);
        if (moduleId == null)
            return;

        ItemStack item = null;
        byte[] snap = holder.data().installedSnapshots().get(moduleId);
        if (snap != null) {
            ItemStack[] arr = ItemStackCodec.fromBytes(snap);
            if (arr.length > 0)
                item = arr[0];
        }

        // EXPORT module persistent state into the physical module item
        byte[] state = holder.data().moduleStates().get(moduleId);
        if (item != null) {
            writeModuleStateToItem(item, state);
            if (isTankModule(item)) {
                TankModuleLogic.applyVisuals(plugin, item, state);
            } else {
                applyModuleLore(item);
            }
        }

        // clear mappings
        holder.data().installedModules().remove(socketIndex);
        holder.data().installedSnapshots().remove(moduleId);
        holder.data().moduleStates().remove(moduleId);

        if (item != null)
            player.getInventory().addItem(item);

        scheduleSave(player, holder);
        refreshBackpackItemsFor(player, holder);
    }

    private void toggleModule(BackpackMenuHolder holder, ItemStack moduleItem) {
        if (holder == null || moduleItem == null)
            return;
        Keys keys = plugin.keys();
        ItemMeta meta = moduleItem.getItemMeta();
        if (meta == null)
            return;

        Byte enabled = meta.getPersistentDataContainer().get(keys.MODULE_ENABLED, PersistentDataType.BYTE);
        byte newVal = (enabled != null && enabled == 1) ? (byte) 0 : (byte) 1;

        meta.getPersistentDataContainer().set(keys.MODULE_ENABLED, PersistentDataType.BYTE, newVal);
        moduleItem.setItemMeta(meta);

        refreshModuleVisuals(holder, moduleItem);
    }

    private void refreshModuleVisuals(BackpackMenuHolder holder, ItemStack moduleItem) {
        if (moduleItem == null || moduleItem.getType().isAir() || !moduleItem.hasItemMeta())
            return;

        String type = getModuleType(moduleItem);
        if (type == null)
            return;

        if (type.equalsIgnoreCase("Tank")) {
            UUID moduleId = readModuleId(moduleItem);
            byte[] state = moduleId != null
                    ? readModuleState(holder, moduleId, moduleItem)
                    : readModuleStateFromItem(moduleItem);
            TankModuleLogic.applyVisuals(plugin, moduleItem, state);
            return;
        }

        applyModuleLore(moduleItem);
    }

    private void applyModuleLore(ItemStack moduleItem) {
        if (moduleItem == null || moduleItem.getType().isAir() || !moduleItem.hasItemMeta())
            return;

        String type = getModuleType(moduleItem);
        if (type == null)
            return;

        var def = plugin.cfg().findUpgrade(type);
        if (def == null)
            return;

        ItemMeta meta = moduleItem.getItemMeta();
        if (meta == null)
            return;

        meta.displayName(Text.c(Placeholders.expandText(plugin, def, moduleItem, def.displayName())));
        List<String> expanded = Placeholders.expandLore(plugin, def, moduleItem, def.lore());
        meta.lore(Text.lore(expanded));
        moduleItem.setItemMeta(meta);
    }

    private void updateModuleSnapshot(BackpackMenuHolder holder, ItemStack moduleItem) {
        Keys keys = plugin.keys();
        ItemMeta meta = moduleItem.getItemMeta();
        if (meta == null)
            return;

        String idStr = meta.getPersistentDataContainer().get(keys.MODULE_ID, PersistentDataType.STRING);
        if (idStr == null)
            return;

        UUID moduleId = UUID.fromString(idStr);
        holder.data().installedSnapshots().put(moduleId,
                ItemStackCodec.toBytes(new ItemStack[] { moduleItem.clone() }));
    }

    private void openModuleScreen(Player player, BackpackMenuHolder holder, ItemStack moduleItem) {
        ItemMeta meta = moduleItem.getItemMeta();
        if (meta == null)
            return;

        Keys keys = plugin.keys();
        String moduleType = meta.getPersistentDataContainer().get(keys.MODULE_TYPE, PersistentDataType.STRING);
        if (moduleType == null)
            return;

        var def = plugin.cfg().findUpgrade(moduleType);
        if (def == null)
            return;

        String idStr = meta.getPersistentDataContainer().get(keys.MODULE_ID, PersistentDataType.STRING);
        if (idStr == null)
            return;

        UUID moduleId = UUID.fromString(idStr);

        // Ensure the module install + current backpack state is persisted before we
        // open a module UI that loads/saves via the repository.
        flushSaveNow(player, holder);

        screens.open(player, holder.backpackId(), holder.type().id(), moduleId, def.screenType());
    }

    private int pageCount(BackpackMenuHolder holder) {
        if (!holder.paginated())
            return 1;
        int logical = holder.logicalSlots();
        return Math.max(1, (int) Math.ceil(logical / 45.0));
    }

    private ItemStack insertIntoBackpackLogical(BackpackMenuHolder holder, ItemStack stack) {
        if (stack == null || stack.getType().isAir())
            return stack;
        if (!plugin.cfg().isAllowedInBackpack(stack))
            return stack;

        ItemStack[] logical = ItemStackCodec.fromBytes(holder.data().contentsBytes());
        int logicalSize = holder.logicalSlots();

        if (logical.length != logicalSize) {
            ItemStack[] resized = new ItemStack[logicalSize];
            System.arraycopy(logical, 0, resized, 0, Math.min(logical.length, logicalSize));
            logical = resized;
        }

        // Prefer inserting into the CURRENT page range first (prevents client-side
        // sorting mods from using shift-click to accidentally rewrite earlier pages).
        if (holder.paginated()) {
            int start = holder.page() * 45;
            int end = Math.min(start + 45, logical.length);
            stack = insertIntoLogicalRange(logical, start, end, stack);
            if (stack == null || stack.getAmount() <= 0) {
                holder.data().contentsBytes(ItemStackCodec.toBytes(logical));
                return null;
            }
        }

        // Fallback: insert anywhere (vanilla-ish behavior if current page is full)
        stack = insertIntoLogicalRange(logical, 0, logical.length, stack);

        holder.data().contentsBytes(ItemStackCodec.toBytes(logical));
        return stack;
    }

    private ItemStack insertIntoLogicalRange(ItemStack[] logical, int start, int end, ItemStack stack) {
        if (stack == null || stack.getType().isAir())
            return stack;
        start = Math.max(0, start);
        end = Math.max(start, Math.min(end, logical.length));

        // merge
        for (int i = start; i < end; i++) {
            ItemStack cur = logical[i];
            if (cur == null || cur.getType().isAir())
                continue;
            if (!cur.isSimilar(stack))
                continue;

            int max = cur.getMaxStackSize();
            int space = max - cur.getAmount();
            if (space <= 0)
                continue;

            int toMove = Math.min(space, stack.getAmount());
            cur.setAmount(cur.getAmount() + toMove);
            stack.setAmount(stack.getAmount() - toMove);

            if (stack.getAmount() <= 0) {
                return null;
            }
        }

        // empty slots
        for (int i = start; i < end; i++) {
            ItemStack cur = logical[i];
            if (cur != null && !cur.getType().isAir())
                continue;

            int toPlace = Math.min(stack.getMaxStackSize(), stack.getAmount());
            ItemStack placed = stack.clone();
            placed.setAmount(toPlace);
            logical[i] = placed;

            stack.setAmount(stack.getAmount() - toPlace);
            if (stack.getAmount() <= 0) {
                return null;
            }
        }

        return stack;
    }

    private void changePage(Player player, BackpackMenuHolder holder, int newPage) {
        // Sync current page -> data (no DB write)
        renderer.saveVisibleStorageToData(holder);

        int clamped = Math.max(0, Math.min(newPage, pageCount(holder) - 1));
        if (clamped == holder.page())
            return;

        holder.page(clamped);

        // Debounce-save after mutation
        scheduleSave(player, holder);

        if (!plugin.cfg().resizeGui()) {
            renderer.render(holder);
            return;
        }

        // Reopen next tick (resize GUI mode)
        int now = Bukkit.getCurrentTick();
        ignoreCloseUntilTick.put(player.getUniqueId(), now + 1);

        Bukkit.getScheduler().runTask(plugin, () -> {
            renderer.openMenu(player, holder.data(), holder.type(), holder.page());
        });

    }

    private String getModuleType(ItemStack item) {
        if (item == null || !item.hasItemMeta())
            return null;
        ItemMeta meta = item.getItemMeta();
        String type = meta.getPersistentDataContainer().get(plugin.keys().MODULE_TYPE, PersistentDataType.STRING);
        return type;
    }

    private boolean isModuleTypeInstalled(BackpackMenuHolder holder, String moduleType) {
        if (moduleType == null)
            return false;

        // installedModules(): Map<Integer, UUID>
        for (UUID moduleId : holder.data().installedModules().values()) {
            if (moduleId == null)
                continue;

            byte[] snap = holder.data().installedSnapshots().get(moduleId);
            if (snap == null)
                continue;

            ItemStack[] arr = ItemStackCodec.fromBytes(snap);
            if (arr.length == 0 || arr[0] == null)
                continue;

            String t = getModuleType(arr[0]);
            if (t != null && t.equalsIgnoreCase(moduleType))
                return true;
        }
        return false;
    }

    private boolean isEmptySocket(ItemStack item) {
        if (item == null || item.getType().isAir())
            return true;

        // If it is a socket placeholder, not a module
        return !isModuleItem(item);
    }

    private byte[] readModuleStateFromItem(ItemStack item) {
        if (item == null || !item.hasItemMeta())
            return null;

        ItemMeta meta = item.getItemMeta();
        String b64 = meta.getPersistentDataContainer().get(plugin.keys().MODULE_STATE_B64, PersistentDataType.STRING);
        if (b64 == null || b64.isBlank())
            return null;

        try {
            return java.util.Base64.getDecoder().decode(b64);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private void writeModuleStateToItem(ItemStack item, byte[] state) {
        if (item == null)
            return;

        ItemMeta meta = item.getItemMeta();
        if (meta == null)
            return;

        var pdc = meta.getPersistentDataContainer();

        if (state == null || state.length == 0) {
            pdc.remove(plugin.keys().MODULE_STATE_B64);
        } else {
            String b64 = java.util.Base64.getEncoder().encodeToString(state);
            pdc.set(plugin.keys().MODULE_STATE_B64, PersistentDataType.STRING, b64);
        }

        item.setItemMeta(meta);
    }

    private void markInteraction(Player player, BackpackMenuHolder holder) {
        int now = Bukkit.getCurrentTick();

        lastStorageInteractionTick.put(player.getUniqueId(), now);
        dirtySinceTick.put(player.getUniqueId(), now);

        scheduleSave(player, holder);
    }

    private void scheduleSave(Player player, BackpackMenuHolder holder) {
        SaveKey key = new SaveKey(player.getUniqueId(), holder.backpackId());

        BukkitTask existing = pendingSaves.remove(key);
        if (existing != null)
            existing.cancel();

        UUID backpackId = holder.backpackId();

        pendingSaves.put(key, Bukkit.getScheduler().runTaskLater(plugin, () -> {
            // Remove our own task record up front so any reschedule doesn't get removed by
            // this runnable.
            pendingSaves.remove(key);

            int now = Bukkit.getCurrentTick();
            if (player == null || !player.isOnline()) {
                dirtySinceTick.remove(key.playerId());
                return;
            }
            if (!isSafeToPersist(player, now)) {
                // IPN-style sorting does many click packets over multiple ticks. If we save
                // mid-sort, we can persist a transient "in-flight" state and effectively
                // delete items from the DB snapshot. Keep deferring until stable.
                scheduleSave(player, holder);
                return;
            }

            Inventory top = player.getOpenInventory().getTopInventory();
            if (top.getHolder() instanceof BackpackMenuHolder current
                    && current.backpackId().equals(backpackId)) {

                renderer.saveVisibleStorageToData(current);
                plugin.repo().saveBackpack(current.data());
                refreshBackpackItemsFor(player, current);
                plugin.sessions().refreshLinkedBackpacksThrottled(current.backpackId(), current.data());
                dirtySinceTick.remove(player.getUniqueId());
            }

        }, SAVE_DEBOUNCE_TICKS));
    }

    private void flushSaveNow(Player player, BackpackMenuHolder holder) {
        SaveKey key = new SaveKey(player.getUniqueId(), holder.backpackId());

        BukkitTask existing = pendingSaves.remove(key);
        if (existing != null)
            existing.cancel();

        Inventory top = player.getOpenInventory().getTopInventory();
        if (top.getHolder() instanceof BackpackMenuHolder current
                && current.backpackId().equals(holder.backpackId())) {
            int now = Bukkit.getCurrentTick();
            if (!isSafeToPersist(player, now)) {
                scheduleSave(player, holder);
                return;
            }
            renderer.saveVisibleStorageToData(current);
            plugin.repo().saveBackpack(current.data());
            refreshBackpackItemsFor(player, current);
            plugin.sessions().refreshLinkedBackpacksThrottled(current.backpackId(), current.data());
        } else {
            renderer.saveVisibleStorageToData(holder);
            plugin.repo().saveBackpack(holder.data());
            refreshBackpackItemsFor(player, holder);
            plugin.sessions().refreshLinkedBackpacksThrottled(holder.backpackId(), holder.data());
        }

        dirtySinceTick.remove(player.getUniqueId());
    }

    private void playSocketSuccess(Player player) {
        player.playSound(player.getLocation(), Sound.BLOCK_END_PORTAL_FRAME_FILL, 1.0f, 1.0f);
    }

    private void playSocketFail(Player player) {
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
    }

    private boolean isSortingModBurst(Player player, int now) {
        UUID playerId = player.getUniqueId();

        int until = cancelClicksUntilTick.getOrDefault(playerId, -1);
        if (now <= until) {
            // Keep extending the block window while spam continues.
            cancelClicksUntilTick.put(playerId, now + SORT_MOD_CANCEL_WINDOW_TICKS);
            return true;
        }

        int lastTick = sortBurstTick.getOrDefault(playerId, Integer.MIN_VALUE);
        int count = (lastTick == now) ? (sortBurstCount.getOrDefault(playerId, 0) + 1) : 1;

        sortBurstTick.put(playerId, now);
        sortBurstCount.put(playerId, count);

        java.util.ArrayDeque<Integer> window = sortWindowTicks.computeIfAbsent(playerId,
                _k -> new java.util.ArrayDeque<>());
        window.addLast(now);
        while (!window.isEmpty() && (now - window.peekFirst()) > SORT_MOD_WINDOW_TICKS) {
            window.removeFirst();
        }

        if (count >= SORT_MOD_PER_TICK_THRESHOLD || window.size() >= SORT_MOD_WINDOW_THRESHOLD) {
            cancelClicksUntilTick.put(playerId, now + SORT_MOD_CANCEL_WINDOW_TICKS);

            int lastNotified = sortBurstNotifiedAtTick.getOrDefault(playerId, Integer.MIN_VALUE);
            if ((now - lastNotified) > 40) {
                sortBurstNotifiedAtTick.put(playerId, now);
                player.sendMessage(Text.c("&cClient-side sorting is blocked in backpacks. Use the &eSort &cbutton."));
            }

            return true;
        }

        return false;
    }

    private boolean isSafeToPersist(Player player, int now) {
        if (player == null || !player.isOnline())
            return false;

        UUID playerId = player.getUniqueId();

        int until = cancelClicksUntilTick.getOrDefault(playerId, -1);
        if (now <= until)
            return false;

        int last = lastStorageInteractionTick.getOrDefault(playerId, Integer.MIN_VALUE);
        if (last != Integer.MIN_VALUE && (now - last) <= SAVE_QUIET_TICKS)
            return false;

        ItemStack cursor = player.getItemOnCursor();
        if (cursor != null && !cursor.getType().isAir())
            return false;

        return true;
    }

    private void stabilizeAfterBurst(Player player, BackpackMenuHolder holder) {
        if (player == null || holder == null)
            return;

        boolean changed = stashCursorIntoBackpackOrPlayer(player, holder);
        if (changed) {
            markInteraction(player, holder);
        }

        Bukkit.getScheduler().runTask(plugin, player::updateInventory);
    }

    private boolean stashCursorIntoBackpackOrPlayer(Player player, BackpackMenuHolder holder) {
        ItemStack cursor = player.getItemOnCursor();
        if (cursor == null || cursor.getType().isAir())
            return false;

        Inventory inv = holder.getInventory();
        if (inv == null) {
            return stashCursorIntoPlayer(player);
        }

        boolean hasNavRow = holder.paginated() || holder.type().upgradeSlots() > 0;
        int invSize = inv.getSize();
        int storageSize = SlotLayout.storageAreaSize(invSize, hasNavRow);

        int valid = storageSize;
        if (holder.paginated()) {
            int remaining = holder.logicalSlots() - holder.page() * 45;
            int pageValid = Math.max(0, Math.min(45, remaining));
            valid = Math.min(pageValid, storageSize);
        }

        ItemStack remaining = cursor.clone();

        // Merge into similar stacks first.
        for (int i = 0; i < valid; i++) {
            ItemStack cur = inv.getItem(i);
            if (cur == null || cur.getType().isAir())
                continue;
            if (!cur.isSimilar(remaining))
                continue;

            int max = cur.getMaxStackSize();
            int space = max - cur.getAmount();
            if (space <= 0)
                continue;

            int toMove = Math.min(space, remaining.getAmount());
            cur.setAmount(cur.getAmount() + toMove);
            remaining.setAmount(remaining.getAmount() - toMove);
            inv.setItem(i, cur);

            if (remaining.getAmount() <= 0) {
                player.setItemOnCursor(null);
                return true;
            }
        }

        // Put remaining into an empty slot.
        for (int i = 0; i < valid; i++) {
            ItemStack cur = inv.getItem(i);
            if (cur != null && !cur.getType().isAir())
                continue;

            inv.setItem(i, remaining);
            player.setItemOnCursor(null);
            return true;
        }

        // No room: move to player inventory (and drop overflow).
        player.setItemOnCursor(null);
        var leftovers = player.getInventory().addItem(remaining);
        for (ItemStack left : leftovers.values()) {
            if (left == null || left.getType().isAir())
                continue;
            player.getWorld().dropItemNaturally(player.getLocation(), left);
        }
        return true;
    }

    private boolean stashCursorIntoPlayer(Player player) {
        ItemStack cursor = player.getItemOnCursor();
        if (cursor == null || cursor.getType().isAir())
            return false;

        player.setItemOnCursor(null);
        var leftovers = player.getInventory().addItem(cursor);
        for (ItemStack left : leftovers.values()) {
            if (left == null || left.getType().isAir())
                continue;
            player.getWorld().dropItemNaturally(player.getLocation(), left);
        }
        return true;
    }

    private void sortBackpack(BackpackMenuHolder holder) {
        // Ensure the current visible page is merged into the logical contents first.
        renderer.saveVisibleStorageToData(holder);

        int logicalSize = holder.logicalSlots();
        ItemStack[] logical = ItemStackCodec.fromBytes(holder.data().contentsBytes());

        if (logical.length != logicalSize) {
            ItemStack[] resized = new ItemStack[logicalSize];
            System.arraycopy(logical, 0, resized, 0, Math.min(logical.length, logicalSize));
            logical = resized;
        }

        List<ItemStack> items = new java.util.ArrayList<>(logical.length);
        for (ItemStack it : logical) {
            if (it == null || it.getType().isAir())
                continue;
            items.add(it);
        }

        items.sort(BackpackSortMode.comparator(plugin, holder.sortMode()));

        ItemStack[] out = new ItemStack[logicalSize];
        for (int i = 0; i < Math.min(out.length, items.size()); i++) {
            out[i] = items.get(i).clone();
        }

        holder.data().contentsBytes(ItemStackCodec.toBytes(out));
    }

}
