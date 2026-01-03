package io.github.tootertutor.ModularPacks.modules;

import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.BlastingRecipe;
import org.bukkit.inventory.CookingRecipe;
import org.bukkit.inventory.FurnaceRecipe;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.SmokingRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.inventory.view.FurnaceView;

import io.github.tootertutor.ModularPacks.ModularPacksPlugin;
import io.github.tootertutor.ModularPacks.config.ScreenType;
import io.github.tootertutor.ModularPacks.data.BackpackData;
import io.github.tootertutor.ModularPacks.data.ItemStackCodec;

final class FurnaceEngine {

    private final ModularPacksPlugin plugin;

    FurnaceEngine(ModularPacksPlugin plugin) {
        this.plugin = plugin;
    }

    void tickFurnaceScreen(
            Player player,
            UUID backpackId,
            String backpackType,
            UUID moduleId,
            ScreenType screenType,
            Inventory inv,
            int dtTicks) {
        BackpackData data = plugin.repo().loadOrCreate(backpackId, backpackType);

        byte[] bytes = data.moduleStates().get(moduleId);
        FurnaceStateCodec.State stored = FurnaceStateCodec.decode(bytes);

        // Merge: use CURRENT UI items, keep STORED progress
        FurnaceStateCodec.State s = new FurnaceStateCodec.State();
        s.input = inv.getItem(0);
        s.fuel = inv.getItem(1);
        s.output = inv.getItem(2);

        s.burnTime = stored.burnTime;
        s.burnTotal = stored.burnTotal;
        s.cookTime = stored.cookTime;
        s.cookTotal = stored.cookTotal;

        boolean changed = tickFurnaceLike(screenType, s, dtTicks);
        if (!changed)
            return;

        // Push back to UI
        inv.setItem(0, s.input);
        inv.setItem(1, s.fuel);
        inv.setItem(2, s.output);

        // Progress bars (client-side) for furnace-like screens.
        var view = player.getOpenInventory();
        if (view != null && view.getTopInventory() == inv) {
            if (view instanceof FurnaceView fv) {
                fv.setBurnTime(s.burnTime, s.burnTotal);
                fv.setCookTime(s.cookTime, s.cookTotal);
            }
        }
        // Send data packets as a best-effort fallback for older clients / edge cases.
        ContainerDataSync.trySyncFurnaceLike(player, s.burnTime, s.burnTotal, s.cookTime, s.cookTotal);

        // Persist
        data.moduleStates().put(moduleId, FurnaceStateCodec.encode(s));
        plugin.repo().saveBackpack(data);
    }

    boolean tickInstalledFurnaces(BackpackData data, Set<UUID> openModuleIds, int dtTicks) {
        boolean changedAny = false;
        for (UUID moduleId : data.installedModules().values()) {
            if (moduleId == null)
                continue;
            if (openModuleIds != null && openModuleIds.contains(moduleId))
                continue;

            ScreenType st = resolveInstalledModuleScreenType(data, moduleId);
            if (st != ScreenType.SMELTING && st != ScreenType.BLASTING && st != ScreenType.SMOKING)
                continue;

            byte[] stateBytes = data.moduleStates().get(moduleId);
            FurnaceStateCodec.State s = FurnaceStateCodec.decode(stateBytes);
            boolean changed = tickFurnaceLike(st, s, dtTicks);
            if (!changed)
                continue;

            data.moduleStates().put(moduleId, FurnaceStateCodec.encode(s));
            changedAny = true;
        }
        return changedAny;
    }

    private ScreenType resolveInstalledModuleScreenType(BackpackData data, UUID moduleId) {
        byte[] snap = data.installedSnapshots().get(moduleId);
        if (snap == null)
            return ScreenType.NONE;

        ItemStack[] arr;
        try {
            arr = ItemStackCodec.fromBytes(snap);
        } catch (Exception ex) {
            return ScreenType.NONE;
        }
        if (arr.length == 0 || arr[0] == null)
            return ScreenType.NONE;

        ItemMeta meta = arr[0].getItemMeta();
        if (meta == null)
            return ScreenType.NONE;

        var pdc = meta.getPersistentDataContainer();
        String upgradeId = pdc.get(plugin.keys().MODULE_TYPE, PersistentDataType.STRING);
        if (upgradeId == null)
            return ScreenType.NONE;

        Byte enabled = pdc.get(plugin.keys().MODULE_ENABLED, PersistentDataType.BYTE);
        if (enabled != null && enabled == 0)
            return ScreenType.NONE;

        var def = plugin.cfg().findUpgrade(upgradeId);
        if (def == null || !def.enabled())
            return ScreenType.NONE;

        return def.screenType();
    }

