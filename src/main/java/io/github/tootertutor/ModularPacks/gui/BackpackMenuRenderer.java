package io.github.tootertutor.ModularPacks.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import io.github.tootertutor.ModularPacks.ModularPacksPlugin;
import io.github.tootertutor.ModularPacks.config.BackpackTypeDef;
import io.github.tootertutor.ModularPacks.data.BackpackData;
import io.github.tootertutor.ModularPacks.data.ItemStackCodec;
import io.github.tootertutor.ModularPacks.item.Keys;
import io.github.tootertutor.ModularPacks.modules.TankModuleLogic;
import io.github.tootertutor.ModularPacks.text.Text;
import net.kyori.adventure.text.Component;

public final class BackpackMenuRenderer {

    private final ModularPacksPlugin plugin;

    public BackpackMenuRenderer(ModularPacksPlugin plugin) {
        this.plugin = plugin;
    }

    public Inventory openMenu(Player player, UUID backpackId, String typeId) {
        var type = plugin.cfg().findType(typeId);
        if (type == null)
            return null;

        BackpackData data = plugin.repo().loadOrCreate(backpackId, type.id());

        boolean paginated = type.rows() > 5;
        boolean hasNavRow = paginated || type.upgradeSlots() > 0;

        // inventory rows:
        // - paginated: always 6 (5 storage rows + 1 nav row)
        // - non-paginated: rows (+1 if we need a nav row for upgrade sockets)
        int invRows = paginated ? 6 : (hasNavRow ? type.rows() + 1 : type.rows());

        // Page size for logical paging (always 45 for paginated, otherwise irrelevant)
        int pageSize = paginated ? 45 : type.rows() * 9;

        // upgrade sockets live in bottom row (even for non-paginated if hasNavRow)
        int invSize = invRows * 9;
        List<Integer> upgradeSlots = hasNavRow
                ? SlotLayout.upgradeSocketSlots(invSize, type.upgradeSlots())
                : List.of();

        BackpackMenuHolder holder = new BackpackMenuHolder(
                backpackId,
                type,
                data,
                paginated,
                pageSize,
                upgradeSlots);

        Component title = Text.c("&8Backpack &7(" + type.displayName() + ")");
        Inventory inv = Bukkit.createInventory(holder, invSize, title);
        holder.setInventory(inv);

        // clamp page to valid range (important if saved page index is stale)
        int pageCount = pageCount(holder);
        if (holder.page() < 0)
            holder.page(0);
        if (holder.page() >= pageCount)
            holder.page(Math.max(0, pageCount - 1));

        render(holder);

        player.openInventory(inv);
        return inv;
    }

    public Inventory openMenu(Player player, UUID backpackId, String typeId, int page) {
        var type = plugin.cfg().findType(typeId);
        if (type == null)
            return null;

        BackpackData data = plugin.repo().loadOrCreate(backpackId, type.id());

        boolean paginated = type.rows() > 5;
        boolean hasNavRow = paginated || type.upgradeSlots() > 0;

        int invRows;
        if (paginated) {
            // Option B sizing: if ResizeGUI=true, shrink storage rows for last page based
            // on valid slots
            if (plugin.cfg().resizeGui()) {
                int logicalSlots = type.rows() * 9;
                int remaining = logicalSlots - page * 45;
                int valid = Math.max(0, Math.min(45, remaining));
                int storageRows = Math.max(1, (int) Math.ceil(valid / 9.0)); // 1..5
                invRows = storageRows + 1; // + nav row
            } else {
                invRows = 6; // 5 storage + nav
            }
        } else {
            invRows = hasNavRow ? type.rows() + 1 : type.rows();
        }

        int invSize = invRows * 9;
        List<Integer> upgradeSlots = hasNavRow
                ? SlotLayout.upgradeSocketSlots(invSize, type.upgradeSlots())
                : List.of();

        int pageSize = paginated ? 45 : type.rows() * 9;

        BackpackMenuHolder holder = new BackpackMenuHolder(backpackId, type, data, paginated, pageSize, upgradeSlots);

        // clamp and set page
        int pc = paginated ? Math.max(1, (int) Math.ceil((type.rows() * 9) / 45.0)) : 1;
        page = Math.max(0, Math.min(page, pc - 1));
        holder.page(page);

        Component title = Text.c("&8Backpack &7(" + type.displayName() + ")");
        Inventory inv = Bukkit.createInventory(holder, invSize, title);
        holder.setInventory(inv);

        render(holder);

        player.openInventory(inv);
        return inv;
    }

