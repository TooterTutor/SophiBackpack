package io.github.tootertutor.ModularPacks.config;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.ItemStack;

import io.github.tootertutor.ModularPacks.ModularPacksPlugin;

public final class ConfigManager {

    private final ModularPacksPlugin plugin;

    // GUI nav materials (from config.yml)
    private boolean resizeGui = false;
    private Material navPageButtons = Material.ARROW;
    private Material navBorderFiller = Material.GRAY_STAINED_GLASS_PANE;
    private Material unlockedUpgradeSlotMaterial = Material.WHITE_STAINED_GLASS_PANE;
    private Material lockedUpgradeSlotMaterial = Material.IRON_BARS;

    // Debug
    private boolean debugClickLog = false;

    // Container rules
    private boolean allowShulkerBoxes = false;
    private boolean allowBundles = false;

    // Item types that cannot be inserted into backpacks (e.g. by Magnet)
    private Set<Material> backpackInsertBlacklist = Set.of();

    // Backpack types by name
    private final Map<String, BackpackTypeDef> types = new HashMap<>();

    // Upgrades by ID
    private final Map<String, UpgradeDef> upgrades = new HashMap<>();

    public ConfigManager(ModularPacksPlugin plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        plugin.reloadConfig();
        FileConfiguration cfg = plugin.getConfig();

        this.types.clear();
        this.upgrades.clear();

        resizeGui = cfg.getBoolean("modularpacks.ResizeGUI", false);
        debugClickLog = cfg.getBoolean("modularpacks.Debug.ClickLog", false);

        allowShulkerBoxes = cfg.getBoolean("modularpacks.AllowShulkerBoxes", false);
        allowBundles = cfg.getBoolean("modularpacks.AllowBundles", false);

        navPageButtons = mat(cfg.getString("modularpacks.NavPageButtons", "ARROW"), Material.ARROW);
        navBorderFiller = mat(cfg.getString("modularpacks.NavBorderFiller", "GRAY_STAINED_GLASS_PANE"),
                Material.GRAY_STAINED_GLASS_PANE);
        unlockedUpgradeSlotMaterial = mat(
                cfg.getString("modularpacks.UnlockedUpgradeSlotMaterial", "WHITE_STAINED_GLASS_PANE"),
                Material.WHITE_STAINED_GLASS_PANE);
        lockedUpgradeSlotMaterial = mat(
                cfg.getString("modularpacks.LockedUpgradeSlotMaterial", "IRON_BARS"),
                Material.IRON_BARS);

        // Global insert blacklist (Magnet respects this; other insertion paths may as
        // well)
        Set<Material> bl = new HashSet<>();
        for (String raw : cfg.getStringList("modularpacks.BackpackInsertBlacklist")) {
            Material m = parseMaterial(raw);
            if (m != null)
                bl.add(m);
        }
        backpackInsertBlacklist = Collections.unmodifiableSet(bl);

        // Backpack types
        ConfigurationSection typesSec = cfg.getConfigurationSection("BackpackTypes");
        if (typesSec != null) {
            for (String key : typesSec.getKeys(false)) {
                ConfigurationSection s = typesSec.getConfigurationSection(key);
                if (s == null)
                    continue;

                int rows = s.getInt("Rows", 2);
                int upgradeSlots = s.getInt("UpgradeSlots", 0);
                String displayName = firstString(s, "DisplayName", key);
                List<String> lore = firstStringList(s, "Lore");
                int customModelData = firstInt(s, "CustomModelData", 0);

                Material output = mat(
                        firstString(s, "OutputMaterial", s.getString("CraftingRecipe.OutputMaterial", "PLAYER_HEAD")),
                        Material.PLAYER_HEAD);

                types.put(key.toLowerCase(Locale.ROOT),
                        new BackpackTypeDef(key, displayName, rows, upgradeSlots, output, lore, customModelData));
            }
        }

        // Upgrades/modules
        ConfigurationSection upSec = cfg.getConfigurationSection("Upgrades");
        if (upSec != null) {
            for (String id : upSec.getKeys(false)) {
                ConfigurationSection s = upSec.getConfigurationSection(id);
                if (s == null)
                    continue;

                boolean enabled = s.getBoolean("Enabled", true);
                boolean toggleable = s.getBoolean("Toggleable", false);
                boolean secondaryAction = s.getBoolean("SecondaryAction", false);
                String screenType = s.getString("ScreenType", "NONE");

                String displayName = s.getString("DisplayName", id);
                String matName = s.getString("OutputMaterial", s.getString("CraftingRecipe.OutputMaterial", "PAPER"));
                Material material = mat(matName, Material.PAPER);

                List<String> lore = s.getStringList("Lore");
                int customModelData = s.getInt("CustomModelData", 0);
                boolean glint = s.getBoolean("Glint", s.getBoolean("CraftingRecipe.Glint", false));

                upgrades.put(id.toLowerCase(Locale.ROOT),
                        new UpgradeDef(id, displayName, material, lore, customModelData, glint, enabled, toggleable,
                                secondaryAction, ScreenType.from(screenType)));
            }
        }
    }

    public boolean resizeGui() {
        return resizeGui;
    }

    public boolean debugClickLog() {
        return debugClickLog;
    }

    public boolean allowShulkerBoxes() {
        return allowShulkerBoxes;
    }