    private boolean tickFurnaceLike(ScreenType type, FurnaceStateCodec.State s, int dtTicks) {
        if (dtTicks <= 0)
            dtTicks = 1;

        boolean hasInput = s.input != null && !s.input.getType().isAir();
        boolean changed = false;

        if (!hasInput) {
            // Burn always decays if already lit, even if smelting can't progress.
            if (s.burnTime > 0) {
                int dec = Math.min(dtTicks, s.burnTime);
                s.burnTime -= dec;
                changed = true;
            }

            // cool down cookTime if no input
            if (s.cookTime > 0) {
                s.cookTime = Math.max(0, s.cookTime - 2 * dtTicks);
                changed = true;
            }
            if (s.burnTime <= 0 && (s.fuel == null || s.fuel.getType().isAir())) {
                if (s.burnTotal != 0) {
                    s.burnTotal = 0;
                    changed = true;
                }
            }
            return changed;
        }

        CookingRecipe<?> recipe = findCookingRecipe(type, s.input);
        if (recipe == null) {
            // Burn always decays if already lit, even if smelting can't progress.
            if (s.burnTime > 0) {
                int dec = Math.min(dtTicks, s.burnTime);
                s.burnTime -= dec;
                changed = true;
            }

            // input not valid; cool down
            if (s.cookTime > 0) {
                s.cookTime = Math.max(0, s.cookTime - 2 * dtTicks);
                changed = true;
            }
            if (s.burnTime <= 0 && (s.fuel == null || s.fuel.getType().isAir())) {
                if (s.burnTotal != 0) {
                    s.burnTotal = 0;
                    changed = true;
                }
            }
            return changed;
        }

        int total = recipe.getCookingTime();
        if (total <= 0)
            total = 200;
        if (s.cookTotal != total) {
            // recipe changed -> reset progress to prevent weird partial crafts
            s.cookTotal = total;
            s.cookTime = 0;
            changed = true;
        }

        ItemStack result = recipe.getResult();
        if (result == null || result.getType().isAir())
            return changed;

        int producedPerCraft = Math.max(1, result.getAmount());

        boolean canOutput = true;
        int outputSpace = 0;
        if (s.output == null || s.output.getType().isAir()) {
            outputSpace = result.getMaxStackSize();
        } else {
            if (!s.output.isSimilar(result)) {
                canOutput = false;
            } else {
                outputSpace = s.output.getMaxStackSize() - s.output.getAmount();
            }
        }
        if (outputSpace < producedPerCraft)
            canOutput = false;

        // If we can't output, don't consume new fuel and don't progress cooking; just
        // cool down.
        if (!canOutput) {
            // Burn always decays if already lit, even if smelting can't progress.
            if (s.burnTime > 0) {
                int dec = Math.min(dtTicks, s.burnTime);
                s.burnTime -= dec;
                changed = true;
            }

            if (s.cookTime > 0) {
                s.cookTime = Math.max(0, s.cookTime - 2 * dtTicks);
                changed = true;
            }
            if (s.burnTime <= 0 && (s.fuel == null || s.fuel.getType().isAir())) {
                if (s.burnTotal != 0) {
                    s.burnTotal = 0;
                    changed = true;
                }
            }
            return changed;
        }

        // Consume fuel ONLY when we need to light (burnTime <= 0).
        if (s.burnTime <= 0) {
            int fuelTicks = fuelTicks(s.fuel);
            if (fuelTicks > 0) {
                s.fuel = consumeOneFuel(s.fuel);
                s.burnTime = fuelTicks;
                s.burnTotal = fuelTicks;
                changed = true;
            }
        }

        // If not burning after trying to light, cool down.
        if (s.burnTime <= 0) {
            if (s.cookTime > 0) {
                s.cookTime = Math.max(0, s.cookTime - 2 * dtTicks);
                changed = true;
            }
            if (s.burnTime <= 0 && (s.fuel == null || s.fuel.getType().isAir())) {
                if (s.burnTotal != 0) {
                    s.burnTotal = 0;
                    changed = true;
                }
            }
            return changed;
        }

        int burnStep = Math.min(dtTicks, s.burnTime);
        if (burnStep > 0) {
            s.burnTime -= burnStep;
            changed = true;
        }
        if (burnStep > 0) {
            int newCookTime = s.cookTime + burnStep;
            boolean crafted = false;

            while (newCookTime >= s.cookTotal) {
                if (s.input == null || s.input.getType().isAir())
                    break;

                // Re-check output space for each craft (important when producedPerCraft > 1).
                if (s.output == null || s.output.getType().isAir()) {
                    outputSpace = result.getMaxStackSize();
                } else {
                    outputSpace = s.output.getMaxStackSize() - s.output.getAmount();
                }
                if (outputSpace < producedPerCraft)
                    break;

                // produce output
                if (s.output == null || s.output.getType().isAir()) {
                    s.output = result.clone();
                } else {
                    s.output.setAmount(s.output.getAmount() + producedPerCraft);
                }

                // consume one input (after output is produced so single-item stacks still
                // craft)
                s.input = BackpackInventoryUtil.decrementOne(s.input);

                crafted = true;
                newCookTime -= s.cookTotal;

                if (s.input == null || s.input.getType().isAir()) {
                    newCookTime = 0;
                    break;
                }
            }

            if (crafted || newCookTime != s.cookTime) {
                s.cookTime = newCookTime;
                changed = true;
            }
        }

        if (s.burnTime <= 0 && (s.fuel == null || s.fuel.getType().isAir())) {
            if (s.burnTotal != 0) {
                s.burnTotal = 0;
                changed = true;
            }
        }

        return changed;
    }