    public void openMenu(Player player, BackpackData data, BackpackTypeDef type, int page) {
        boolean paginated = type.rows() > 5;
        boolean hasNavRow = paginated || type.upgradeSlots() > 0;

        int invRows;

        if (paginated) {
            if (plugin.cfg().resizeGui()) {
                int logicalSlots = type.rows() * 9;
                int remaining = logicalSlots - page * 45;
                int valid = Math.max(0, Math.min(45, remaining));
                int storageRows = Math.max(1, (int) Math.ceil(valid / 9.0));
                invRows = storageRows + 1; // + nav row
            } else {
                invRows = 6;
            }
        } else {
            invRows = hasNavRow ? type.rows() + 1 : type.rows();
        }

        int invSize = invRows * 9;
        List<Integer> upgradeSlots = hasNavRow
                ? SlotLayout.upgradeSocketSlots(invSize, type.upgradeSlots())
                : java.util.Collections.emptyList();

        int pageSize = paginated ? 45 : type.rows() * 9;

        BackpackMenuHolder holder = new BackpackMenuHolder(
                data.backpackId(),
                type,
                data,
                paginated,
                pageSize,
                upgradeSlots);

        int clamped = Math.max(0, Math.min(page, holder.pageCount() - 1));
        holder.page(clamped);

        Component title = Text.c("&8Backpack &7(" + type.displayName() + ")");
        Inventory inv = Bukkit.createInventory(holder, invSize, title);
        holder.setInventory(inv);

        render(holder);
        player.openInventory(inv);
    }

    public void render(BackpackMenuHolder holder) {
        Inventory inv = holder.getInventory();
        inv.clear();

        boolean hasNavRow = hasNavRow(holder);
        int invSize = inv.getSize();
        int navStart = invSize - 9;
        int storageSize = navStart; // everything above nav row

        int visibleStorage = SlotLayout.storageAreaSize(invSize, hasNavRow);

        // load logical contents
        ItemStack[] logical = ItemStackCodec.fromBytes(holder.data().contentsBytes());
        int logicalSize = holder.logicalSlots();

        if (logical.length != logicalSize) {
            ItemStack[] resized = new ItemStack[logicalSize];
            System.arraycopy(logical, 0, resized, 0, Math.min(logical.length, logicalSize));
            logical = resized;
        }

        // draw storage area
        if (holder.paginated()) {
            int offset = holder.page() * 45; // still 45 per page logically
            int maxLogical = holder.logicalSlots();

            for (int i = 0; i < storageSize; i++) {
                int logicalIndex = offset + i;
                if (logicalIndex >= maxLogical)
                    break;
                inv.setItem(i, logical[logicalIndex]);
            }

        } else {
            // non-paginated: visibleStorage might be rows*9 (if hasNavRow) or invSize
            int limit = Math.min(logicalSize, visibleStorage);
            for (int i = 0; i < limit; i++) {
                inv.setItem(i, logical[i]);
            }
        }

        // bottom row (nav row) with fillers + optional buttons + upgrade sockets
        if (hasNavRow) {
            renderNavRow(holder);
            renderUpgradeSockets(holder);
        }

        if (holder.paginated()) {
            int valid = validVisibleSlots(holder, storageSize); // note overload below
            if (valid < storageSize) {
                ItemStack blocked = namedItem(plugin.cfg().navBorderFiller(), "&7");
                for (int i = valid; i < storageSize; i++) {
                    inv.setItem(i, blocked);
                }
            }
        }

        // write-back normalized logical array (so size stays stable)
        holder.data().contentsBytes(ItemStackCodec.toBytes(logical));
    }

    public void saveVisibleStorageToData(BackpackMenuHolder holder) {
        Inventory inv = holder.getInventory();

        boolean hasNavRow = hasNavRow(holder);
        int invSize = inv.getSize();
        int visibleStorage = SlotLayout.storageAreaSize(invSize, hasNavRow);

        ItemStack[] logical = ItemStackCodec.fromBytes(holder.data().contentsBytes());
        int logicalSize = holder.logicalSlots();

        if (logical.length != logicalSize) {
            ItemStack[] resized = new ItemStack[logicalSize];
            System.arraycopy(logical, 0, resized, 0, Math.min(logical.length, logicalSize));
            logical = resized;
        }

        if (holder.paginated()) {
            int offset = holder.page() * 45;
            int invSize2 = inv.getSize();
            int storageSize = hasNavRow ? invSize2 - 9 : invSize2;
            int valid = validVisibleSlots(holder, storageSize);
            int saveSlots = Math.min(valid, storageSize);

            for (int i = 0; i < saveSlots; i++) {

                int logicalIndex = offset + i;
                if (logicalIndex >= logical.length)
                    break;

                ItemStack it = inv.getItem(i);
                logical[logicalIndex] = (it == null ? null : it.clone());
            }
        } else {
            int limit = Math.min(logical.length, visibleStorage);
            for (int i = 0; i < limit; i++) {
                ItemStack it = inv.getItem(i);
                logical[i] = (it == null ? null : it.clone());
            }
        }

        holder.data().contentsBytes(ItemStackCodec.toBytes(logical));

    }

