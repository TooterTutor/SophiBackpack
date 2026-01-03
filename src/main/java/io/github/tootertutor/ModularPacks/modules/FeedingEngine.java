package io.github.tootertutor.ModularPacks.modules;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.FoodComponent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionEffectTypeCategory;

import io.github.tootertutor.ModularPacks.ModularPacksPlugin;
import io.github.tootertutor.ModularPacks.item.Keys;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.Consumable;
import io.papermc.paper.datacomponent.item.FoodProperties;
import io.papermc.paper.datacomponent.item.SuspiciousStewEffects;
import io.papermc.paper.datacomponent.item.consumable.ConsumeEffect;
import io.papermc.paper.potion.SuspiciousEffectEntry;

final class FeedingEngine {

    private final ModularPacksPlugin plugin;
    private final Map<UUID, Integer> lastFedTickByPlayer = new HashMap<>();

    FeedingEngine(ModularPacksPlugin plugin) {
        this.plugin = plugin;
    }

    boolean applyFeeding(Player player, ItemStack[] contents, ItemStack moduleSnapshot, List<Material> orderedWhitelist) {
        if (player == null || contents == null)
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

        FeedingSettings settings = readFeedingSettings(moduleSnapshot);
        List<Material> ordered = orderedWhitelist == null ? java.util.Collections.emptyList() : orderedWhitelist;

        // If whitelist-order is selected but no whitelist is configured, behave like
        // best-candidate.
        if (settings.mode == FeedingSelectionMode.WHITELIST_ORDER && !ordered.isEmpty()) {
            int chosen = chooseFeedingByWhitelistOrder(contents, ordered, settings.preference, minFood, foodLevel);
            if (chosen < 0)
                return false;
            return consumeFeeding(player, contents, chosen, minFood, foodLevel, settings, now);
        }

        java.util.Set<Material> whitelistSet = ordered.isEmpty()
                ? java.util.Collections.emptySet()
                : new java.util.HashSet<>(ordered);

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
        ItemStack after = BackpackInventoryUtil.decrementOne(it);
        contents[index] = after;

        // Handle remaining container item (e.g. soup -> bowl)
        Material rem = it.getType().getCraftingRemainingItem();
        if (rem != null && !rem.isAir()) {
            ItemStack leftover = BackpackInventoryUtil.insertIntoContents(contents, new ItemStack(rem, 1));
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

    private FeedingSettings readFeedingSettings(ItemStack moduleSnapshot) {
        FeedingSelectionMode defaultMode = FeedingSelectionMode
                .parse(plugin.getConfig().getString("Upgrades.Feeding.SelectionMode", "BestCandidate"));
        FeedingPreference defaultPref = FeedingPreference
                .parse(plugin.getConfig().getString("Upgrades.Feeding.Preference", "Nutrition"));

        if (moduleSnapshot == null || !moduleSnapshot.hasItemMeta()) {
            return new FeedingSettings(defaultMode, defaultPref);
        }

        ItemMeta meta = moduleSnapshot.getItemMeta();
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
}