    private CookingRecipe<?> findCookingRecipe(ScreenType type, ItemStack input) {
        Iterator<Recipe> it = Bukkit.recipeIterator();
        while (it.hasNext()) {
            Recipe r = it.next();
            if (!(r instanceof CookingRecipe<?> cr))
                continue;

            boolean ok = (type == ScreenType.SMELTING && r instanceof FurnaceRecipe)
                    || (type == ScreenType.BLASTING && r instanceof BlastingRecipe)
                    || (type == ScreenType.SMOKING && r instanceof SmokingRecipe);

            if (!ok)
                continue;

            if (cr.getInputChoice() != null && cr.getInputChoice().test(input)) {
                return cr;
            }
        }
        return null;
    }

    private int fuelTicks(ItemStack fuel) {
        if (fuel == null || fuel.getType().isAir())
            return 0;

        int nms = FuelTimeLookup.tryGetBurnTimeTicks(fuel);
        if (nms > 0)
            return nms;

        // Fallback (should only hit if reflection breaks).
        Material m = fuel.getType();
        if (m == Material.COAL || m == Material.CHARCOAL)
            return 1600;
        if (m == Material.COAL_BLOCK)
            return 16000;
        if (m == Material.BLAZE_ROD)
            return 2400;
        if (m == Material.LAVA_BUCKET)
            return 20000;

        String name = m.name();
        if (name.endsWith("_PLANKS"))
            return 300;
        if (name.endsWith("_LOG") || name.endsWith("_WOOD"))
            return 300;

        return 0;
    }

    private ItemStack consumeOneFuel(ItemStack fuel) {
        if (fuel == null || fuel.getType().isAir())
            return null;

        // Handle container fuel items (lava bucket -> bucket).
        Material remaining = fuel.getType().getCraftingRemainingItem();
        if (remaining != null && !remaining.isAir()) {
            return new ItemStack(remaining, 1);
        }

        return BackpackInventoryUtil.decrementOne(fuel);
    }

