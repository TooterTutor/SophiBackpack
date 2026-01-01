package io.github.tootertutor.ModularPacks.recipes;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.inventory.PrepareSmithingEvent;
import org.bukkit.event.inventory.SmithItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.SmithingInventory;
import org.bukkit.inventory.SmithingTransformRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import io.github.tootertutor.ModularPacks.ModularPacksPlugin;
import io.github.tootertutor.ModularPacks.config.BackpackTypeDef;
import io.github.tootertutor.ModularPacks.config.Placeholders;
import io.github.tootertutor.ModularPacks.item.BackpackItems;
import io.github.tootertutor.ModularPacks.item.Keys;
import io.github.tootertutor.ModularPacks.item.UpgradeItems;
import io.github.tootertutor.ModularPacks.text.Text;

/**
 * Registers recipes from config and handles "dynamic output" (unique IDs).
 *
 * Notes:
 * - Backpacks and modules contain unique UUIDs, so we cannot rely on static
 * recipe
 * outputs; we swap the result on CraftItemEvent.
 * - SHIFT craft is blocked for these recipes to avoid mass-creating unique
 * items
 * and edge cases with client-side crafting automation.
 */
public final class RecipeManager implements Listener {

    private final ModularPacksPlugin plugin;
    private final BackpackItems backpackItems;
    private final UpgradeItems upgradeItems;

    private final Set<NamespacedKey> registeredKeys = new HashSet<>();
    private final Map<NamespacedKey, DynamicRecipe> dynamic = new HashMap<>();
    private final Map<String, SmithingUpgrade> smithingUpgrades = new HashMap<>();

    public RecipeManager(ModularPacksPlugin plugin) {
        this.plugin = plugin;
        this.backpackItems = new BackpackItems(plugin);
        this.upgradeItems = new UpgradeItems(plugin);
    }

