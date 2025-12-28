package io.github.tootertutor.ModularPacks.config;

import java.io.File;
import java.util.Collections;
import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;

import io.github.tootertutor.ModularPacks.ModularPacksPlugin;

public final class LangManager {

    private final ModularPacksPlugin plugin;
    private YamlConfiguration lang;

    public LangManager(ModularPacksPlugin plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        File f = new File(plugin.getDataFolder(), "lang/en_us.yml");
        if (!f.exists()) {
            plugin.saveResource("lang/en_us.yml", false);
        }
        this.lang = YamlConfiguration.loadConfiguration(f);
    }

    public String get(String path, String fallback) {
        if (lang == null)
            return fallback;
        return lang.getString(path, fallback);
    }

    public List<String> getList(String path) {
        if (lang == null)
            return Collections.emptyList();
        List<String> out = lang.getStringList(path);
        return out == null ? Collections.emptyList() : out;
    }

    /**
     * Returns the raw YAML value at the given path (String, Number, Boolean, List,
     * etc.). Useful for dynamic placeholder expansion.
     */
    public Object raw(String path) {
        if (lang == null || path == null)
            return null;
        return lang.get(path);
    }

    public boolean has(String path) {
        if (lang == null || path == null)
            return false;
        return lang.contains(path);
    }
}
