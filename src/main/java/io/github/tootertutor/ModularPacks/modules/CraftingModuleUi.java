package io.github.tootertutor.ModularPacks.modules;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MenuType;

import io.github.tootertutor.ModularPacks.ModularPacksPlugin;
import io.github.tootertutor.ModularPacks.data.BackpackData;
import io.github.tootertutor.ModularPacks.data.ItemStackCodec;
import net.kyori.adventure.text.Component;

public final class CraftingModuleUi {

    private static final Map<UUID, Session> SESSIONS = new ConcurrentHashMap<>();

    public record Session(UUID backpackId, String backpackType, UUID moduleId) {
    }

    private CraftingModuleUi() {
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

        InventoryView view = MenuType.CRAFTING.builder()
                .title(Component.text("Crafting Module"))
                .location(player.getLocation())
                .checkReachable(false)
                .build(player);
        if (view == null)
            return;

        Inventory top = view.getTopInventory();

        // Load saved matrix into this view.
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

        // Output is derived; never trust persisted values.
        if (top.getSize() > 0) {
            top.setItem(0, null);
        }

        // Compute the derived output based on the current matrix.
        CraftingModuleLogic.updateResult(plugin.recipes(), player, top);

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

        // Persist everything except the derived result slot.
        ItemStack[] items = new ItemStack[inv.getSize()];
        for (int i = 0; i < items.length; i++) {
            items[i] = inv.getItem(i);
        }
        if (items.length > 0)
            items[0] = null;

        // Clear matrix BEFORE vanilla returns items to the player
        for (int i = 0; i < inv.getSize(); i++) {
            inv.setItem(i, null);
        }

        BackpackData data = plugin.repo().loadOrCreate(session.backpackId(), session.backpackType());
        data.moduleStates().put(session.moduleId(), ItemStackCodec.toBytes(items));
        plugin.repo().saveBackpack(data);
        plugin.sessions().refreshLinkedBackpacksThrottled(session.backpackId(), data);
        plugin.sessions().onRelatedInventoryClose(player, session.backpackId());
    }
}