    /**
     * Best-effort fuel-time lookup using CraftBukkit + NMS via reflection (so this
     * compiles against paper-api).
     */
    private static final class FuelTimeLookup {
        private static volatile boolean initialized;
        private static volatile boolean available;
        private static Method craftAsNmsCopy;
        private static Method nmsGetItem;
        private static java.util.Map<Object, Integer> fuelMap;

        private FuelTimeLookup() {
        }

        static int tryGetBurnTimeTicks(ItemStack bukkitFuel) {
            if (bukkitFuel == null || bukkitFuel.getType().isAir())
                return 0;

            ensureInit();
            if (!available)
                return 0;

            try {
                Object nmsStack = craftAsNmsCopy.invoke(null, bukkitFuel);
                Object nmsItem = nmsGetItem.invoke(nmsStack);
                Integer ticks = fuelMap.get(nmsItem);
                return ticks == null ? 0 : Math.max(0, ticks);
            } catch (ReflectiveOperationException ex) {
                return 0;
            }
        }

        @SuppressWarnings("unchecked")
        private static void ensureInit() {
            if (initialized)
                return;
            synchronized (FuelTimeLookup.class) {
                if (initialized)
                    return;
                initialized = true;
                try {
                    String craftPackage = Bukkit.getServer().getClass().getPackage().getName();
                    Class<?> craftItemStack = Class.forName(craftPackage + ".inventory.CraftItemStack");
                    craftAsNmsCopy = craftItemStack.getMethod("asNMSCopy", ItemStack.class);

                    Class<?> nmsItemStack = Class.forName("net.minecraft.world.item.ItemStack");
                    nmsGetItem = nmsItemStack.getMethod("getItem");

                    Class<?> abstractFurnace = Class.forName(
                            "net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity");
                    java.lang.reflect.Method getFuel = null;
                    for (var m : abstractFurnace.getDeclaredMethods()) {
                        if (!java.lang.reflect.Modifier.isStatic(m.getModifiers()))
                            continue;
                        if (m.getParameterCount() != 0)
                            continue;
                        if (!java.util.Map.class.isAssignableFrom(m.getReturnType()))
                            continue;
                        if (m.getName().toLowerCase().contains("fuel")) {
                            getFuel = m;
                            break;
                        }
                    }
                    if (getFuel == null) {
                        for (var m : abstractFurnace.getDeclaredMethods()) {
                            if (!java.lang.reflect.Modifier.isStatic(m.getModifiers()))
                                continue;
                            if (m.getParameterCount() != 0)
                                continue;
                            if (!java.util.Map.class.isAssignableFrom(m.getReturnType()))
                                continue;
                            getFuel = m;
                            break;
                        }
                    }
                    if (getFuel == null) {
                        available = false;
                        return;
                    }
                    getFuel.setAccessible(true);
                    fuelMap = (java.util.Map<Object, Integer>) getFuel.invoke(null);
                    available = fuelMap != null;
                } catch (ReflectiveOperationException ex) {
                    available = false;
                }
            }
        }
    }

    /**
     * Sends container data updates for furnace-like menus, including blast furnace
     * and smoker.
     * Uses reflection so this can compile against paper-api.
     */
    private static final class ContainerDataSync {
        private static volatile boolean initialized;
        private static volatile boolean available;

        private static Method craftPlayerGetHandle;
        private static java.lang.reflect.Field serverPlayerConnectionField;
        private static java.lang.reflect.Field serverPlayerContainerMenuField;
        private static java.lang.reflect.Field menuContainerIdField;
        private static Method connectionSendMethod;
        private static java.lang.reflect.Constructor<?> setDataPacketCtor;

        private ContainerDataSync() {
        }

        static void trySyncFurnaceLike(Player player, int burnTime, int burnTotal, int cookTime, int cookTotal) {
            if (player == null)
                return;

            ensureInit();
            if (!available)
                return;

            try {
                Object handle = craftPlayerGetHandle.invoke(player);
                if (handle == null)
                    return;

                Object menu = serverPlayerContainerMenuField.get(handle);
                if (menu == null)
                    return;

                int containerId = menuContainerIdField.getInt(menu);

                Object connection = serverPlayerConnectionField.get(handle);
                if (connection == null)
                    return;

                // AbstractFurnaceMenu data indices:
                // 0 = burnTime (litTime), 1 = burnTotal (litDuration), 2 = cookTime, 3 =
                // cookTotal
                send(connection, newSetDataPacket(containerId, 0, burnTime));
                send(connection, newSetDataPacket(containerId, 1, burnTotal));
                send(connection, newSetDataPacket(containerId, 2, cookTime));
                send(connection, newSetDataPacket(containerId, 3, cookTotal));
            } catch (ReflectiveOperationException ignored) {
            }
        }

