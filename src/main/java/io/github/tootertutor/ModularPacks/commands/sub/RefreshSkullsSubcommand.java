package io.github.tootertutor.ModularPacks.commands.sub;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import io.github.tootertutor.ModularPacks.ModularPacksPlugin;
import io.github.tootertutor.ModularPacks.commands.CommandContext;
import io.github.tootertutor.ModularPacks.commands.Subcommand;
import io.github.tootertutor.ModularPacks.config.BackpackTypeDef;
import io.github.tootertutor.ModularPacks.data.BackpackData;
import io.github.tootertutor.ModularPacks.item.BackpackItems;
import io.github.tootertutor.ModularPacks.item.Keys;
import net.kyori.adventure.text.Component;

public final class RefreshSkullsSubcommand implements Subcommand {

    private final ModularPacksPlugin plugin;
    private final BackpackItems backpackItems;

    public RefreshSkullsSubcommand(ModularPacksPlugin plugin) {
        this.plugin = plugin;
        this.backpackItems = new BackpackItems(plugin);
    }

    @Override
    public String name() {
        return "refreshskulls";
    }

    @Override
    public String description() {
        return "Refresh backpack skull textures (online players)";
    }

    @Override
    public void execute(CommandContext ctx) {
        CommandSender sender = ctx.sender();
        if (!sender.hasPermission("modularpacks.admin")) {
            sender.sendMessage(Component.text("You do not have permission."));
            return;
        }

        boolean includeOpen = ctx.args().stream().anyMatch(s -> s.equalsIgnoreCase("--open"));
        boolean includeEnder = !ctx.args().stream().anyMatch(s -> s.equalsIgnoreCase("--no-ender"));

        String target = (ctx.size() >= 1) ? ctx.arg(0) : "all";
        target = target == null ? "all" : target.trim();
        if (target.isEmpty())
            target = "all";

        Map<UUID, BackpackData> dataCache = new HashMap<>();
        Map<String, BackpackTypeDef> typeCache = new HashMap<>();
        Map<UUID, String> typeIdCache = new HashMap<>();

        Totals totals = new Totals();

        if (!target.equalsIgnoreCase("all")) {
            Player p = Bukkit.getPlayerExact(target);
            if (p == null) {
                sender.sendMessage(Component.text("Player must be online: " + target));
                return;
            }
            refreshPlayer(p, includeEnder, includeOpen, dataCache, typeCache, typeIdCache, totals);
            sender.sendMessage(Component.text(summary(totals, 1, includeEnder, includeOpen)));
            return;
        }

        int players = 0;
        for (Player p : Bukkit.getOnlinePlayers()) {
            players++;
            refreshPlayer(p, includeEnder, includeOpen, dataCache, typeCache, typeIdCache, totals);
        }

        sender.sendMessage(Component.text(summary(totals, players, includeEnder, includeOpen)));
    }

    private void refreshPlayer(
            Player player,
            boolean includeEnder,
            boolean includeOpen,
            Map<UUID, BackpackData> dataCache,
            Map<String, BackpackTypeDef> typeCache,
            Map<UUID, String> typeIdCache,
            Totals totals) {
        if (player == null || !player.isOnline())
            return;

        totals.playersTouched++;

        totals.updated += refreshInventory(player.getInventory(), dataCache, typeCache, typeIdCache, totals);

        if (includeEnder) {
            totals.updated += refreshInventory(player.getEnderChest(), dataCache, typeCache, typeIdCache, totals);
        }

        if (includeOpen) {
            Inventory top = player.getOpenInventory() == null ? null : player.getOpenInventory().getTopInventory();
            if (top != null) {
                totals.updated += refreshInventory(top, dataCache, typeCache, typeIdCache, totals);
            }
            ItemStack cursor = player.getItemOnCursor();
            if (isBackpack(cursor)) {
                totals.scanned++;
                if (refreshInPlace(cursor, dataCache, typeCache, typeIdCache, totals)) {
                    player.setItemOnCursor(cursor);
                    totals.updated++;
                }
            }
        }

        player.updateInventory();
    }

    private int refreshInventory(
            Inventory inv,
            Map<UUID, BackpackData> dataCache,
            Map<String, BackpackTypeDef> typeCache,
            Map<UUID, String> typeIdCache,
            Totals totals) {
        if (inv == null)
            return 0;

        int updated = 0;
        ItemStack[] contents = inv.getContents();
        if (contents == null || contents.length == 0)
            return 0;

        for (int i = 0; i < contents.length; i++) {
            ItemStack it = contents[i];
            if (!isBackpack(it))
                continue;

            totals.scanned++;

            if (refreshInPlace(it, dataCache, typeCache, typeIdCache, totals)) {
                inv.setItem(i, it);
                updated++;
            }
        }

        return updated;
    }

    private boolean refreshInPlace(
            ItemStack backpackItem,
            Map<UUID, BackpackData> dataCache,
            Map<String, BackpackTypeDef> typeCache,
            Map<UUID, String> typeIdCache,
            Totals totals) {
        UUID backpackId = readBackpackId(backpackItem);
        if (backpackId == null) {
            totals.skippedNoId++;
            return false;
        }

        String typeId = typeIdCache.get(backpackId);
        if (typeId == null) {
            String db = plugin.repo().findBackpackType(backpackId);
            if (db != null && !db.isBlank()) {
                typeId = db;
            } else {
                typeId = readBackpackType(backpackItem);
            }
            if (typeId != null)
                typeIdCache.put(backpackId, typeId);
        }

        if (typeId == null || typeId.isBlank()) {
            totals.skippedUnknownType++;
            return false;
        }

        BackpackTypeDef type = typeCache.get(typeId.toLowerCase(Locale.ROOT));
        if (type == null) {
            type = plugin.cfg().findType(typeId);
            if (type != null)
                typeCache.put(type.id().toLowerCase(Locale.ROOT), type);
        }
        if (type == null) {
            totals.skippedUnknownType++;
            return false;
        }

        BackpackData data = dataCache.get(backpackId);
        if (data == null) {
            data = plugin.repo().loadOrCreate(backpackId, type.id());
            dataCache.put(backpackId, data);
        }

        return backpackItems.refreshInPlace(backpackItem, type, backpackId, data, type.rows() * 9);
    }

    private boolean isBackpack(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta())
            return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null)
            return false;
        var pdc = meta.getPersistentDataContainer();
        Keys keys = plugin.keys();
        return pdc.has(keys.BACKPACK_ID, PersistentDataType.STRING) && pdc.has(keys.BACKPACK_TYPE, PersistentDataType.STRING);
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

    private String readBackpackType(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta())
            return null;
        ItemMeta meta = item.getItemMeta();
        if (meta == null)
            return null;
        return meta.getPersistentDataContainer().get(plugin.keys().BACKPACK_TYPE, PersistentDataType.STRING);
    }

    private static String summary(Totals totals, int players, boolean includeEnder, boolean includeOpen) {
        String scope = includeEnder ? "inventories+enderchests" : "inventories";
        if (includeOpen)
            scope += "+open";
        return "Refreshed skull textures for " + totals.updated + " backpack item(s) across " + players + " player(s) (" + scope
                + "). Skipped: noId=" + totals.skippedNoId + ", unknownType=" + totals.skippedUnknownType;
    }

    private static final class Totals {
        int playersTouched;
        int scanned;
        int updated;
        int skippedNoId;
        int skippedUnknownType;
    }
}

