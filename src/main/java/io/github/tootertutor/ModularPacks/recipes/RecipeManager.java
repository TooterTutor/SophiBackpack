package io.github.tootertutor.ModularPacks.recipes;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
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
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.SmithingInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import io.github.tootertutor.ModularPacks.ModularPacksPlugin;
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
                registerBackpackCraftingRecipe(typeId, typeSec);
                registerBackpackSmithingRecipe(typeId, typeSec);
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
        ConfigurationSection recipe = typeSec.getConfigurationSection("CraftingRecipe");
        if (recipe == null)
            return;

        String kind = recipe.getString("Type", "Crafting");
        if (!"Crafting".equalsIgnoreCase(kind)) {
            // Smithing/other types handled via events later.
            return;
        }

        List<String> pattern = recipe.getStringList("Pattern");
        if (pattern == null || pattern.isEmpty())
            return;

        var typeDef = plugin.cfg().findType(typeId);
        if (typeDef == null)
            return;

        NamespacedKey key = new NamespacedKey(plugin, "backpack_" + sanitize(typeId));

        ItemStack preview = new ItemStack(typeDef.outputMaterial());
        ItemMeta meta = preview.getItemMeta();
        if (meta != null) {
            meta.displayName(Text.c(typeDef.displayName()));
            preview.setItemMeta(meta);
        }

        ShapedRecipe shaped = new ShapedRecipe(key, preview);
        shaped.shape(pattern.toArray(new String[0]));

        ConfigurationSection ing = recipe.getConfigurationSection("Ingredients");
        if (ing != null) {
            for (String k : ing.getKeys(false)) {
                if (k == null || k.length() != 1)
                    continue;
                char symbol = k.charAt(0);
                String matName = ing.getString(k);
                Material m = Material.matchMaterial(matName == null ? "" : matName);
                if (m != null) {
                    shaped.setIngredient(symbol, m);
                }
            }
        }

        if (Bukkit.addRecipe(shaped)) {
            registeredKeys.add(key);
            dynamic.put(key, new DynamicRecipe(DynamicKind.BACKPACK, typeId));
        }
    }

    private void registerBackpackSmithingRecipe(String resultTypeId, ConfigurationSection typeSec) {
        ConfigurationSection recipe = typeSec.getConfigurationSection("CraftingRecipe");
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

        Material template = Material.matchMaterial(templateStr);
        Material addition = Material.matchMaterial(additionStr);
        if (template == null || addition == null)
            return;

        String baseTypeId = resolveBackpackTypeId(baseStr);
        if (baseTypeId == null)
            return;

        var baseType = plugin.cfg().findType(baseTypeId);
        var resultType = plugin.cfg().findType(resultTypeId);
        if (baseType == null || resultType == null)
            return;

        smithingUpgrades.put(resultType.id().toLowerCase(Locale.ROOT),
                new SmithingUpgrade(baseType.id(), resultType.id(), template, addition));
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
            preview.setItemMeta(meta);
        }

        ShapedRecipe shaped = new ShapedRecipe(key, preview);
        shaped.shape(pattern.toArray(new String[0]));

        ConfigurationSection ing = recipe.getConfigurationSection("Ingredients");
        if (ing != null) {
            for (String k : ing.getKeys(false)) {
                if (k == null || k.length() != 1)
                    continue;
                char symbol = k.charAt(0);
                String matName = ing.getString(k);
                Material m = Material.matchMaterial(matName == null ? "" : matName);
                if (m != null) {
                    shaped.setIngredient(symbol, m);
                }
            }
        }

        if (Bukkit.addRecipe(shaped)) {
            registeredKeys.add(key);
            dynamic.put(key, new DynamicRecipe(DynamicKind.UPGRADE, upgradeId));
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

        if (e.isShiftClick()) {
            e.setCancelled(true);
            player.sendMessage(Text.c("&cCraft one at a time for modularpacks items."));
            return;
        }

        ItemStack result = switch (dyn.kind) {
            case BACKPACK -> backpackItems.create(dyn.id);
            case UPGRADE -> upgradeItems.create(dyn.id);
        };

        e.setCurrentItem(result);

        if (dyn.kind == DynamicKind.BACKPACK && result.hasItemMeta()) {
            ItemMeta meta = result.getItemMeta();
            if (meta != null) {
                String idStr = meta.getPersistentDataContainer().get(plugin.keys().BACKPACK_ID,
                        PersistentDataType.STRING);
                String typeId = meta.getPersistentDataContainer().get(plugin.keys().BACKPACK_TYPE,
                        PersistentDataType.STRING);
                if (idStr != null && typeId != null) {
                    UUIDUtils.Parsed parsed = UUIDUtils.tryParse(idStr);
                    if (parsed != null) {
                        plugin.repo().ensureBackpackExists(parsed.uuid(), typeId, player.getUniqueId(),
                                player.getName());
                    }
                }
            }
        }
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

        Keys keys = plugin.keys();
        String idStr = base.hasItemMeta()
                ? base.getItemMeta().getPersistentDataContainer().get(keys.BACKPACK_ID, PersistentDataType.STRING)
                : null;
        String baseTypeId = base.hasItemMeta()
                ? base.getItemMeta().getPersistentDataContainer().get(keys.BACKPACK_TYPE, PersistentDataType.STRING)
                : null;
        if (idStr == null || baseTypeId == null)
            return;

        UUIDUtils.Parsed parsed = UUIDUtils.tryParse(idStr);
        if (parsed == null)
            return;

        for (SmithingUpgrade up : smithingUpgrades.values()) {
            if (!baseTypeId.equalsIgnoreCase(up.baseTypeId))
                continue;
            if (template.getType() != up.template)
                continue;
            if (addition.getType() != up.addition)
                continue;

            var resultType = plugin.cfg().findType(up.resultTypeId);
            if (resultType == null)
                return;

            ItemStack out = new ItemStack(resultType.outputMaterial());
            ItemMeta meta = out.getItemMeta();
            if (meta != null) {
                meta.displayName(Text.c(resultType.displayName()));
                meta.getPersistentDataContainer().set(keys.BACKPACK_ID, PersistentDataType.STRING,
                        parsed.uuid().toString());
                meta.getPersistentDataContainer().set(keys.BACKPACK_TYPE, PersistentDataType.STRING, resultType.id());
                out.setItemMeta(meta);
            }

            e.setResult(out);
            return;
        }
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

    private String resolveBackpackTypeId(String baseStr) {
        if (baseStr == null || baseStr.isBlank())
            return null;

        var direct = plugin.cfg().findType(baseStr);
        if (direct != null)
            return direct.id();

        String s = baseStr.trim();
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

    private enum DynamicKind {
        BACKPACK,
        UPGRADE
    }

    private record DynamicRecipe(DynamicKind kind, String id) {
    }

    private record SmithingUpgrade(String baseTypeId, String resultTypeId, Material template, Material addition) {
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