    public void reload() {
        unregisterAll();
        registerAll();
        try {
            Bukkit.updateRecipes();
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING,
                    "Failed to update recipes; clients may not see new recipes until relog.",
                    t);
        }
    }

    public boolean isDynamicRecipe(Recipe recipe) {
        NamespacedKey key = recipeKey(recipe);
        if (key == null)
            return false;
        return dynamic.containsKey(key);
    }

    public boolean validateDynamicIngredients(Recipe recipe, ItemStack[] matrix) {
        NamespacedKey key = recipeKey(recipe);
        DynamicRecipe dyn = key == null ? null : dynamic.get(key);
        if (dyn == null)
            return true;
        if (dyn.requirements == null || dyn.requirements.isEmpty())
            return true;
        if (matrix == null)
            return false;

        for (SpecialRequirement req : dyn.requirements) {
            int have = 0;
            for (ItemStack it : matrix) {
                if (matchesRequirement(it, req))
                    have++;
            }
            if (have < req.requiredCount)
                return false;
        }
        return true;
    }

    /**
     * Returns the correct crafting result for the given recipe, including dynamic
     * UUID-bearing items for ModularPacks backpacks/modules.
     *
     * This is used both by vanilla crafting (CraftItemEvent) and by the in-backpack
     * Crafting module GUI which bypasses normal crafting events.
     */
    public ItemStack createCraftResult(Player player, Recipe recipe) {
        return createCraftResult(player, recipe, null);
    }

    public ItemStack createCraftResult(Player player, Recipe recipe, ItemStack[] matrix) {
        if (recipe == null)
            return null;

        NamespacedKey key = recipeKey(recipe);
        DynamicRecipe dyn = key == null ? null : dynamic.get(key);
        if (dyn == null) {
            ItemStack out = recipe.getResult();
            return out == null ? null : out.clone();
        }

        ItemStack result;

        if (dyn.kind == DynamicKind.BACKPACK) {
            UUID upgradeFromId = null;
            String upgradeFromType = null;

            SpecialRequirement baseReq = firstBackpackRequirement(dyn);
            if (matrix != null && baseReq != null) {
                BackpackMatrixRef ref = findBackpackInMatrix(matrix, baseReq.id);
                if (ref != null) {
                    upgradeFromId = ref.id;
                    upgradeFromType = ref.typeId;
                }
            }

            if (upgradeFromId != null) {
                // Upgrading an existing backpack: keep UUID.
                result = backpackItems.createExisting(upgradeFromId, dyn.id);

                // Persist the type change while keeping contents/modules.
                String oldType = plugin.repo().findBackpackType(upgradeFromId);
                if (oldType == null || oldType.isBlank())
                    oldType = upgradeFromType;
                if (oldType == null || oldType.isBlank())
                    oldType = dyn.id;

                var data = plugin.repo().loadOrCreate(upgradeFromId, oldType);
                data.backpackType(dyn.id);
                plugin.repo().saveBackpack(data);
                plugin.sessions().refreshLinkedBackpacksThrottled(upgradeFromId, data);
            } else {
                // New backpack (no base ingredient): new UUID.
                result = backpackItems.create(dyn.id);
            }
        } else {
            result = upgradeItems.create(dyn.id);
        }

        if (dyn.kind == DynamicKind.BACKPACK) {
            ensureOwnedBackpackRow(player, result);
        }

        return result;
    }

    public void close() {
        unregisterAll();
    }

    private void unregisterAll() {
        for (NamespacedKey key : registeredKeys) {
            Bukkit.removeRecipe(key);
        }
        registeredKeys.clear();
        dynamic.clear();
        smithingUpgrades.clear();
    }

    private void registerAll() {
        var cfg = plugin.getConfig();

        ConfigurationSection backpackTypes = cfg.getConfigurationSection("BackpackTypes");
        if (backpackTypes != null) {
            for (String typeId : backpackTypes.getKeys(false)) {
                ConfigurationSection typeSec = backpackTypes.getConfigurationSection(typeId);
                if (typeSec == null)
                    continue;
                registerBackpackRecipes(typeId, typeSec);
            }
        }

        ConfigurationSection upgrades = cfg.getConfigurationSection("Upgrades");
        if (upgrades != null) {
            for (String upgradeId : upgrades.getKeys(false)) {
                ConfigurationSection uSec = upgrades.getConfigurationSection(upgradeId);
                if (uSec == null)
                    continue;
                boolean enabled = uSec.getBoolean("Enabled", true);
                if (!enabled)
                    continue;
                registerUpgradeCraftingRecipe(upgradeId, uSec);
            }
        }
    }

    private void registerBackpackCraftingRecipe(String typeId, ConfigurationSection typeSec) {
        registerBackpackCraftingRecipeVariant(typeId, typeSec, "main",
                typeSec == null ? null : typeSec.getConfigurationSection("CraftingRecipe"));
    }

    private void registerBackpackRecipes(String typeId, ConfigurationSection typeSec) {
        for (RecipeVariant v : readRecipeVariants(typeSec)) {
            String kind = v.section.getString("Type", "Crafting");
            if ("Crafting".equalsIgnoreCase(kind)) {
                registerBackpackCraftingRecipeVariant(typeId, typeSec, v.id, v.section);
            } else if ("Smithing".equalsIgnoreCase(kind)) {
                registerBackpackSmithingRecipeFromVariant(typeId, v.section);
            }
        }
    }

    private void registerBackpackCraftingRecipeVariant(String typeId, ConfigurationSection typeSec, String variantId,
            ConfigurationSection recipe) {
        if (recipe == null)
            return;

        String kind = recipe.getString("Type", "Crafting");
        if (!"Crafting".equalsIgnoreCase(kind))
            return;

        List<String> pattern = recipe.getStringList("Pattern");
        if (pattern == null || pattern.isEmpty())
            return;

        var typeDef = plugin.cfg().findType(typeId);
        if (typeDef == null)
            return;

        String suffix = (variantId == null || variantId.isBlank()) ? "main" : sanitize(variantId);
        NamespacedKey key = new NamespacedKey(plugin, "backpack_" + sanitize(typeId) + "_" + suffix);

        ItemStack preview = new ItemStack(typeDef.outputMaterial());
        ItemMeta meta = preview.getItemMeta();
        if (meta != null) {
            meta.displayName(Text.c(typeDef.displayName()));
            if (typeDef.customModelData() > 0) {
                meta.setCustomModelData(typeDef.customModelData());
            }
            if (typeDef.lore() != null && !typeDef.lore().isEmpty()) {
                meta.lore(Text.lore(Placeholders.expandBackpackLore(plugin, typeDef, null, typeDef.lore())));
            }
            preview.setItemMeta(meta);
        }

        ShapedRecipe shaped = new ShapedRecipe(key, preview);
        shaped.shape(pattern.toArray(new String[0]));

        Map<Character, Integer> symbolCounts = countSymbols(pattern);
        List<SpecialRequirement> requirements = new java.util.ArrayList<>();

        ConfigurationSection ing = recipe.getConfigurationSection("Ingredients");
        if (ing != null) {
            for (String k : ing.getKeys(false)) {
                if (k == null || k.length() != 1)
                    continue;
                char symbol = k.charAt(0);
                String raw = ing.getString(k);
                ParsedIngredient parsed = parseIngredient(raw);

                if (parsed.kind == IngredientKind.MATERIAL) {
                    if (parsed.material != null)
                        shaped.setIngredient(symbol, parsed.material);
                    continue;
                }

                int requiredCount = symbolCounts.getOrDefault(symbol, 0);
                if (requiredCount <= 0)
                    continue;

                // Register as a vanilla material so the recipe can be discovered; we
                // enforce the real requirement in PrepareItemCraftEvent/CraftItemEvent.
                if (parsed.kind == IngredientKind.BACKPACK_TYPE) {
                    shaped.setIngredient(symbol, Material.PLAYER_HEAD);
                    requirements.add(new SpecialRequirement(SpecialKind.BACKPACK_TYPE, parsed.id, requiredCount));
                } else if (parsed.kind == IngredientKind.MODULE_TYPE) {
                    Material m = parsed.material != null ? parsed.material : Material.PAPER;
                    shaped.setIngredient(symbol, m);
                    requirements.add(new SpecialRequirement(SpecialKind.MODULE_TYPE, parsed.id, requiredCount));
                }
            }
        }

        if (Bukkit.addRecipe(shaped)) {
            registeredKeys.add(key);
            dynamic.put(key, new DynamicRecipe(DynamicKind.BACKPACK, typeId, requirements));
        } else {
            plugin.getLogger().warning("Failed to register crafting recipe: " + key);
        }
    }

    private void registerBackpackSmithingRecipeFromVariant(String resultTypeId, ConfigurationSection recipe) {
        if (recipe == null)
            return;

        String kind = recipe.getString("Type", "Crafting");
        if (!"Smithing".equalsIgnoreCase(kind))
            return;

        String templateStr = recipe.getString("Template");
        String additionStr = recipe.getString("Addition");
        String baseStr = recipe.getString("Base");
        if (templateStr == null || additionStr == null || baseStr == null)
            return;

        Material template = parseMaterial(templateStr);
        Material addition = parseMaterial(additionStr);
        if (template == null || addition == null) {
            plugin.getLogger().warning("Skipping smithing recipe for backpack type " + resultTypeId
                    + " because Template/Addition material is invalid (Template=" + templateStr + ", Addition="
                    + additionStr
                    + "). If you meant the netherite upgrade template, use NETHERITE_UPGRADE_SMITHING_TEMPLATE.");
            return;
        }

        String baseTypeId = resolveBackpackTypeId(baseStr);
        if (baseTypeId == null) {
            plugin.getLogger().warning(
                    "Skipping smithing recipe for backpack type " + resultTypeId
                            + " because Base type is invalid (Base="
                            + baseStr + "). Expected e.g. `BACKPACK:Diamond` or `Diamond`.");
            return;
        }

        var baseType = plugin.cfg().findType(baseTypeId);
        var resultType = plugin.cfg().findType(resultTypeId);
        if (baseType == null || resultType == null)
            return;

        smithingUpgrades.put(resultType.id().toLowerCase(Locale.ROOT),
                new SmithingUpgrade(baseType.id(), resultType.id(), template, addition));

        // Register a real smithing recipe so the client will accept the backpack
        // (player head) in the smithing base slot. We still validate the PDC in
        // PrepareSmithingEvent so only true ModularPacks backpacks work.
        registerBackpackSmithingTransformRecipe(baseType, resultType, template, addition);
    }

    private void registerBackpackSmithingTransformRecipe(
            BackpackTypeDef baseType,
            BackpackTypeDef resultType,
            Material template,
            Material addition) {
        if (baseType == null || resultType == null || template == null || addition == null)
            return;

        NamespacedKey key = new NamespacedKey(plugin, "backpack_smith_" + sanitize(resultType.id()));

        ItemStack preview = new ItemStack(resultType.outputMaterial());
        ItemMeta meta = preview.getItemMeta();
        if (meta != null) {
            meta.displayName(Text.c(resultType.displayName()));
            if (resultType.customModelData() > 0) {
                meta.setCustomModelData(resultType.customModelData());
            }
            if (resultType.lore() != null && !resultType.lore().isEmpty()) {
                meta.lore(Text.lore(Placeholders.expandBackpackLore(plugin, resultType, null, resultType.lore())));
            }
            preview.setItemMeta(meta);
        }

        RecipeChoice templateChoice = new RecipeChoice.MaterialChoice(template);
        RecipeChoice baseChoice = new RecipeChoice.MaterialChoice(baseType.outputMaterial());
        RecipeChoice additionChoice = new RecipeChoice.MaterialChoice(addition);

        try {
            SmithingTransformRecipe smith = new SmithingTransformRecipe(key, preview, templateChoice, baseChoice,
                    additionChoice);
            if (Bukkit.addRecipe(smith)) {
                registeredKeys.add(key);
                // plugin.getLogger()
                // .info("Registered smithing recipe " + key + " (Template=" + template + ",
                // Base="
                // + baseType.outputMaterial() + ", Addition=" + addition + ", Result="
                // + resultType.outputMaterial() + ")");
            } else {
                plugin.getLogger().warning("Failed to register smithing recipe: " + key);
            }
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING,
                    "Failed to register smithing recipe " + key
                            + "; client may refuse to accept backpacks in smithing table base slot.",
                    t);
        }
    }

    private void registerUpgradeCraftingRecipe(String upgradeId, ConfigurationSection upgradeSec) {
        ConfigurationSection recipe = upgradeSec.getConfigurationSection("CraftingRecipe");
        if (recipe == null)
            return;

        String kind = recipe.getString("Type", "Crafting");
        if (!"Crafting".equalsIgnoreCase(kind)) {
            return;
        }

        List<String> pattern = recipe.getStringList("Pattern");
        if (pattern == null || pattern.isEmpty())
            return;

        var def = plugin.cfg().findUpgrade(upgradeId);
        if (def == null)
            return;

        NamespacedKey key = new NamespacedKey(plugin, "upgrade_" + sanitize(upgradeId));

        ItemStack preview = new ItemStack(def.material());
        ItemMeta meta = preview.getItemMeta();
        if (meta != null) {
            meta.displayName(Text.c(Placeholders.expandText(plugin, def, preview, def.displayName())));
            if (def.customModelData() > 0) {
                meta.setCustomModelData(def.customModelData());
            }
            if (def.glint()) {
                meta.setEnchantmentGlintOverride(true);
            }
            preview.setItemMeta(meta);
        }

        ShapedRecipe shaped = new ShapedRecipe(key, preview);
        shaped.shape(pattern.toArray(new String[0]));

        Map<Character, Integer> symbolCounts = countSymbols(pattern);
        List<SpecialRequirement> requirements = new java.util.ArrayList<>();

        ConfigurationSection ing = recipe.getConfigurationSection("Ingredients");
        if (ing != null) {
            for (String k : ing.getKeys(false)) {
                if (k == null || k.length() != 1)
                    continue;
                char symbol = k.charAt(0);
                String raw = ing.getString(k);
                ParsedIngredient parsed = parseIngredient(raw);

                if (parsed.kind == IngredientKind.MATERIAL) {
                    if (parsed.material != null)
                        shaped.setIngredient(symbol, parsed.material);
                    continue;
                }

                int requiredCount = symbolCounts.getOrDefault(symbol, 0);
                if (requiredCount <= 0)
                    continue;

                if (parsed.kind == IngredientKind.BACKPACK_TYPE) {
                    shaped.setIngredient(symbol, Material.PLAYER_HEAD);
                    requirements.add(new SpecialRequirement(SpecialKind.BACKPACK_TYPE, parsed.id, requiredCount));
                } else if (parsed.kind == IngredientKind.MODULE_TYPE) {
                    Material m = parsed.material != null ? parsed.material : Material.PAPER;
                    shaped.setIngredient(symbol, m);
                    requirements.add(new SpecialRequirement(SpecialKind.MODULE_TYPE, parsed.id, requiredCount));
                }
            }
        }

        if (Bukkit.addRecipe(shaped)) {
            registeredKeys.add(key);
            dynamic.put(key, new DynamicRecipe(DynamicKind.UPGRADE, upgradeId, requirements));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPrepareCraft(PrepareItemCraftEvent e) {
        Recipe recipe = e.getRecipe();
        NamespacedKey key = recipeKey(recipe);
        if (key == null)
            return;

        DynamicRecipe dyn = dynamic.get(key);
        if (dyn == null)
            return;

        ItemStack[] matrix = e.getInventory() != null ? e.getInventory().getMatrix() : null;
        if (!validateDynamicIngredients(recipe, matrix)) {
            e.getInventory().setResult(null);
            return;
        }

        // Keep result stable/preview-only here; real UUID output is created in
        // CraftItemEvent.
        ItemStack preview = switch (dyn.kind) {
            case BACKPACK -> {
                var type = plugin.cfg().findType(dyn.id);
                if (type == null)
                    yield null;
                ItemStack it = new ItemStack(type.outputMaterial());
                var meta = it.getItemMeta();
                if (meta != null) {
                    meta.displayName(Text.c(type.displayName()));
                    if (type.customModelData() > 0) {
                        meta.setCustomModelData(type.customModelData());
                    }
                    if (type.lore() != null && !type.lore().isEmpty()) {
                        meta.lore(Text.lore(Placeholders.expandBackpackLore(plugin, type, null, type.lore())));
                    }
                    it.setItemMeta(meta);
                }
                yield it;
            }
            case UPGRADE -> {
                var def = plugin.cfg().findUpgrade(dyn.id);
                if (def == null)
                    yield null;
                ItemStack it = new ItemStack(def.material());
                var meta = it.getItemMeta();
                if (meta != null) {
                    meta.displayName(Text.c(Placeholders.expandText(plugin, def, it, def.displayName())));
                    if (def.customModelData() > 0) {
                        meta.setCustomModelData(def.customModelData());
                    }
                    if (def.glint()) {
                        meta.setEnchantmentGlintOverride(true);
                    }
                    it.setItemMeta(meta);
                }
                yield it;
            }
        };

        e.getInventory().setResult(preview);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCraft(CraftItemEvent e) {
        Recipe recipe = e.getRecipe();
        NamespacedKey key = recipeKey(recipe);
        if (key == null)
            return;

        DynamicRecipe dyn = dynamic.get(key);
        if (dyn == null)
            return;

        if (!(e.getWhoClicked() instanceof Player player))
            return;

        if (!validateDynamicIngredients(recipe, e.getInventory() != null ? e.getInventory().getMatrix() : null)) {
            e.setCancelled(true);
            Bukkit.getScheduler().runTask(plugin, player::updateInventory);
            return;
        }

        if (e.isShiftClick()) {
            e.setCancelled(true);
            player.sendMessage(Text.c("&cCraft one at a time for modularpacks items."));
            return;
        }

        ItemStack[] matrix = e.getInventory() != null ? e.getInventory().getMatrix() : null;
        ItemStack result = createCraftResult(player, recipe, matrix);
        e.setCurrentItem(result);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPrepareSmithing(PrepareSmithingEvent e) {
        SmithingInventory inv = e.getInventory();
        if (inv == null)
            return;

        ItemStack base = inv.getInputEquipment();
        ItemStack template = inv.getInputTemplate();
        ItemStack addition = inv.getInputMineral();

        if (base == null || base.getType().isAir())
            return;
        if (template == null || template.getType().isAir())
            return;
        if (addition == null || addition.getType().isAir())
            return;

        // Only intervene for our backpack smithing (player heads). If we intercept broadly
        // (Template+Addition only), we break vanilla netherite upgrades in real smithing tables.
        if (base.getType() != Material.PLAYER_HEAD)
            return;

        // Only intervene if these inputs match one of our smithing upgrades.
        SmithingUpgrade candidate = null;
        for (SmithingUpgrade up : smithingUpgrades.values()) {
            if (up == null)
                continue;
            if (template.getType() == up.template && addition.getType() == up.addition) {
                candidate = up;
                break;
            }
        }
        if (candidate == null)
            return;

        Keys keys = plugin.keys();
        String idStr = base.hasItemMeta()
                ? base.getItemMeta().getPersistentDataContainer().get(keys.BACKPACK_ID, PersistentDataType.STRING)
                : null;
        String baseTypeId = base.hasItemMeta()
                ? base.getItemMeta().getPersistentDataContainer().get(keys.BACKPACK_TYPE, PersistentDataType.STRING)
                : null;
        if (idStr == null || baseTypeId == null) {
            // Prevent the vanilla smithing recipe from accepting random heads.
            e.setResult(null);
            return;
        }

        UUIDUtils.Parsed parsed = UUIDUtils.tryParse(idStr);
        if (parsed == null) {
            e.setResult(null);
            return;
        }

        SmithingUpgrade match = null;
        for (SmithingUpgrade up : smithingUpgrades.values()) {
            if (up == null)
                continue;
            if (template.getType() != up.template)
                continue;
            if (addition.getType() != up.addition)
                continue;
            if (!baseTypeId.equalsIgnoreCase(up.baseTypeId))
                continue;
            match = up;
            break;
        }
        if (match == null) {
            e.setResult(null);
            return;
        }

        var resultType = plugin.cfg().findType(match.resultTypeId);
        if (resultType == null) {
            e.setResult(null);
            return;
        }

        e.setResult(backpackItems.createExisting(parsed.uuid(), resultType.id()));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSmith(SmithItemEvent e) {
        if (!(e.getWhoClicked() instanceof Player))
            return;
        Player player = (Player) e.getWhoClicked();

        ItemStack result = e.getCurrentItem();
        if (result == null || result.getType().isAir() || !result.hasItemMeta())
            return;

        Keys keys = plugin.keys();
        String idStr = result.getItemMeta().getPersistentDataContainer().get(keys.BACKPACK_ID,
                PersistentDataType.STRING);
        String newType = result.getItemMeta().getPersistentDataContainer().get(keys.BACKPACK_TYPE,
                PersistentDataType.STRING);
        if (idStr == null || newType == null)
            return;

        UUIDUtils.Parsed parsed = UUIDUtils.tryParse(idStr);
        if (parsed == null)
            return;

        String oldType = plugin.repo().findBackpackType(parsed.uuid());
        if (oldType == null) {
            // fallback to whatever type was on the input equipment item
            ItemStack base = e.getInventory() != null ? e.getInventory().getInputEquipment() : null;
            if (base != null && base.hasItemMeta()) {
                oldType = base.getItemMeta().getPersistentDataContainer().get(keys.BACKPACK_TYPE,
                        PersistentDataType.STRING);
            }
        }
        if (oldType == null)
            oldType = newType;

        var data = plugin.repo().loadOrCreate(parsed.uuid(), oldType);
        data.backpackType(newType);
        plugin.repo().saveBackpack(data);
        plugin.sessions().refreshLinkedBackpacksThrottled(parsed.uuid(), data);

        plugin.repo().ensureBackpackExists(parsed.uuid(), newType, player.getUniqueId(), player.getName());
    }

    private static NamespacedKey recipeKey(Recipe recipe) {
        if (recipe == null)
            return null;
        if (recipe instanceof org.bukkit.Keyed keyed) {
            return keyed.getKey();
        }
        return null;
    }

    private static String sanitize(String id) {
        String s = (id == null ? "" : id).toLowerCase(Locale.ROOT);
        return s.replaceAll("[^a-z0-9_\\-]", "_");
    }

    private static Material parseMaterial(String name) {
        if (name == null)
            return null;
        String s = name.trim();
        if (s.isEmpty())
            return null;
        if (s.regionMatches(true, 0, "minecraft:", 0, "minecraft:".length())) {
            s = s.substring("minecraft:".length());
        }
        s = s.trim().replace(' ', '_').toUpperCase(Locale.ROOT);
        return Material.getMaterial(s);
    }

    private void ensureOwnedBackpackRow(Player owner, ItemStack backpackItem) {
        if (owner == null || backpackItem == null || !backpackItem.hasItemMeta())
            return;
        ItemMeta meta = backpackItem.getItemMeta();
        if (meta == null)
            return;
        Keys keys = plugin.keys();
        var pdc = meta.getPersistentDataContainer();
        String idStr = pdc.get(keys.BACKPACK_ID, PersistentDataType.STRING);
        String typeId = pdc.get(keys.BACKPACK_TYPE, PersistentDataType.STRING);
        if (idStr == null || typeId == null)
            return;
        UUIDUtils.Parsed parsed = UUIDUtils.tryParse(idStr);
        if (parsed == null)
            return;
        plugin.repo().ensureBackpackExists(parsed.uuid(), typeId, owner.getUniqueId(), owner.getName());
    }

    private String resolveBackpackTypeId(String baseStr) {
        if (baseStr == null || baseStr.isBlank())
            return null;

        // Special token format used in config: BACKPACK:<TypeId>
        String token = baseStr.trim();
        if (token.regionMatches(true, 0, "BACKPACK:", 0, "BACKPACK:".length())) {
            token = token.substring("BACKPACK:".length()).trim();
            if (token.isEmpty())
                return null;
        }

        var directToken = plugin.cfg().findType(token);
        if (directToken != null)
            return directToken.id();

        var direct = plugin.cfg().findType(baseStr);
        if (direct != null)
            return direct.id();

        String s = token;
        s = s.replace(' ', '_');
        String lower = s.toLowerCase(Locale.ROOT);
        if (lower.endsWith("_backpack")) {
            s = s.substring(0, s.length() - "_backpack".length());
        }

        // DIAMOND_BACKPACK -> Diamond
        String guess = s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1).toLowerCase(Locale.ROOT);
        var t = plugin.cfg().findType(guess);
        if (t != null)
            return t.id();

        var t2 = plugin.cfg().findType(s);
        if (t2 != null)
            return t2.id();

        return null;
    }

    private enum IngredientKind {
        MATERIAL,
        BACKPACK_TYPE,
        MODULE_TYPE
    }

    private enum SpecialKind {
        BACKPACK_TYPE,
        MODULE_TYPE
    }

    private record SpecialRequirement(SpecialKind kind, String id, int requiredCount) {
    }

    private record ParsedIngredient(IngredientKind kind, String id, Material material) {
        static ParsedIngredient material(Material m) {
            return new ParsedIngredient(IngredientKind.MATERIAL, null, m);
        }

        static ParsedIngredient backpack(String typeId) {
            return new ParsedIngredient(IngredientKind.BACKPACK_TYPE, typeId, Material.PLAYER_HEAD);
        }

        static ParsedIngredient module(String upgradeId, Material mat) {
            return new ParsedIngredient(IngredientKind.MODULE_TYPE, upgradeId, mat);
        }
    }

    private ParsedIngredient parseIngredient(String raw) {
        if (raw == null || raw.isBlank())
            return ParsedIngredient.material(null);

        String s = raw.trim();

        // Vanilla material name
        Material m = parseMaterial(s);
        if (m != null)
            return ParsedIngredient.material(m);

        // Special: BACKPACK:<TypeId>
        if (s.regionMatches(true, 0, "BACKPACK:", 0, "BACKPACK:".length())) {
            String typeToken = s.substring("BACKPACK:".length()).trim();
            String typeId = resolveBackpackTypeId(typeToken);
            if (typeId != null)
                return ParsedIngredient.backpack(typeId);
        }

        // Special: MODULE:<UpgradeId>
        if (s.regionMatches(true, 0, "MODULE:", 0, "MODULE:".length())
                || s.regionMatches(true, 0, "UPGRADE:", 0, "UPGRADE:".length())) {
            int idx = s.indexOf(':');
            String upgradeToken = idx >= 0 ? s.substring(idx + 1).trim() : "";
            var def = plugin.cfg().findUpgrade(upgradeToken);
            if (def != null)
                return ParsedIngredient.module(def.id(), def.material());
        }

        // Convenience: DIAMOND_BACKPACK, LEATHER_BACKPACK, etc.
        if (s.toLowerCase(Locale.ROOT).endsWith("_backpack")) {
            String typeId = resolveBackpackTypeId(s);
            if (typeId != null)
                return ParsedIngredient.backpack(typeId);
        }

        return ParsedIngredient.material(null);
    }

    private boolean matchesRequirement(ItemStack it, SpecialRequirement req) {
        if (it == null || it.getType().isAir() || !it.hasItemMeta() || req == null)
            return false;
        ItemMeta meta = it.getItemMeta();
        if (meta == null)
            return false;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        Keys keys = plugin.keys();

        if (req.kind == SpecialKind.BACKPACK_TYPE) {
            if (!pdc.has(keys.BACKPACK_ID, PersistentDataType.STRING))
                return false;
            String type = pdc.get(keys.BACKPACK_TYPE, PersistentDataType.STRING);
            return type != null && type.equalsIgnoreCase(req.id);
        }

        if (req.kind == SpecialKind.MODULE_TYPE) {
            String type = pdc.get(keys.MODULE_TYPE, PersistentDataType.STRING);
            return type != null && type.equalsIgnoreCase(req.id);
        }

        return false;
    }

    private static Map<Character, Integer> countSymbols(List<String> pattern) {
        Map<Character, Integer> out = new HashMap<>();
        if (pattern == null)
            return out;
        for (String row : pattern) {
            if (row == null)
                continue;
            for (int i = 0; i < row.length(); i++) {
                char c = row.charAt(i);
                if (c == ' ')
                    continue;
                out.merge(c, 1, Integer::sum);
            }
        }
        return out;
    }

    private enum DynamicKind {
        BACKPACK,
        UPGRADE
    }

    private record DynamicRecipe(DynamicKind kind, String id, List<SpecialRequirement> requirements) {
    }

    private record SmithingUpgrade(String baseTypeId, String resultTypeId, Material template, Material addition) {
    }

    private record RecipeVariant(String id, ConfigurationSection section) {
    }

    private List<RecipeVariant> readRecipeVariants(ConfigurationSection typeSec) {
        if (typeSec == null)
            return List.of();

        // Preferred: CraftingRecipe as section (old format OR keyed variants)
        ConfigurationSection sec = typeSec.getConfigurationSection("CraftingRecipe");
        if (sec != null) {
            // Old format: CraftingRecipe has fields like Type/Pattern/Ingredients
            if (sec.contains("Type") || sec.contains("Pattern") || sec.contains("Ingredients")) {
                return List.of(new RecipeVariant("main", sec));
            }

            // New format: CraftingRecipe has child sections "1", "2", ...
            java.util.ArrayList<RecipeVariant> out = new java.util.ArrayList<>();
            for (String k : sec.getKeys(false)) {
                ConfigurationSection child = sec.getConfigurationSection(k);
                if (child != null) {
                    out.add(new RecipeVariant(k, child));
                }
            }
            if (!out.isEmpty())
                return out;
        }

        // Alternative: CraftingRecipe is a YAML list:
        // - wrapper map format:
        //   CraftingRecipe:
        //     - "1": { ... }
        //     - "2": { ... }
        // - direct map format:
        //   CraftingRecipe:
        //     - { Type: Crafting, Pattern: [...], Ingredients: {...} }
        List<?> rawList = typeSec.getList("CraftingRecipe");
        if (rawList == null || rawList.isEmpty())
            return List.of();

        java.util.ArrayList<RecipeVariant> out = new java.util.ArrayList<>();
        int idx = 0;
        for (Object elem : rawList) {
            String fallbackId = Integer.toString(idx + 1);

            ConfigurationSection direct = asSection(elem);
            if (direct != null) {
                out.add(new RecipeVariant(fallbackId, direct));
                idx++;
                continue;
            }

            if (elem instanceof Map<?, ?> wrapper && !wrapper.isEmpty()) {
                // If this map looks like a recipe already, accept it directly.
                if (wrapper.containsKey("Type") || wrapper.containsKey("Pattern") || wrapper.containsKey("Ingredients")) {
                    ConfigurationSection child = asSection(wrapper);
                    if (child != null)
                        out.add(new RecipeVariant(fallbackId, child));
                    idx++;
                    continue;
                }

                for (Map.Entry<?, ?> e : wrapper.entrySet()) {
                    String id = e.getKey() == null ? fallbackId : e.getKey().toString();
                    ConfigurationSection child = asSection(e.getValue());
                    if (child != null)
                        out.add(new RecipeVariant(id, child));
                }
            }
            idx++;
        }
        return out;
    }

    private static ConfigurationSection asSection(Object value) {
        if (value == null)
            return null;
        if (value instanceof ConfigurationSection cs)
            return cs;
        if (value instanceof Map<?, ?> m) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) m;
            MemoryConfiguration mem = new MemoryConfiguration();
            return mem.createSection("r", typed);
        }
        return null;
    }

    private record BackpackMatrixRef(UUID id, String typeId) {
    }

    private static SpecialRequirement firstBackpackRequirement(DynamicRecipe dyn) {
        if (dyn == null || dyn.requirements == null)
            return null;
        for (SpecialRequirement req : dyn.requirements) {
            if (req != null && req.kind == SpecialKind.BACKPACK_TYPE)
                return req;
        }
        return null;
    }

    private BackpackMatrixRef findBackpackInMatrix(ItemStack[] matrix, String requiredTypeId) {
        if (matrix == null || requiredTypeId == null || requiredTypeId.isBlank())
            return null;
        Keys keys = plugin.keys();
        for (ItemStack it : matrix) {
            if (it == null || it.getType().isAir() || !it.hasItemMeta())
                continue;
            ItemMeta meta = it.getItemMeta();
            if (meta == null)
                continue;
            var pdc = meta.getPersistentDataContainer();
            String typeId = pdc.get(keys.BACKPACK_TYPE, PersistentDataType.STRING);
            if (typeId == null || !typeId.equalsIgnoreCase(requiredTypeId))
                continue;
            String idStr = pdc.get(keys.BACKPACK_ID, PersistentDataType.STRING);
            UUIDUtils.Parsed parsed = UUIDUtils.tryParse(idStr);
            if (parsed == null)
                continue;
            return new BackpackMatrixRef(parsed.uuid(), typeId);
        }
        return null;
    }

    private static final class UUIDUtils {
        private UUIDUtils() {
        }

        private static Parsed tryParse(String s) {
            if (s == null || s.isBlank())
                return null;
            try {
                return new Parsed(java.util.UUID.fromString(s));
            } catch (IllegalArgumentException ex) {
                return null;
            }
        }

        private record Parsed(java.util.UUID uuid) {
        }
    }
}
