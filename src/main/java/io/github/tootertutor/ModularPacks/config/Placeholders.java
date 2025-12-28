package io.github.tootertutor.ModularPacks.config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import io.github.tootertutor.ModularPacks.ModularPacksPlugin;

public final class Placeholders {
    private Placeholders() {
    }

    private static final int MAX_EXPANSION_DEPTH = 6;
    private static final int MAX_EXPANDED_LINES = 250;
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z0-9._\\-]+)\\}");

    public static List<String> expandLore(ModularPacksPlugin plugin, List<String> lore) {
        if (plugin == null)
            return lore == null ? List.of() : lore;

        Map<String, Replacement> overrides = new HashMap<>();
        // Back-compat: treat {moduleActions} as "primary" actions.
        overrides.put("moduleActions", Replacement.list(langActionsPrimary(plugin)));

        return expandLines(plugin, null, null, lore, overrides);
    }

    public static List<String> expandLore(ModularPacksPlugin plugin, UpgradeDef def, List<String> lore) {
        return expandLore(plugin, def, null, lore);
    }

    public static List<String> expandLore(ModularPacksPlugin plugin, UpgradeDef def, ItemStack moduleItem,
            List<String> lore) {
        if (plugin == null)
            return lore == null ? List.of() : lore;
        if (def == null)
            return expandLore(plugin, lore);

        Map<String, Replacement> overrides = new HashMap<>();
        overrides.put("moduleActions", Replacement.list(resolveActions(plugin, def)));

        String toggleState = resolveToggleState(plugin, def, moduleItem);
        // Always register toggleState so non-toggleable modules can safely omit it.
        overrides.put("toggleState", Replacement.scalar(toggleState));

        addUpgradeConfigScalars(plugin, def, overrides);

        return expandLines(plugin, def, moduleItem, lore, overrides);
    }

    public static String expandText(ModularPacksPlugin plugin, UpgradeDef def, ItemStack moduleItem, String text) {
        if (plugin == null || text == null)
            return text;

        Map<String, Replacement> overrides = new HashMap<>();
        if (def != null) {
            overrides.put("moduleActions", Replacement.list(resolveActions(plugin, def)));
            String toggleState = resolveToggleState(plugin, def, moduleItem);
            overrides.put("toggleState", Replacement.scalar(toggleState));
            addUpgradeConfigScalars(plugin, def, overrides);
        } else {
            overrides.put("moduleActions", Replacement.list(langActionsPrimary(plugin)));
        }

        return expandString(plugin, def, moduleItem, text, overrides);
    }

    private static List<String> resolveActions(ModularPacksPlugin plugin, UpgradeDef def) {
        if (plugin == null || def == null)
            return List.of();

        if (def.id() != null && def.id().equalsIgnoreCase("Tank")) {
            return langActionsTank(plugin);
        }
        if (def.secondaryAction()) {
            return langActionsSecondary(plugin);
        }
        if (def.screenType() == ScreenType.NONE) {
            return langActionsPassive(plugin);
        }
        return langActionsPrimary(plugin);
    }

    private static List<String> langActionsPrimary(ModularPacksPlugin plugin) {
        if (plugin == null || plugin.lang() == null)
            return List.of();
        List<String> primary = plugin.lang().getList("moduleActionsPrimary");
        if (!primary.isEmpty())
            return primary;
        return plugin.lang().getList("moduleActions");
    }

    private static List<String> langActionsSecondary(ModularPacksPlugin plugin) {
        if (plugin == null || plugin.lang() == null)
            return List.of();
        List<String> secondary = plugin.lang().getList("moduleActionsSecondary");
        if (!secondary.isEmpty())
            return secondary;
        return langActionsPrimary(plugin);
    }

    private static List<String> langActionsTank(ModularPacksPlugin plugin) {
        if (plugin == null || plugin.lang() == null)
            return List.of();
        List<String> tank = plugin.lang().getList("moduleActionsTank");
        if (!tank.isEmpty())
            return tank;
        return langActionsSecondary(plugin);
    }

    private static List<String> langActionsPassive(ModularPacksPlugin plugin) {
        if (plugin == null || plugin.lang() == null)
            return List.of();
        List<String> passive = plugin.lang().getList("moduleActionsPassive");
        if (!passive.isEmpty())
            return passive;
        return langActionsPrimary(plugin);
    }

    private static String resolveToggleState(ModularPacksPlugin plugin, UpgradeDef def, ItemStack moduleItem) {
        if (plugin == null || def == null || !def.toggleable())
            return null;

        boolean enabled = true; // default-on if item missing/old

        if (moduleItem != null && moduleItem.hasItemMeta()) {
            ItemMeta meta = moduleItem.getItemMeta();
            if (meta != null) {
                Byte b = meta.getPersistentDataContainer().get(plugin.keys().MODULE_ENABLED, PersistentDataType.BYTE);
                if (b != null) {
                    enabled = (b == 1);
                }
            }
        }

        if (enabled) {
            return plugin.lang().get("toggleState.enabled", "&7State: &aᴇɴᴀʙʟᴇᴅ");
        }
        return plugin.lang().get("toggleState.disabled", "&7State: &cᴅɪѕᴀʙʟᴇᴅ");
    }

    private static void addUpgradeConfigScalars(ModularPacksPlugin plugin, UpgradeDef def,
            Map<String, Replacement> overrides) {
        if (plugin == null || def == null || def.id() == null)
            return;
        if (overrides == null)
            return;

        var sec = plugin.getConfig().getConfigurationSection("Upgrades." + def.id());
        if (sec == null)
            return;

        for (String key : sec.getKeys(false)) {
            Object raw = sec.get(key);
            String formatted = formatScalar(raw);
            if (formatted == null)
                continue;

            overrides.put(key, Replacement.scalar(formatted));

            String lc = lowerCamel(key);
            if (lc != null && !lc.equals(key)) {
                overrides.putIfAbsent(lc, Replacement.scalar(formatted));
            }
        }
    }

    private static String lowerCamel(String key) {
        if (key == null || key.isBlank())
            return null;
        if (key.length() == 1)
            return key.toLowerCase();
        char c0 = key.charAt(0);
        if (!Character.isUpperCase(c0))
            return key;
        return Character.toLowerCase(c0) + key.substring(1);
    }

    private static String formatScalar(Object raw) {
        if (raw == null)
            return null;
        if (raw instanceof String s)
            return s;
        if (raw instanceof Boolean b)
            return b.toString();
        if (raw instanceof Integer || raw instanceof Long || raw instanceof Short || raw instanceof Byte)
            return raw.toString();
        if (raw instanceof Float f) {
            if (f.isNaN() || f.isInfinite())
                return null;
            float rounded = (float) Math.rint(f);
            if (Math.abs(f - rounded) < 1.0e-6)
                return Integer.toString((int) rounded);
            return stripTrailingZeros(Float.toString(f));
        }
        if (raw instanceof Double d) {
            if (d.isNaN() || d.isInfinite())
                return null;
            double rounded = Math.rint(d);
            if (Math.abs(d - rounded) < 1.0e-9)
                return Long.toString((long) rounded);
            return stripTrailingZeros(Double.toString(d));
        }
        return null;
    }

    private static String stripTrailingZeros(String s) {
        if (s == null)
            return null;
        int dot = s.indexOf('.');
        if (dot < 0)
            return s;
        int end = s.length();
        while (end > dot + 1 && s.charAt(end - 1) == '0') {
            end--;
        }
        if (end > dot && s.charAt(end - 1) == '.') {
            end--;
        }
        return s.substring(0, end);
    }

    private static List<String> expandLines(
            ModularPacksPlugin plugin,
            UpgradeDef def,
            ItemStack moduleItem,
            List<String> lore,
            Map<String, Replacement> overrides) {
        if (lore == null || lore.isEmpty())
            return List.of();

        List<String> current = new ArrayList<>();
        for (String line : lore) {
            if (line != null)
                current.add(line);
        }

        for (int depth = 0; depth < MAX_EXPANSION_DEPTH; depth++) {
            boolean changed = false;
            List<String> next = new ArrayList<>();

            for (String line : current) {
                if (line == null)
                    continue;

                List<String> expanded = expandLineToLines(plugin, def, moduleItem, line, overrides);
                if (expanded.isEmpty() || expanded.size() != 1 || !expanded.get(0).equals(line)) {
                    changed = true;
                }

                for (String out : expanded) {
                    if (out == null)
                        continue;
                    next.add(out);
                    if (next.size() >= MAX_EXPANDED_LINES)
                        break;
                }
                if (next.size() >= MAX_EXPANDED_LINES)
                    break;
            }

            current = next;
            if (!changed)
                break;
        }

        return current;
    }

    private static String expandString(
            ModularPacksPlugin plugin,
            UpgradeDef def,
            ItemStack moduleItem,
            String text,
            Map<String, Replacement> overrides) {
        if (text == null)
            return null;

        String current = text;
        for (int depth = 0; depth < MAX_EXPANSION_DEPTH; depth++) {
            Matcher m = PLACEHOLDER.matcher(current);
            boolean any = false;
            StringBuffer sb = new StringBuffer();
            while (m.find()) {
                any = true;
                String key = m.group(1);
                Replacement r = resolveReplacement(plugin, def, moduleItem, key, overrides);
                String repl = r == null ? null : r.asScalar();
                if (r != null && repl == null) {
                    // Known placeholder with no scalar value => remove.
                    repl = "";
                }
                if (repl == null) {
                    repl = m.group(0); // leave untouched
                }
                m.appendReplacement(sb, Matcher.quoteReplacement(repl));
            }
            if (!any)
                return current;
            m.appendTail(sb);

            String next = sb.toString();
            if (next.equals(current))
                break;
            current = next;
        }
        return current;
    }

    private static List<String> expandLineToLines(
            ModularPacksPlugin plugin,
            UpgradeDef def,
            ItemStack moduleItem,
            String line,
            Map<String, Replacement> overrides) {
        List<String> lines = List.of(line);

        List<String> next = new ArrayList<>();

        for (String cur : lines) {
            if (cur == null)
                continue;

            Matcher m = PLACEHOLDER.matcher(cur);
            if (!m.find()) {
                next.add(cur);
                continue;
            }

            String key = m.group(1);
            Replacement r = resolveReplacement(plugin, def, moduleItem, key, overrides);
            if (r == null) {
                next.add(cur); // unknown placeholder
                continue;
            }

            if (r.isList()) {
                List<String> repls = r.list();
                if (repls == null || repls.isEmpty()) {
                    // hide pure placeholder lines (lets you disable lines via empty list)
                    if (!cur.trim().equals("{" + key + "}")) {
                        next.add(cur.replace("{" + key + "}", ""));
                    }
                    continue;
                }

                if (cur.trim().equals("{" + key + "}")) {
                    next.addAll(repls);
                    continue;
                }

                for (String repl : repls) {
                    next.add(cur.replace("{" + key + "}", repl == null ? "" : repl));
                    if (next.size() >= MAX_EXPANDED_LINES)
                        break;
                }
                continue;
            }

            String repl = r.scalar();
            if (repl == null) {
                // hide pure placeholder line
                if (!cur.trim().equals("{" + key + "}")) {
                    next.add(cur.replace("{" + key + "}", ""));
                }
                continue;
            }
            next.add(cur.replace("{" + key + "}", repl));
        }

        return next;
    }

    private static Replacement resolveReplacement(
            ModularPacksPlugin plugin,
            UpgradeDef def,
            ItemStack moduleItem,
            String key,
            Map<String, Replacement> overrides) {
        if (key == null || key.isBlank())
            return null;

        if (overrides != null) {
            Replacement r = overrides.get(key);
            if (r != null)
                return r;
        }

        if (plugin == null || plugin.lang() == null)
            return null;

        Object raw = plugin.lang().raw(key);
        if (raw instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object o : list) {
                if (o == null)
                    continue;
                out.add(o.toString());
            }
            return Replacement.list(out);
        }

        String formatted = formatScalar(raw);
        if (formatted != null)
            return Replacement.scalar(formatted);

        return null;
    }

    private record Replacement(String scalar, List<String> list) {
        static Replacement scalar(String s) {
            return new Replacement(s, null);
        }

        static Replacement list(List<String> l) {
            return new Replacement(null, l == null ? List.of() : l);
        }

        boolean isList() {
            return list != null;
        }

        String asScalar() {
            if (scalar != null)
                return scalar;
            if (list != null && !list.isEmpty())
                return list.get(0);
            return null;
        }
    }
}
