package io.github.tootertutor.ModularPacks.modules;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.MenuType;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.view.FurnaceView;

import io.github.tootertutor.ModularPacks.ModularPacksPlugin;
import io.github.tootertutor.ModularPacks.config.ScreenType;
import io.github.tootertutor.ModularPacks.data.BackpackData;
import net.kyori.adventure.text.Component;

public final class FurnaceModuleLogic {

    private static final Map<UUID, Session> SESSIONS = new ConcurrentHashMap<>();

    public record Session(UUID backpackId, String backpackType, UUID moduleId, ScreenType screenType) {
    }

    private FurnaceModuleLogic() {
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
            UUID moduleId,
            ScreenType screenType) {

        if (plugin == null || player == null || !player.isOnline())
            return;

        if (screenType != ScreenType.SMELTING && screenType != ScreenType.BLASTING && screenType != ScreenType.SMOKING)
            return;

        var builder = switch (screenType) {
            case SMELTING -> MenuType.FURNACE.builder();
            case BLASTING -> MenuType.BLAST_FURNACE.builder();
            case SMOKING -> MenuType.SMOKER.builder();
            default -> null;
        };
        if (builder == null)
            return;

        Component title = switch (screenType) {
            case SMELTING -> Component.text("Smelting Module");
            case BLASTING -> Component.text("Blasting Module");
            case SMOKING -> Component.text("Smoking Module");
            default -> Component.text("Furnace Module");
        };

        FurnaceView view = builder.title(title)
                .location(player.getLocation())
                .checkReachable(false)
                .build(player);
        if (view == null)
            return;

        // Seed inventory + progress BEFORE opening.
        BackpackData data = plugin.repo().loadOrCreate(backpackId, backpackType);
        byte[] stateBytes = data.moduleStates().get(moduleId);
        FurnaceStateCodec.State s = FurnaceStateCodec.decode(stateBytes);

        Inventory top = view.getTopInventory();
        top.setItem(0, s.input);
        top.setItem(1, s.fuel);
        top.setItem(2, s.output);

        view.setBurnTime(s.burnTime, s.burnTotal);
        view.setCookTime(s.cookTime, s.cookTotal);

        player.openInventory(view);

        SESSIONS.put(player.getUniqueId(), new Session(backpackId, backpackType, moduleId, screenType));
        player.updateInventory();
    }

    /**
     * Persist input/fuel/output and preserve progress values from the stored state.
     * Clears slots to prevent vanilla returning items to the player on close.
     */
    public static void handleClose(ModularPacksPlugin plugin, Player player, Inventory inv) {
        if (plugin == null || player == null || inv == null)
            return;

        Session session = SESSIONS.remove(player.getUniqueId());
        if (session == null)
            return;

        ItemStack input = inv.getItem(0);
        ItemStack fuel = inv.getItem(1);
        ItemStack output = inv.getItem(2);

        // Prevent duplication: clear BEFORE vanilla tries to return items.
        inv.setItem(0, null);
        inv.setItem(1, null);
        inv.setItem(2, null);

        BackpackData data = plugin.repo().loadOrCreate(session.backpackId(), session.backpackType());
        byte[] existing = data.moduleStates().get(session.moduleId());
        FurnaceStateCodec.State old = FurnaceStateCodec.decode(existing);

        FurnaceStateCodec.State fs = new FurnaceStateCodec.State();
        fs.input = input;
        fs.fuel = fuel;
        fs.output = output;

        fs.burnTime = old.burnTime;
        fs.burnTotal = old.burnTotal;
        fs.cookTime = old.cookTime;
        fs.cookTotal = old.cookTotal;

        data.moduleStates().put(session.moduleId(), FurnaceStateCodec.encode(fs));
        plugin.repo().saveBackpack(data);
        plugin.sessions().refreshLinkedBackpacksThrottled(session.backpackId(), data);
        plugin.sessions().onRelatedInventoryClose(player, session.backpackId());
    }
}