        private static Object newSetDataPacket(int containerId, int id, int value) throws ReflectiveOperationException {
            return setDataPacketCtor.newInstance(containerId, id, value);
        }

        private static void send(Object connection, Object packet) throws ReflectiveOperationException {
            connectionSendMethod.invoke(connection, packet);
        }

        private static void ensureInit() {
            if (initialized)
                return;
            synchronized (ContainerDataSync.class) {
                if (initialized)
                    return;
                initialized = true;

                try {
                    String craftPackage = Bukkit.getServer().getClass().getPackage().getName();
                    Class<?> craftPlayer = Class.forName(craftPackage + ".entity.CraftPlayer");
                    craftPlayerGetHandle = craftPlayer.getMethod("getHandle");

                    Class<?> serverPlayer = Class.forName("net.minecraft.server.level.ServerPlayer");
                    Class<?> abstractMenu = Class.forName("net.minecraft.world.inventory.AbstractContainerMenu");
                    Class<?> connectionClazz = Class
                            .forName("net.minecraft.server.network.ServerGamePacketListenerImpl");
                    Class<?> packetInterface = Class.forName("net.minecraft.network.protocol.Packet");

                    serverPlayerConnectionField = findField(serverPlayer, "connection", connectionClazz);
                    serverPlayerContainerMenuField = findField(serverPlayer, "containerMenu", abstractMenu);
                    menuContainerIdField = findIntField(abstractMenu, "containerId");

                    connectionSendMethod = null;
                    for (Method m : connectionClazz.getMethods()) {
                        if (!m.getName().equals("send"))
                            continue;
                        if (m.getParameterCount() != 1)
                            continue;
                        if (!packetInterface.isAssignableFrom(m.getParameterTypes()[0]))
                            continue;
                        connectionSendMethod = m;
                        break;
                    }
                    if (connectionSendMethod == null) {
                        available = false;
                        return;
                    }

                    Class<?> packetClazz = Class
                            .forName("net.minecraft.network.protocol.game.ClientboundContainerSetDataPacket");
                    setDataPacketCtor = null;
                    for (var c : packetClazz.getConstructors()) {
                        var params = c.getParameterTypes();
                        if (params.length == 3 && params[0] == int.class && params[1] == int.class
                                && params[2] == int.class) {
                            setDataPacketCtor = c;
                            break;
                        }
                    }

                    available = serverPlayerConnectionField != null
                            && serverPlayerContainerMenuField != null
                            && menuContainerIdField != null
                            && setDataPacketCtor != null;
                } catch (ReflectiveOperationException ex) {
                    available = false;
                }
            }
        }

        private static java.lang.reflect.Field findField(Class<?> owner, String preferredName, Class<?> type)
                throws ReflectiveOperationException {
            try {
                var f = owner.getDeclaredField(preferredName);
                if (type.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    return f;
                }
            } catch (NoSuchFieldException ignored) {
            }

            for (var f : owner.getDeclaredFields()) {
                if (!type.isAssignableFrom(f.getType()))
                    continue;
                f.setAccessible(true);
                return f;
            }

            throw new NoSuchFieldException(preferredName);
        }

        private static java.lang.reflect.Field findIntField(Class<?> owner, String preferredName)
                throws ReflectiveOperationException {
            try {
                var f = owner.getDeclaredField(preferredName);
                if (f.getType() == int.class) {
                    f.setAccessible(true);
                    return f;
                }
            } catch (NoSuchFieldException ignored) {
            }

            for (var f : owner.getDeclaredFields()) {
                if (f.getType() != int.class)
                    continue;
                f.setAccessible(true);
                return f;
            }

            throw new NoSuchFieldException(preferredName);
        }
    }
}
