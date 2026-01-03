package io.github.tootertutor.ModularPacks.modules;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.MenuType;
import org.bukkit.inventory.ItemStack;

import io.github.tootertutor.ModularPacks.ModularPacksPlugin;
import io.github.tootertutor.ModularPacks.data.BackpackData;
import io.github.tootertutor.ModularPacks.data.ItemStackCodec;
import net.kyori.adventure.text.Component;

public final class StonecutterModuleUi {

    private static final Map<UUID, Session> SESSIONS = new ConcurrentHashMap<>();

    public record Session(UUID backpackId, String backpackType, UUID moduleId) {
    }

    private StonecutterModuleUi() {
    }

    public static boolean hasSession(Player player) {
        return player != null && SESSIONS.containsKey(player.getUniqueId());
    }

    public static Session session(Player player) {
        if (player == null)
            return null;
        return SESSIONS.get(player.getUniqueId());
    }

    public static void open(
            ModularPacksPlugin plugin,
            Player player,
            UUID backpackId,
            String backpackType,
            UUID moduleId) {
        if (plugin == null || player == null || !player.isOnline())
            return;

        InventoryView view = MenuType.STONECUTTER.builder()
                .title(Component.text("Stonecutter Module"))
                .location(player.getLocation())
                .checkReachable(false)
                .build(player);
        if (view == null)
            return;

        Inventory top = view.getTopInventory();

        BackpackData data = plugin.repo().loadOrCreate(backpackId, backpackType);
        byte[] state = data.moduleStates().get(moduleId);
        if (state != null && state.length > 0) {
            try {
                ItemStack[] saved = ItemStackCodec.fromBytes(state);
                int limit = Math.min(saved.length, top.getSize());
                for (int i = 0; i < limit; i++) {
                    top.setItem(i, saved[i]);
                }
            } catch (Exception ignored) {
            }
        }

        // Output is derived.
        if (top.getSize() > 1) {
            top.setItem(1, null);
        }

        player.openInventory(view);
        SESSIONS.put(player.getUniqueId(), new Session(backpackId, backpackType, moduleId));
        player.updateInventory();
    }

    public static void handleClose(ModularPacksPlugin plugin, Player player, Inventory inv) {
        if (plugin == null || player == null || inv == null)
            return;

        Session session = SESSIONS.remove(player.getUniqueId());
        if (session == null)
            return;

        ItemStack[] items = new ItemStack[inv.getSize()];
        for (int i = 0; i < items.length; i++) {
            items[i] = inv.getItem(i);
        }
        if (items.length > 1)
            items[1] = null;

        // Prevent vanilla from returning inputs on close; they are stored in module state.
        inv.clear();

        BackpackData data = plugin.repo().loadOrCreate(session.backpackId(), session.backpackType());
        data.moduleStates().put(session.moduleId(), ItemStackCodec.toBytes(items));
        plugin.repo().saveBackpack(data);
        plugin.sessions().refreshLinkedBackpacksThrottled(session.backpackId(), data);
        plugin.sessions().onRelatedInventoryClose(player, session.backpackId());
    }
}