    public boolean allowBundles() {
        return allowBundles;
    }

    public Set<Material> backpackInsertBlacklist() {
        return backpackInsertBlacklist;
    }

    public boolean isAllowedInBackpack(ItemStack stack) {
        if (stack == null || stack.getType().isAir())
            return true;

        Material mat = stack.getType();

        // Admin-controlled hard blacklist
        if (backpackInsertBlacklist != null && backpackInsertBlacklist.contains(mat))
            return false;

        if (!allowShulkerBoxes && isShulkerBox(mat))
            return false;

        if (!allowBundles && isBundle(mat))
            return false;

        return true;
    }

    private static Material mat(String name, Material fallback) {
        if (name == null)
            return fallback;
        Material m = parseMaterial(name);
        return m != null ? m : fallback;
    }

    private static boolean isShulkerBox(Material mat) {
        return mat != null && mat.name().endsWith("SHULKER_BOX");
    }

    private static boolean isBundle(Material mat) {
        if (mat == null)
            return false;
        String name = mat.name();
        return name.equals("BUNDLE") || name.endsWith("_BUNDLE");
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

    private static ConfigurationSection firstRecipeSection(ConfigurationSection typeSec) {
        if (typeSec == null)
            return null;

        ConfigurationSection sec = typeSec.getConfigurationSection("CraftingRecipe");
        if (sec != null) {
            if (sec.contains("Type") || sec.contains("Pattern") || sec.contains("Ingredients")
                    || sec.contains("OutputMaterial")
                    || sec.contains("DisplayName") || sec.contains("Lore") || sec.contains("CustomModelData")) {
                return sec;
            }
            for (String k : sec.getKeys(false)) {
                ConfigurationSection child = sec.getConfigurationSection(k);
                if (child != null)
                    return child;
            }
        }

        List<?> rawList = typeSec.getList("CraftingRecipe");
        if (rawList != null) {
            for (Object elem : rawList) {
                ConfigurationSection direct = asSection(elem);
                if (direct != null)
                    return direct;
                if (elem instanceof Map<?, ?> wrapper) {
                    if (wrapper.containsKey("Type") || wrapper.containsKey("Pattern")
                            || wrapper.containsKey("Ingredients")
                            || wrapper.containsKey("OutputMaterial") || wrapper.containsKey("DisplayName")
                            || wrapper.containsKey("Lore") || wrapper.containsKey("CustomModelData")) {
                        ConfigurationSection cs = asSection(wrapper);
                        if (cs != null)
                            return cs;
                    }
                    for (Object v : wrapper.values()) {
                        ConfigurationSection cs = asSection(v);
                        if (cs != null)
                            return cs;
                    }
                }
            }
        }

        return null;
    }

    private static ConfigurationSection asSection(Object value) {
        if (value == null)
            return null;
        if (value instanceof ConfigurationSection cs)
            return cs;
        if (value instanceof Map<?, ?> m) {
            org.bukkit.configuration.MemoryConfiguration mem = new org.bukkit.configuration.MemoryConfiguration();
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) m;
            return mem.createSection("r", typed);
        }
        return null;
    }

    private static String firstString(ConfigurationSection typeSec, String path, String fallback) {
        if (typeSec == null)
            return fallback;
        String v = typeSec.getString(path, null);
        if (v != null)
            return v;
        ConfigurationSection first = firstRecipeSection(typeSec);
        if (first != null) {
            String v2 = first.getString(path, null);
            if (v2 != null)
                return v2;
        }
        return fallback;
    }

    private static int firstInt(ConfigurationSection typeSec, String path, int fallback) {
        if (typeSec == null)
            return fallback;
        if (typeSec.contains(path))
            return typeSec.getInt(path, fallback);
        ConfigurationSection first = firstRecipeSection(typeSec);
        if (first != null && first.contains(path))
            return first.getInt(path, fallback);
        return fallback;
    }

    private static List<String> firstStringList(ConfigurationSection typeSec, String path) {
        if (typeSec == null)
            return List.of();
        List<String> v = typeSec.getStringList(path);
        if (v != null && !v.isEmpty())
            return v;
        ConfigurationSection first = firstRecipeSection(typeSec);
        if (first != null) {
            List<String> v2 = first.getStringList(path);
            if (v2 != null && !v2.isEmpty())
                return v2;
        }
        return List.of();
    }

    public BackpackTypeDef getType(String input) {
        return findType(input);
    }

    public BackpackTypeDef findType(String input) {
        if (input == null)
            return null;
        return types.get(input.toLowerCase(Locale.ROOT));
    }

    public Collection<BackpackTypeDef> getTypes() {
        return types.values();
    }

    public UpgradeDef getUpgrade(String input) {
        return findUpgrade(input);
    }

    public UpgradeDef findUpgrade(String input) {
        if (input == null)
            return null;
        return upgrades.get(input.toLowerCase(Locale.ROOT));
    }

    public Collection<UpgradeDef> getUpgrades() {
        return upgrades.values();
    }

    public Material navPageButtons() {
        return navPageButtons;
    }

    public Material navBorderFiller() {
        return navBorderFiller;
    }

    public Material unlockedUpgradeSlotMaterial() {
        return unlockedUpgradeSlotMaterial;
    }

    public Material lockedUpgradeSlotMaterial() {
        return lockedUpgradeSlotMaterial;
    }
}