    private void renderNavRow(BackpackMenuHolder holder) {
        Inventory inv = holder.getInventory();
        int invSize = inv.getSize();
        int bottomStart = SlotLayout.bottomRowStart(invSize);

        // Fill bottom row
        ItemStack filler = namedItem(plugin.cfg().navBorderFiller(), "&7");
        for (int slot = bottomStart; slot < invSize; slot++) {
            inv.setItem(slot, filler);
        }

        // Only show prev/next if paginated and valid for current page
        if (holder.paginated()) {
            int pageCount = pageCount(holder);

            int prevSlot = SlotLayout.prevButtonSlot(invSize);
            int nextSlot = SlotLayout.nextButtonSlot(invSize);

            if (holder.page() > 0) {
                inv.setItem(prevSlot, namedItem(plugin.cfg().navPageButtons(), "&ePrevious Page"));
            }
            if (holder.page() < pageCount - 1) {
                inv.setItem(nextSlot, namedItem(plugin.cfg().navPageButtons(), "&eNext Page"));
            }
        }

        int sortSlot = SlotLayout.sortButtonSlot(invSize, holder.upgradeSlots(), holder.paginated());
        if (sortSlot >= 0) {
            List<String> lore = new ArrayList<>();
            lore.add("&7Left-click: &fSort");
            lore.add("&7Right-click: &fChange mode");
            lore.add("&7");
            lore.add("&7Mode:");
            for (BackpackSortMode mode : BackpackSortMode.values()) {
                String color = (mode == holder.sortMode()) ? "&a" : "&7";
                lore.add(color + mode.displayName());
            }

            inv.setItem(sortSlot, namedItem(Material.COMPARATOR, "&eSort", lore));
        }
    }

    private void renderUpgradeSockets(BackpackMenuHolder holder) {
        Inventory inv = holder.getInventory();
        Keys keys = plugin.keys();

        // If this backpack type has 0 upgrade slots, nothing to render.
        if (holder.type().upgradeSlots() <= 0)
            return;

        // holder.upgradeSlots() already contains the bottom-row-centered slots
        for (int socketSlotIndex = 0; socketSlotIndex < holder.upgradeSlots().size(); socketSlotIndex++) {
            int invSlot = holder.upgradeSlots().get(socketSlotIndex);

            UUID moduleId = holder.data().installedModules().get(socketSlotIndex);
            if (moduleId == null) {
                inv.setItem(invSlot, namedItem(plugin.cfg().unlockedUpgradeSlotMaterial(), "&f"));
                continue;
            }

            // We only have the snapshot here (fallback). Use it as the displayed item.
            byte[] snap = holder.data().installedSnapshots().get(moduleId);
            ItemStack display = null;

            if (snap != null) {
                ItemStack[] arr = ItemStackCodec.fromBytes(snap);
                if (arr.length > 0)
                    display = arr[0];
            }

            if (display == null) {
                display = namedItem(Material.BARRIER, "&cMissing Module Item");
                inv.setItem(invSlot, display);
                continue;
            }

            // Ensure it has module_id on it (if snapshot is old)
            ItemMeta meta = display.getItemMeta();
            if (meta != null && !meta.getPersistentDataContainer().has(keys.MODULE_ID, PersistentDataType.STRING)) {
                meta.getPersistentDataContainer().set(keys.MODULE_ID, PersistentDataType.STRING, moduleId.toString());
                display.setItemMeta(meta);
            }

            // Dynamic visuals for Tank module based on stored state
            ItemMeta meta2 = display.getItemMeta();
            if (meta2 != null) {
                String moduleType = meta2.getPersistentDataContainer().get(keys.MODULE_TYPE, PersistentDataType.STRING);
                if (moduleType != null && moduleType.equalsIgnoreCase("Tank")) {
                    TankModuleLogic.applyVisuals(plugin, display, holder.data().moduleStates().get(moduleId));
                }
            }

            inv.setItem(invSlot, display);
        }
    }

    private int validVisibleSlots(BackpackMenuHolder holder, int storageSize) {
        if (!holder.paginated())
            return Math.min(holder.logicalSlots(), storageSize);

        int remaining = holder.logicalSlots() - holder.page() * 45;
        int valid = Math.max(0, Math.min(remaining, 45)); // logical remaining in this page
        return Math.min(valid, storageSize); // can't exceed this inventory's storage area
    }

    private boolean hasNavRow(BackpackMenuHolder holder) {
        return holder.paginated() || holder.type().upgradeSlots() > 0;
    }

    private int pageCount(BackpackMenuHolder holder) {
        if (!holder.paginated())
            return 1;
        int logical = holder.logicalSlots();
        return Math.max(1, (int) Math.ceil(logical / 45.0));
    }

    private static ItemStack namedItem(Material mat, String name) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Text.c(name));
            item.setItemMeta(meta);
        }
        return item;
    }

    private static ItemStack namedItem(Material mat, String name, List<String> lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Text.c(name));
            meta.lore(Text.lore(lore));
            item.setItemMeta(meta);
        }
        return item;
    }
}
