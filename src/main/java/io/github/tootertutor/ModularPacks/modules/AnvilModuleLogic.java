package io.github.tootertutor.ModularPacks.modules;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.MenuType;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;

import io.github.tootertutor.ModularPacks.ModularPacksPlugin;
import io.github.tootertutor.ModularPacks.data.BackpackData;
import io.github.tootertutor.ModularPacks.data.ItemStackCodec;
import net.kyori.adventure.text.Component;

public final class AnvilModuleLogic {

    // Anvil slots: 0 = left, 1 = right, 2 = output
    private static final int LEFT = 0;
    private static final int RIGHT = 1;

    private static final Map<UUID, Session> SESSIONS = new ConcurrentHashMap<>();

    private record Session(UUID backpackId, String backpackType, UUID moduleId) {
    }

    private AnvilModuleLogic() {
    }

    /** True only if THIS plugin opened an anvil-module UI for the player. */
    public static boolean hasSession(Player player) {
        return player != null && SESSIONS.containsKey(player.getUniqueId());
    }

    public static UUID sessionBackpackId(Player player) {
        if (player == null)
            return null;
        Session s = SESSIONS.get(player.getUniqueId());
        return s == null ? null : s.backpackId();
    }

    /** Open a real vanilla anvil UI and seed slots 0/1 from module state. */
    public static void open(ModularPacksPlugin plugin, Player player,
            UUID backpackId, String backpackType, UUID moduleId) {
        if (plugin == null || player == null || !player.isOnline())
            return;

        // Real vanilla anvil UI (no block required when checkReachable=false)
        InventoryView view = MenuType.ANVIL.builder()
                .title(Component.text("Anvil Module"))
                .location(player.getLocation())
                .checkReachable(false)
                .build(player);
        if (view == null)
            return;

        Inventory top = view.getTopInventory();
        if (!(top instanceof AnvilInventory anvil) || top.getType() != InventoryType.ANVIL)
            return;

        // Load persisted left/right
        BackpackData data = plugin.repo().loadOrCreate(backpackId, backpackType);
        byte[] state = data.moduleStates().get(moduleId);

        ItemStack left = null;
        ItemStack right = null;

        if (state != null && state.length > 0) {
            try {
                ItemStack[] saved = ItemStackCodec.fromBytes(state);
                if (saved.length > 0)
                    left = saved[0];
                if (saved.length > 1)
                    right = saved[1];
            } catch (Exception ignored) {
            }
        }

        anvil.setItem(LEFT, left);
        anvil.setItem(RIGHT, right);

        // Never seed output; vanilla computes it. Clear any stale output.
        if (anvil.getSize() > 2)
            anvil.setItem(2, null);

        // Actually open the view for the player.
        player.openInventory(view);

        // Track this as "ours" only after the view was opened.
        SESSIONS.put(player.getUniqueId(), new Session(backpackId, backpackType, moduleId));

        player.updateInventory();
    }

    /**
     * Save left/right slots for this player's anvil-module session.
     * Call from InventoryCloseEvent, guarded by hasSession(player).
     */
    public static void handleClose(ModularPacksPlugin plugin, Player player, Inventory inv) {
        if (player == null || inv == null)
            return;
        if (inv.getType() != InventoryType.ANVIL)
            return;

        Session session = SESSIONS.remove(player.getUniqueId());
        if (session == null)
            return; // not our anvil

        if (!(inv instanceof AnvilInventory anvil))
            return;

        ItemStack left = anvil.getItem(0);
        ItemStack right = anvil.getItem(1);

        // ✅ CRITICAL: clear inputs BEFORE vanilla returns them to the player
        anvil.setItem(0, null);
        anvil.setItem(1, null);
        if (anvil.getSize() > 2)
            anvil.setItem(2, null);

        // Persist ONLY slots 0/1
        byte[] bytes = ItemStackCodec.toBytes(new ItemStack[] { left, right });

        BackpackData data = plugin.repo().loadOrCreate(session.backpackId(), session.backpackType());
        data.moduleStates().put(session.moduleId(), bytes);
        plugin.repo().saveBackpack(data);
        plugin.sessions().refreshLinkedBackpacksThrottled(session.backpackId(), data);
        plugin.sessions().onRelatedInventoryClose(player, session.backpackId());
    }

}
