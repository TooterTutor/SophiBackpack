package io.github.tootertutor.ModularPacks.modules;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.BlastingRecipe;
import org.bukkit.inventory.CookingRecipe;
import org.bukkit.inventory.FurnaceRecipe;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.SmokingRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.FoodComponent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionEffectTypeCategory;
import org.bukkit.scheduler.BukkitTask;

import io.github.tootertutor.ModularPacks.ModularPacksPlugin;
import io.github.tootertutor.ModularPacks.config.ScreenType;
import io.github.tootertutor.ModularPacks.data.BackpackData;
import io.github.tootertutor.ModularPacks.data.ItemStackCodec;
import io.github.tootertutor.ModularPacks.data.SQLiteBackpackRepository.VoidedItemRecord;
import io.github.tootertutor.ModularPacks.gui.BackpackMenuHolder;
import io.github.tootertutor.ModularPacks.gui.ModuleScreenHolder;
import io.github.tootertutor.ModularPacks.item.Keys;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.Consumable;
import io.papermc.paper.datacomponent.item.FoodProperties;
import io.papermc.paper.datacomponent.item.SuspiciousStewEffects;
import io.papermc.paper.datacomponent.item.consumable.ConsumeEffect;
import io.papermc.paper.potion.SuspiciousEffectEntry;

/**
 * Engine ticks ONLY currently-open module screens (Option 2).
 * This keeps behavior safe and prevents dupes/desync.
 */
public final class ModuleEngineService {

    private static final long ENGINE_PERIOD_TICKS = 10L;
    private static final int ENGINE_DT_TICKS = 10;

    private final ModularPacksPlugin plugin;
    private BukkitTask task;

    private final Map<UUID, Integer> lastFedTickByPlayer = new HashMap<>();

