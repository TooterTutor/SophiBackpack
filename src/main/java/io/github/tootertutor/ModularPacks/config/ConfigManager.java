package io.github.tootertutor.ModularPacks.config;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

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

        navPageButtons = mat(cfg.getString("modularpacks.NavPageButtons", "ARROW"), Material.ARROW);
        navBorderFiller = mat(cfg.getString("modularpacks.NavBorderFiller", "GRAY_STAINED_GLASS_PANE"),
                Material.GRAY_STAINED_GLASS_PANE);
        unlockedUpgradeSlotMaterial = mat(
                cfg.getString("modularpacks.UnlockedUpgradeSlotMaterial", "WHITE_STAINED_GLASS_PANE"),
                Material.WHITE_STAINED_GLASS_PANE);
        lockedUpgradeSlotMaterial = mat(
                cfg.getString("modularpacks.LockedUpgradeSlotMaterial", "IRON_BARS"),
                Material.IRON_BARS);

        // Backpack types
        ConfigurationSection typesSec = cfg.getConfigurationSection("BackpackTypes");
        if (typesSec != null) {
            for (String key : typesSec.getKeys(false)) {
                ConfigurationSection s = typesSec.getConfigurationSection(key);
                if (s == null)
                    continue;

                int rows = s.getInt("Rows", 2);
                int upgradeSlots = s.getInt("UpgradeSlots", 0);
                String displayName = s.getString("DisplayName", key);

                Material output = mat(
                        s.getString("CraftingRecipe.OutputMaterial", "PLAYER_HEAD"),
                        Material.PLAYER_HEAD);

                types.put(key.toLowerCase(Locale.ROOT),
                        new BackpackTypeDef(key, displayName, rows, upgradeSlots, output));
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
                String matName = s.getString("CraftingRecipe.OutputMaterial", "PAPER");
                Material material = mat(matName, Material.PAPER);

                List<String> lore = s.getStringList("Lore");

                upgrades.put(id.toLowerCase(Locale.ROOT), new UpgradeDef(id, displayName, material, lore, enabled,
                        toggleable, secondaryAction, ScreenType.from(screenType)));
            }
        }
    }

    public boolean resizeGui() {
        return resizeGui;
    }

    public boolean debugClickLog() {
        return debugClickLog;
    }

    private static Material mat(String name, Material fallback) {
        if (name == null)
            return fallback;
        Material m = Material.matchMaterial(name);
        return m != null ? m : fallback;
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