    public ModuleEngineService(ModularPacksPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        if (task != null)
            return;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tickOpenScreens, ENGINE_PERIOD_TICKS,
                ENGINE_PERIOD_TICKS);
    }

    public void stop() {
        if (task != null)
            task.cancel();
        task = null;
    }

    private void tickOpenScreens() {
        Set<UUID> openModuleIds = new HashSet<>();
        Set<UUID> openBackpackIds = new HashSet<>();

        for (Player player : Bukkit.getOnlinePlayers()) {
            Inventory top = player.getOpenInventory().getTopInventory();
            if (top.getHolder() instanceof BackpackMenuHolder bmh) {
                openBackpackIds.add(bmh.backpackId());
            }
            if (!(top.getHolder() instanceof ModuleScreenHolder msh))
                continue;

            openModuleIds.add(msh.moduleId());

            ScreenType st = msh.screenType();
            if (st == ScreenType.SMELTING || st == ScreenType.BLASTING || st == ScreenType.SMOKING) {
                tickFurnaceScreen(player, msh, top);
            }

            // Later: add other engines here (stonecutter, smithing, etc.)
        }

        tickCarriedBackpacks(openModuleIds, openBackpackIds);
    }

    private void tickFurnaceScreen(Player player, ModuleScreenHolder msh, Inventory inv) {
        BackpackData data = plugin.repo().loadOrCreate(msh.backpackId(), msh.backpackType());

        byte[] bytes = data.moduleStates().get(msh.moduleId());
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

        boolean changed = tickFurnaceLike(msh.screenType(), s, ENGINE_DT_TICKS);
        if (!changed)
            return;

        // Push back to UI
        inv.setItem(0, s.input);
        inv.setItem(1, s.fuel);
        inv.setItem(2, s.output);

        // Progress bars (client-side) for furnace-like screens.
        var view = player.getOpenInventory();
        if (view != null && view.getTopInventory() == inv) {
            view.setProperty(InventoryView.Property.BURN_TIME, s.burnTime);
            view.setProperty(InventoryView.Property.TICKS_FOR_CURRENT_FUEL, s.burnTotal);
            view.setProperty(InventoryView.Property.COOK_TIME, s.cookTime);
            view.setProperty(InventoryView.Property.TICKS_FOR_CURRENT_SMELTING, s.cookTotal);
        }
        // Bukkit's property API only applies to InventoryType.FURNACE; blast furnace &
        // smoker need direct container-data packets.
        ContainerDataSync.trySyncFurnaceLike(player, s.burnTime, s.burnTotal, s.cookTime, s.cookTotal);

        // Persist
        data.moduleStates().put(msh.moduleId(), FurnaceStateCodec.encode(s));
        plugin.repo().saveBackpack(data);
    }

    private void tickCarriedBackpacks(Set<UUID> openModuleIds, Set<UUID> openBackpackIds) {
        Keys keys = plugin.keys();

        for (Player player : Bukkit.getOnlinePlayers()) {
            Set<UUID> processedBackpacks = new HashSet<>();
            ItemStack[] contents = player.getInventory().getContents();
            if (contents == null || contents.length == 0)
                continue;

            for (ItemStack item : contents) {
                UUID backpackId = readBackpackId(keys, item);
                if (backpackId == null)
                    continue;
                if (!processedBackpacks.add(backpackId))
                    continue;

                String backpackType = readBackpackType(keys, item);
                if (backpackType == null || backpackType.isBlank())
                    continue;

                tickBackpack(player, backpackId, backpackType, openModuleIds, openBackpackIds);
            }
        }
    }

    private void tickBackpack(
            Player player,
            UUID backpackId,
            String backpackType,
            Set<UUID> openModuleIds,
            Set<UUID> openBackpackIds) {

        var typeDef = plugin.cfg().findType(backpackType);
        if (typeDef == null)
            return;

        BackpackData data = plugin.repo().loadOrCreate(backpackId, backpackType);

        boolean allowContentsMutations = openBackpackIds == null || !openBackpackIds.contains(backpackId);

        boolean changedAny = false;

        // Passive modules that mutate backpack contents (skip while that backpack GUI
        // is open)
        if (allowContentsMutations) {
            ItemStack[] logical = ensureLogicalContentsSize(data, typeDef.rows() * 9);

            UUID voidId = findInstalledModuleId(data, "Void");
            Set<Material> voidWhitelist = (voidId == null) ? Set.of() : readWhitelistFromState(data, voidId);

            UUID feedingId = findInstalledModuleId(data, "Feeding");
            if (feedingId != null) {
                changedAny |= applyFeeding(player, logical, data, feedingId);
            }

            UUID magnetId = findInstalledModuleId(data, "Magnet");
            if (magnetId != null) {
                changedAny |= applyMagnet(player, logical, readWhitelistFromState(data, magnetId),
                        backpackId, backpackType, voidId, voidWhitelist);
            }

            if (changedAny) {
                data.contentsBytes(ItemStackCodec.toBytes(logical));
            }
        }

        // Ticking module states (furnace-like) is safe even if backpack GUI is open.
        changedAny |= tickInstalledFurnaces(data, openModuleIds);

        if (changedAny) {
            plugin.repo().saveBackpack(data);
        }
    }

    private boolean tickInstalledFurnaces(BackpackData data, Set<UUID> openModuleIds) {
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
            boolean changed = tickFurnaceLike(st, s, ENGINE_DT_TICKS);
            if (!changed)
                continue;

            data.moduleStates().put(moduleId, FurnaceStateCodec.encode(s));
            changedAny = true;
        }
        return changedAny;
    }

    private ItemStack[] ensureLogicalContentsSize(BackpackData data, int size) {
        if (size < 0)
            size = 0;
        ItemStack[] logical = ItemStackCodec.fromBytes(data.contentsBytes());
        if (logical.length != size) {
            ItemStack[] resized = new ItemStack[size];
            System.arraycopy(logical, 0, resized, 0, Math.min(logical.length, size));
            logical = resized;
        }
        return logical;
    }

    private UUID findInstalledModuleId(BackpackData data, String targetModuleType) {
        if (data == null || targetModuleType == null)
            return null;

        for (UUID moduleId : data.installedModules().values()) {
            if (moduleId == null)
                continue;
            ItemStack moduleItem = resolveModuleSnapshotItem(data, moduleId);
            if (moduleItem == null || !moduleItem.hasItemMeta())
                continue;

            ItemMeta meta = moduleItem.getItemMeta();
            if (meta == null)
                continue;

            String moduleType = meta.getPersistentDataContainer().get(plugin.keys().MODULE_TYPE,
                    PersistentDataType.STRING);
            if (moduleType == null || !moduleType.equalsIgnoreCase(targetModuleType))
                continue;

            var def = plugin.cfg().findUpgrade(moduleType);
            if (def == null || !def.enabled())
                continue;

            Byte enabled = meta.getPersistentDataContainer().get(plugin.keys().MODULE_ENABLED, PersistentDataType.BYTE);
            if (enabled != null && enabled == 0)
                continue;

            return moduleId;
        }
        return null;
    }

    private ItemStack resolveModuleSnapshotItem(BackpackData data, UUID moduleId) {
        byte[] snap = data.installedSnapshots().get(moduleId);
        if (snap == null)
            return null;
        ItemStack[] arr;
        try {
            arr = ItemStackCodec.fromBytes(snap);
        } catch (Exception ignored) {
            return null;
        }
        if (arr.length == 0)
            return null;
        return arr[0];
    }

    private Set<Material> readWhitelistFromState(BackpackData data, UUID moduleId) {
        if (data == null || moduleId == null)
            return Set.of();
        byte[] bytes = data.moduleStates().get(moduleId);
        if (bytes == null || bytes.length == 0)
            return Set.of();

        ItemStack[] arr = ItemStackCodec.fromBytes(bytes);
        if (arr == null || arr.length == 0)
            return Set.of();

        Set<Material> out = new LinkedHashSet<>();
        for (ItemStack it : arr) {
            if (it == null || it.getType().isAir())
                continue;
            out.add(it.getType());
        }
        return out;
    }

    private List<Material> readWhitelistOrderedFromState(BackpackData data, UUID moduleId) {
        if (data == null || moduleId == null)
            return java.util.Collections.emptyList();
        byte[] bytes = data.moduleStates().get(moduleId);
        if (bytes == null || bytes.length == 0)
            return java.util.Collections.emptyList();

        ItemStack[] arr = ItemStackCodec.fromBytes(bytes);
        if (arr == null || arr.length == 0)
            return java.util.Collections.emptyList();

        java.util.LinkedHashSet<Material> seen = new java.util.LinkedHashSet<>();
        for (ItemStack it : arr) {
            if (it == null || it.getType().isAir())
                continue;
            seen.add(it.getType());
        }
        return new java.util.ArrayList<>(seen);
    }

    private boolean applyFeeding(Player player, ItemStack[] contents, BackpackData data, UUID moduleId) {
        if (player == null || contents == null || data == null || moduleId == null)
            return false;

        int minFood = Math.max(0, Math.min(20, plugin.getConfig().getInt("Upgrades.Feeding.MinFoodLevel", 18)));
        int foodLevel = player.getFoodLevel();
        if (foodLevel >= minFood)
            return false;

        int now = Bukkit.getCurrentTick();
        int lastFed = lastFedTickByPlayer.getOrDefault(player.getUniqueId(), -99999);
        int cooldown = Math.max(0, plugin.getConfig().getInt("Upgrades.Feeding.CooldownTicks", 20));
        if (now - lastFed < cooldown)
            return false;

        FeedingSettings settings = readFeedingSettings(data, moduleId);
        List<Material> orderedWhitelist = readWhitelistOrderedFromState(data, moduleId);

        // If whitelist-order is selected but no whitelist is configured, behave like
        // best-candidate.
        if (settings.mode == FeedingSelectionMode.WHITELIST_ORDER && !orderedWhitelist.isEmpty()) {
            int chosen = chooseFeedingByWhitelistOrder(contents, orderedWhitelist, settings.preference, minFood,
                    foodLevel);
            if (chosen < 0)
                return false;
            return consumeFeeding(player, contents, chosen, minFood, foodLevel, settings, now);
        }

        java.util.Set<Material> whitelistSet = orderedWhitelist.isEmpty()
                ? java.util.Collections.emptySet()
                : new java.util.HashSet<>(orderedWhitelist);

        CandidatePick good = new CandidatePick();
        CandidatePick bad = new CandidatePick();

        for (int i = 0; i < contents.length; i++) {
            ItemStack it = contents[i];
            if (it == null || it.getType().isAir())
                continue;
            if (!it.getType().isEdible())
                continue;
            if (!whitelistSet.isEmpty() && !whitelistSet.contains(it.getType()))
                continue;

            FoodValues v = FoodValues.lookup(it);
            int nutrition = v.nutrition();
            if (nutrition <= 0)
                continue;

            float satPoints = saturationPoints(nutrition, v.saturation());
            int overshoot = (foodLevel + nutrition) - minFood;

            boolean harmful = settings.preference == FeedingPreference.EFFECTS && hasHarmfulFoodEffects(it);
            CandidatePick pick = harmful ? bad : good;
            pick.consider(i, nutrition, satPoints, overshoot, settings.preference);
        }

        int chosen;
        if (settings.preference == FeedingPreference.EFFECTS) {
            chosen = good.bestIndex >= 0 ? good.bestIndex : good.fallbackIndex;
            if (chosen < 0) {
                chosen = bad.bestIndex >= 0 ? bad.bestIndex : bad.fallbackIndex;
            }
        } else {
            chosen = good.bestIndex >= 0 ? good.bestIndex : good.fallbackIndex;
        }

        if (chosen < 0)
            return false;

        return consumeFeeding(player, contents, chosen, minFood, foodLevel, settings, now);
    }

    private boolean consumeFeeding(
            Player player,
            ItemStack[] contents,
            int index,
            int minFood,
            int beforeFood,
            FeedingSettings settings,
            int now) {
        ItemStack it = contents[index];
        if (it == null || it.getType().isAir())
            return false;

        boolean debug = plugin.getConfig().getBoolean("Upgrades.Feeding.Debug", false);
        float beforeSat = player.getSaturation();

        // Consume one item
        ItemStack after = decrementOne(it);
        contents[index] = after;

        // Handle remaining container item (e.g. soup -> bowl)
        Material rem = it.getType().getCraftingRemainingItem();
        if (rem != null && !rem.isAir()) {
            ItemStack leftover = insertIntoContents(contents, new ItemStack(rem, 1));
            if (leftover != null && !leftover.getType().isAir() && leftover.getAmount() > 0) {
                var notFit = player.getInventory().addItem(leftover);
                if (!notFit.isEmpty()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), leftover);
                }
            }
        }

        applyFoodValues(player, it);
        int effectsApplied = applyFoodEffects(player, it);

        if (debug) {
            FoodValues v = FoodValues.lookup(it);
            int nutrition = v.nutrition();
            float satPoints = saturationPoints(nutrition, v.saturation());
            plugin.getLogger().info(String.format(
                    "[Feeding] %s ate %s: food %d->%d, sat %.2f->%.2f (nutrition=%d, sat=%.3f, satPts=%.2f, harmful=%s, effects=%d, mode=%s, pref=%s, minFood=%d)",
                    player.getName(),
                    it.getType().name(),
                    beforeFood,
                    player.getFoodLevel(),
                    beforeSat,
                    player.getSaturation(),
                    nutrition,
                    v.saturation(),
                    satPoints,
                    hasHarmfulFoodEffects(it),
                    effectsApplied,
                    settings.mode,
                    settings.preference,
                    minFood));
        }

        lastFedTickByPlayer.put(player.getUniqueId(), now);
        return true;
    }

    private int chooseFeedingByWhitelistOrder(
            ItemStack[] contents,
            List<Material> orderedWhitelist,
            FeedingPreference preference,
            int minFood,
            int foodLevel) {
        if (contents == null || orderedWhitelist == null || orderedWhitelist.isEmpty())
            return -1;

        for (Material mat : orderedWhitelist) {
            if (mat == null || mat.isAir())
                continue;

            CandidatePick good = new CandidatePick();
            CandidatePick bad = new CandidatePick();

            for (int i = 0; i < contents.length; i++) {
                ItemStack it = contents[i];
                if (it == null || it.getType().isAir())
                    continue;
                if (it.getType() != mat)
                    continue;
                if (!it.getType().isEdible())
                    continue;

                FoodValues v = FoodValues.lookup(it);
                int nutrition = v.nutrition();
                if (nutrition <= 0)
                    continue;

                float satPoints = saturationPoints(nutrition, v.saturation());
                int overshoot = (foodLevel + nutrition) - minFood;

                boolean harmful = preference == FeedingPreference.EFFECTS && hasHarmfulFoodEffects(it);
                CandidatePick pick = harmful ? bad : good;
                pick.consider(i, nutrition, satPoints, overshoot, preference);
            }

            if (preference == FeedingPreference.EFFECTS) {
                int chosen = good.bestIndex >= 0 ? good.bestIndex : good.fallbackIndex;
                if (chosen >= 0)
                    return chosen;
                chosen = bad.bestIndex >= 0 ? bad.bestIndex : bad.fallbackIndex;
                if (chosen >= 0)
                    return chosen;
                continue;
            }

            int chosen = good.bestIndex >= 0 ? good.bestIndex : good.fallbackIndex;
            if (chosen >= 0)
                return chosen;
        }

        return -1;
    }

    private FeedingSettings readFeedingSettings(BackpackData data, UUID moduleId) {
        FeedingSelectionMode defaultMode = FeedingSelectionMode
                .parse(plugin.getConfig().getString("Upgrades.Feeding.SelectionMode", "BestCandidate"));
        FeedingPreference defaultPref = FeedingPreference
                .parse(plugin.getConfig().getString("Upgrades.Feeding.Preference", "Nutrition"));

        ItemStack snap = resolveModuleSnapshotItem(data, moduleId);
        if (snap == null || !snap.hasItemMeta()) {
            return new FeedingSettings(defaultMode, defaultPref);
        }

        ItemMeta meta = snap.getItemMeta();
        if (meta == null) {
            return new FeedingSettings(defaultMode, defaultPref);
        }

        var pdc = meta.getPersistentDataContainer();
        Keys keys = plugin.keys();

        FeedingSelectionMode mode = FeedingSelectionMode
                .parse(pdc.get(keys.MODULE_FEEDING_SELECTION_MODE, PersistentDataType.STRING));
        FeedingPreference pref = FeedingPreference
                .parse(pdc.get(keys.MODULE_FEEDING_PREFERENCE, PersistentDataType.STRING));

        if (mode == null)
            mode = defaultMode;
        if (pref == null)
            pref = defaultPref;

        return new FeedingSettings(mode, pref);
    }

    private boolean hasHarmfulFoodEffects(ItemStack foodItem) {
        if (foodItem == null || foodItem.getType().isAir())
            return false;

        try {
            Consumable consumable = foodItem.getData(DataComponentTypes.CONSUMABLE);
            if (consumable != null) {
                for (ConsumeEffect effect : consumable.consumeEffects()) {
                    if (!(effect instanceof ConsumeEffect.ApplyStatusEffects ase))
                        continue;
                    float prob = ase.probability();
                    if (prob <= 0.0f)
                        continue;

                    for (Object o : ase.effects()) {
                        if (!(o instanceof PotionEffect pe))
                            continue;
                        PotionEffectType type = pe.getType();
                        if (type != null && type.getCategory() == PotionEffectTypeCategory.HARMFUL) {
                            return true;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        try {
            SuspiciousStewEffects stew = foodItem.getData(DataComponentTypes.SUSPICIOUS_STEW_EFFECTS);
            if (stew != null) {
                for (SuspiciousEffectEntry e : stew.effects()) {
                    PotionEffectType type = e.effect();
                    if (type != null && type.getCategory() == PotionEffectTypeCategory.HARMFUL) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        return false;
    }

    private static final class CandidatePick {
        int bestIndex = -1;
        int bestOvershoot = Integer.MAX_VALUE;
        int bestNutrition = -1;
        float bestSatPoints = -1.0f;

        int fallbackIndex = -1;
        int fallbackNutrition = -1;
        float fallbackSatPoints = -1.0f;

        void consider(int index, int nutrition, float satPoints, int overshoot, FeedingPreference preference) {
            if (overshoot >= 0) {
                if (overshoot < bestOvershoot
                        || (overshoot == bestOvershoot && isBetterTie(nutrition, satPoints, preference))) {
                    bestIndex = index;
                    bestOvershoot = overshoot;
                    bestNutrition = nutrition;
                    bestSatPoints = satPoints;
                }
                return;
            }

            if (nutrition > fallbackNutrition || (nutrition == fallbackNutrition && satPoints > fallbackSatPoints)) {
                fallbackIndex = index;
                fallbackNutrition = nutrition;
                fallbackSatPoints = satPoints;
            }
        }

        private boolean isBetterTie(int nutrition, float satPoints, FeedingPreference preference) {
            if (preference == FeedingPreference.NUTRITION) {
                if (nutrition != bestNutrition)
                    return nutrition > bestNutrition;
                return satPoints > bestSatPoints;
            }
            if (satPoints != bestSatPoints)
                return satPoints > bestSatPoints;
            return nutrition > bestNutrition;
        }
    }

    private enum FeedingSelectionMode {
        BEST_CANDIDATE,
        WHITELIST_ORDER;

        static FeedingSelectionMode parse(String raw) {
            if (raw == null)
                return BEST_CANDIDATE;
            String s = raw.trim().toUpperCase(java.util.Locale.ROOT);
            if (s.isEmpty())
                return BEST_CANDIDATE;
            return switch (s) {
                case "BEST", "BESTCANDIDATE", "BEST_CANDIDATE" -> BEST_CANDIDATE;
                case "WHITELIST", "WHITELISTORDER", "WHITELIST_ORDER", "PREFER_FIRST_IN_WHITELIST" -> WHITELIST_ORDER;
                default -> BEST_CANDIDATE;
            };
        }
    }

    private enum FeedingPreference {
        NUTRITION,
        EFFECTS;

        static FeedingPreference parse(String raw) {
            if (raw == null)
                return NUTRITION;
            String s = raw.trim().toUpperCase(java.util.Locale.ROOT);
            if (s.isEmpty())
                return NUTRITION;
            return switch (s) {
                case "EFFECT", "EFFECTS" -> EFFECTS;
                default -> NUTRITION;
            };
        }
    }

    private static final class FeedingSettings {
        final FeedingSelectionMode mode;
        final FeedingPreference preference;

        FeedingSettings(FeedingSelectionMode mode, FeedingPreference preference) {
            this.mode = (mode == null ? FeedingSelectionMode.BEST_CANDIDATE : mode);
            this.preference = (preference == null ? FeedingPreference.NUTRITION : preference);
        }
    }

    private boolean applyMagnet(
            Player player,
            ItemStack[] contents,
            Set<Material> whitelist,
            UUID backpackId,
            String backpackType,
            UUID voidModuleId,
            Set<Material> voidWhitelist) {
        if (player == null || contents == null || whitelist == null)
            return false;

        double range = plugin.getConfig().getDouble("Upgrades.Magnet.Range", 6.0);
        if (range <= 0.1)
            return false;
        int maxEntities = Math.max(1,
                Math.min(256, plugin.getConfig().getInt("Upgrades.Magnet.MaxItemsPerTick", 32)));

        boolean changed = false;
        int processed = 0;

        boolean voidActive = backpackId != null && voidModuleId != null && voidWhitelist != null && !voidWhitelist.isEmpty();

        for (Entity ent : player.getNearbyEntities(range, range, range)) {
            if (processed >= maxEntities)
                break;
            if (!(ent instanceof Item itemEnt))
                continue;
            if (itemEnt.getPickupDelay() > 0)
                continue;

            ItemStack stack = itemEnt.getItemStack();
            if (stack == null || stack.getType().isAir())
                continue;
            if (!whitelist.isEmpty() && !whitelist.contains(stack.getType()))
                continue;

            if (voidActive && voidWhitelist.contains(stack.getType()) && !isProtectedFromVoid(stack)) {
                boolean logged = tryLogVoidedItem(player, backpackId, backpackType, voidModuleId, stack, itemEnt.getLocation());
                if (logged) {
                    itemEnt.remove();
                    changed = true;
                    processed++;
                }
                // Only affects magnet pickups; do not fall through to insertion.
                continue;
            }

            ItemStack remainder = insertIntoContents(contents, stack.clone());
            if (remainder == null || remainder.getType().isAir() || remainder.getAmount() <= 0) {
                itemEnt.remove();
                changed = true;
                processed++;
                continue;
            }

            // Partial insert; update entity
            if (remainder.getAmount() != stack.getAmount()) {
                itemEnt.setItemStack(remainder);
                changed = true;
                processed++;
            }
        }

        return changed;
    }

    private boolean isProtectedFromVoid(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || !stack.hasItemMeta())
            return false;
        ItemMeta meta = stack.getItemMeta();
        if (meta == null)
            return false;
        var pdc = meta.getPersistentDataContainer();
        Keys keys = plugin.keys();
        return pdc.has(keys.BACKPACK_ID, PersistentDataType.STRING)
                || pdc.has(keys.MODULE_ID, PersistentDataType.STRING);
    }

    private boolean tryLogVoidedItem(
            Player player,
            UUID backpackId,
            String backpackType,
            UUID voidModuleId,
            ItemStack stack,
            Location loc) {
        if (player == null || backpackId == null || voidModuleId == null || stack == null || stack.getType().isAir())
            return false;

        byte[] bytes;
        try {
            bytes = ItemStackCodec.toBytes(new ItemStack[] { stack.clone() });
        } catch (Exception ex) {
            plugin.getLogger().warning("Failed to serialize voided item: " + ex.getMessage());
            return false;
        }

        String world = (loc == null || loc.getWorld() == null) ? null : loc.getWorld().getName();
        Double x = loc == null ? null : loc.getX();
        Double y = loc == null ? null : loc.getY();
        Double z = loc == null ? null : loc.getZ();

        try {
            long id = plugin.repo().logVoidedItem(new VoidedItemRecord(
                    null,
                    System.currentTimeMillis(),
                    player.getUniqueId().toString(),
                    player.getName(),
                    backpackId.toString(),
                    backpackType,
                    voidModuleId.toString(),
                    stack.getType().name(),
                    stack.getAmount(),
                    bytes,
                    world,
                    x,
                    y,
                    z,
                    null,
                    null,
                    null));
            return id > 0;
        } catch (Exception ex) {
            plugin.getLogger().warning("Failed to log voided item to DB: " + ex.getMessage());
            return false;
        }
    }

    private ItemStack insertIntoContents(ItemStack[] contents, ItemStack stack) {
        if (contents == null)
            return stack;
        if (stack == null || stack.getType().isAir())
            return stack;

        // Merge into existing stacks first
        for (int i = 0; i < contents.length; i++) {
            ItemStack cur = contents[i];
            if (cur == null || cur.getType().isAir())
                continue;
            if (!cur.isSimilar(stack))
                continue;
            int max = cur.getMaxStackSize();
            int space = max - cur.getAmount();
            if (space <= 0)
                continue;

            int move = Math.min(space, stack.getAmount());
            ItemStack merged = cur.clone();
            merged.setAmount(cur.getAmount() + move);
            contents[i] = merged;

            stack.setAmount(stack.getAmount() - move);
            if (stack.getAmount() <= 0)
                return null;
        }

        // Empty slots
        for (int i = 0; i < contents.length; i++) {
            ItemStack cur = contents[i];
            if (cur != null && !cur.getType().isAir())
                continue;

            int toPlace = Math.min(stack.getMaxStackSize(), stack.getAmount());
            ItemStack placed = stack.clone();
            placed.setAmount(toPlace);
            contents[i] = placed;

            stack.setAmount(stack.getAmount() - toPlace);
            if (stack.getAmount() <= 0)
                return null;
        }

        return stack;
    }

    private void applyFoodValues(Player player, ItemStack foodItem) {
        if (player == null || foodItem == null || foodItem.getType().isAir())
            return;

        FoodValues values = FoodValues.lookup(foodItem);
        int nutrition = values.nutrition();
        float saturationPoints = saturationPoints(nutrition, values.saturation());

        int newFood = Math.min(20, player.getFoodLevel() + nutrition);
        player.setFoodLevel(newFood);

        // Saturation is clamped to food level in vanilla.
        float newSat = Math.min(newFood, player.getSaturation() + saturationPoints);
        player.setSaturation(newSat);
    }

    private int applyFoodEffects(Player player, ItemStack foodItem) {
        if (player == null || foodItem == null || foodItem.getType().isAir())
            return 0;

        int applied = 0;

        // General consumable effects (covers things like Rotten Flesh, Golden Apples,
        // Chorus Fruit, etc.)
        try {
            Consumable consumable = foodItem.getData(DataComponentTypes.CONSUMABLE);
            if (consumable != null) {
                for (ConsumeEffect effect : consumable.consumeEffects()) {
                    if (effect instanceof ConsumeEffect.ApplyStatusEffects ase) {
                        float prob = ase.probability();
                        if (prob <= 0.0f)
                            continue;
                        if (prob < 1.0f && java.util.concurrent.ThreadLocalRandom.current().nextFloat() >= prob)
                            continue;

                        for (Object o : ase.effects()) {
                            if (o instanceof PotionEffect pe) {
                                player.addPotionEffect(pe);
                                applied++;
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        // Suspicious stew stores its effects in a separate component.
        try {
            SuspiciousStewEffects stew = foodItem.getData(DataComponentTypes.SUSPICIOUS_STEW_EFFECTS);
            if (stew != null) {
                for (SuspiciousEffectEntry e : stew.effects()) {
                    PotionEffectType type = e.effect();
                    if (type == null)
                        continue;
                    int duration = Math.max(0, e.duration());
                    if (duration <= 0)
                        continue;
                    player.addPotionEffect(new PotionEffect(type, duration, 0));
                    applied++;
                }
            }
        } catch (Throwable ignored) {
        }

        return applied;
    }

    private static float saturationPoints(int nutrition, float saturation) {
        if (nutrition <= 0)
            return 0.0f;

        float s = Math.max(0.0f, saturation);

        // Depending on the source, "saturation" may be:
        // - saturation modifier (vanilla math uses nutrition * modifier * 2)
        // - absolute saturation points (some APIs / tooltips display this)
        //
        // Heuristic: modifiers are typically <= ~1.2; points are usually > 1.5.
        if (s <= 1.5f) {
            return Math.max(0.0f, nutrition * s * 2.0f);
        }
        return s;
    }

    private record FoodValues(int nutrition, float saturation) {
        static FoodValues lookup(ItemStack stack) {
            if (stack == null || stack.getType().isAir())
                return new FoodValues(0, 0.0f);

            // Paper data components: works for all vanilla food items (and custom food
            // components).
            try {
                FoodProperties fp = stack.getData(DataComponentTypes.FOOD);
                if (fp != null) {
                    return new FoodValues(Math.max(0, fp.nutrition()), Math.max(0.0f, fp.saturation()));
                }
            } catch (Throwable ignored) {
            }

            FoodComponent fc = foodComponentFromMeta(stack.getItemMeta());
            if (fc == null) {
                // Default values for the material (covers normal food items).
                fc = foodComponentFromMeta(new ItemStack(stack.getType()).getItemMeta());
            }

            if (fc != null) {
                return new FoodValues(Math.max(0, fc.getNutrition()), Math.max(0.0f, fc.getSaturation()));
            }

            // Final fallback: edible but unknown food values.
            return new FoodValues(stack.getType().isEdible() ? 1 : 0, 0.0f);
        }

        private static FoodComponent foodComponentFromMeta(ItemMeta meta) {
            if (meta == null || !meta.hasFood())
                return null;
            try {
                return meta.getFood();
            } catch (Throwable ignored) {
                return null;
            }
        }
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

    private static UUID readBackpackId(Keys keys, ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta())
            return null;
        ItemMeta meta = item.getItemMeta();
        if (meta == null)
            return null;
        String idStr = meta.getPersistentDataContainer().get(keys.BACKPACK_ID, PersistentDataType.STRING);
        if (idStr == null || idStr.isBlank())
            return null;
        try {
            return UUID.fromString(idStr);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static String readBackpackType(Keys keys, ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta())
            return null;
        ItemMeta meta = item.getItemMeta();
        if (meta == null)
            return null;
        return meta.getPersistentDataContainer().get(keys.BACKPACK_TYPE, PersistentDataType.STRING);
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
                s.input = decrementOne(s.input);

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

        return decrementOne(fuel);
    }

    private ItemStack decrementOne(ItemStack stack) {
        if (stack == null)
            return null;
        var s = stack.clone();
        int amt = s.getAmount();
        if (amt <= 1)
            return null;
        s.setAmount(amt - 1);
        return s;
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
            if (bukkitFuel == null || bukkitFuel.getType().isAir() || !bukkitFuel.getType().isFuel())
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
