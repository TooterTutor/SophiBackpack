package io.github.tootertutor.ModularPacks.config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import io.github.tootertutor.ModularPacks.ModularPacksPlugin;
import io.github.tootertutor.ModularPacks.data.BackpackData;
import io.github.tootertutor.ModularPacks.data.ItemStackCodec;
import io.github.tootertutor.ModularPacks.item.Keys;

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

    public static List<String> expandBackpackLore(ModularPacksPlugin plugin, BackpackTypeDef type, UUID backpackId,
            List<String> lore) {
        if (plugin == null)
            return lore == null ? List.of() : lore;

        if (type == null) {
            String typeId = backpackId == null ? null : plugin.repo().findBackpackType(backpackId);
            type = typeId == null ? null : plugin.cfg().findType(typeId);
        }

        int totalSlots = type == null ? 0 : (type.rows() * 9);

        BackpackData data = null;
        if (backpackId != null) {
            String effectiveType = type != null ? type.id() : plugin.repo().findBackpackType(backpackId);
            if (effectiveType == null || effectiveType.isBlank())
                effectiveType = "Unknown";
            data = plugin.repo().loadOrCreate(backpackId, effectiveType);
        }

        return expandBackpackLore(plugin, type, backpackId, data, totalSlots, lore);
    }

    public static List<String> expandBackpackLore(
            ModularPacksPlugin plugin,
            BackpackTypeDef type,
            UUID backpackId,
            BackpackData data,
            int totalSlots,
            List<String> lore) {
        if (plugin == null)
            return lore == null ? List.of() : lore;

        Map<String, Replacement> overrides = new HashMap<>();

        String backpackIdStr = backpackId == null ? "" : backpackId.toString();
        overrides.put("backpackId", Replacement.scalar(backpackIdStr));
        overrides.put("BackpackId", Replacement.scalar(backpackIdStr));

        if (type != null) {
            overrides.put("typeId", Replacement.scalar(type.id()));
            overrides.put("TypeId", Replacement.scalar(type.id()));
            overrides.put("rows", Replacement.scalar(Integer.toString(type.rows())));
            overrides.put("Rows", Replacement.scalar(Integer.toString(type.rows())));
            overrides.put("upgradeSlots", Replacement.scalar(Integer.toString(type.upgradeSlots())));
            overrides.put("UpgradeSlots", Replacement.scalar(Integer.toString(type.upgradeSlots())));
        }

        int usedSlots = 0;
        int itemCount = 0;
        int installedModules = 0;
        List<String> installedModuleLines = new ArrayList<>();

        if (data != null) {
            ItemStack[] decoded = ItemStackCodec.fromBytes(data.contentsBytes());

            int effectiveTotalSlots = totalSlots > 0 ? totalSlots : decoded.length;

            int limit = Math.min(decoded.length, Math.max(0, effectiveTotalSlots));
            for (int i = 0; i < limit; i++) {
                ItemStack it = decoded[i];
                if (it == null || it.getType().isAir())
                    continue;
                usedSlots++;
                itemCount += Math.max(0, it.getAmount());
            }

            installedModules = data.installedModules().size();

            // Build installed module display lines from stored snapshots (preferred)
            // or fall back to module UUID.
            Keys keys = plugin.keys();
            data.installedModules().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(e -> {
                        UUID moduleId = e.getValue();
                        if (moduleId == null)
                            return;

                        String display = null;
                        byte[] snap = data.installedSnapshots().get(moduleId);
                        if (snap != null) {
                            ItemStack[] arr = ItemStackCodec.fromBytes(snap);
                            ItemStack moduleItem = (arr != null && arr.length > 0) ? arr[0] : null;
                            if (moduleItem != null && moduleItem.hasItemMeta()) {
                                ItemMeta meta = moduleItem.getItemMeta();
                                if (meta != null) {
                                    String moduleType = meta.getPersistentDataContainer().get(keys.MODULE_TYPE,
                                            PersistentDataType.STRING);
                                    if (moduleType != null) {
                                        UpgradeDef def = plugin.cfg().findUpgrade(moduleType);
                                        display = def != null ? def.displayName() : moduleType;
                                    }
                                }
                            }
                        }

                        if (display == null) {
                            String shortId = moduleId.toString();
                            display = shortId.length() > 8 ? shortId.substring(0, 8) : shortId;
                        }

                        installedModuleLines.add("&7- &f" + display);
                    });
        }

        int effectiveTotalSlots = totalSlots;
        if (effectiveTotalSlots <= 0 && type != null) {
            effectiveTotalSlots = type.rows() * 9;
        }
        if (effectiveTotalSlots < 0)
            effectiveTotalSlots = 0;

        overrides.put("totalSlots", Replacement.scalar(Integer.toString(effectiveTotalSlots)));
        overrides.put("TotalSlots", Replacement.scalar(Integer.toString(effectiveTotalSlots)));

        overrides.put("usedSlots", Replacement.scalar(Integer.toString(usedSlots)));
        overrides.put("UsedSlots", Replacement.scalar(Integer.toString(usedSlots)));
        overrides.put("itemCount", Replacement.scalar(Integer.toString(itemCount)));
        overrides.put("ItemCount", Replacement.scalar(Integer.toString(itemCount)));
        overrides.put("installedModuleCount", Replacement.scalar(Integer.toString(installedModules)));
        overrides.put("InstalledModuleCount", Replacement.scalar(Integer.toString(installedModules)));

        overrides.put("installedModules", Replacement.list(installedModuleLines));
        overrides.put("InstalledModules", Replacement.list(installedModuleLines));

        boolean empty = (usedSlots <= 0 && itemCount <= 0);
        List<String> tpl = empty ? plugin.lang().getList("backpackContentsEmpty")
                : plugin.lang().getList("backpackContents");
        if (tpl == null || tpl.isEmpty()) {
            tpl = empty
                    ? List.of("&7Contents: &8(Empty)")
                    : List.of("&7Contents: &f{usedSlots}&7/&f{totalSlots} &7slots", "&7Items: &f{itemCount}");
        }
        overrides.put("backpackContents", Replacement.list(tpl));

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

        // Optional module-specific placeholders
        if (def.id() != null && def.id().equalsIgnoreCase("Jukebox")) {
            overrides.put("jukeboxMode", Replacement.scalar(resolveJukeboxMode(plugin, moduleItem)));
        } else {
            overrides.put("jukeboxMode", Replacement.scalar(""));
        }
        if (def.id() != null && def.id().equalsIgnoreCase("Feeding")) {
            overrides.put("feedingMode", Replacement.scalar(resolveFeedingMode(plugin, moduleItem)));
        } else {
            overrides.put("feedingMode", Replacement.scalar(""));
        }

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
            if (def.id() != null && def.id().equalsIgnoreCase("Jukebox")) {
                overrides.put("jukeboxMode", Replacement.scalar(resolveJukeboxMode(plugin, moduleItem)));
            } else {
                overrides.put("jukeboxMode", Replacement.scalar(""));
            }
            if (def.id() != null && def.id().equalsIgnoreCase("Feeding")) {
                overrides.put("feedingMode", Replacement.scalar(resolveFeedingMode(plugin, moduleItem)));
            } else {
                overrides.put("feedingMode", Replacement.scalar(""));
            }
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
        if (def.id() != null && def.id().equalsIgnoreCase("Feeding")) {
            return langActionsFeeding(plugin);
        }
        if (def.id() != null && def.id().equalsIgnoreCase("Jukebox")) {
            return langActionsJukebox(plugin);
        }
        if (def.secondaryAction()) {
            return langActionsSecondary(plugin);
        }
        if (def.screenType() == ScreenType.NONE) {
            return langActionsPassive(plugin);
        }
        return langActionsPrimary(plugin);
    }

    private static List<String> langActionsFeeding(ModularPacksPlugin plugin) {
        if (plugin == null || plugin.lang() == null)
            return List.of();
        List<String> feeding = plugin.lang().getList("moduleActionsFeeding");
        if (!feeding.isEmpty())
            return feeding;
        return langActionsSecondary(plugin);
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

    private static List<String> langActionsJukebox(ModularPacksPlugin plugin) {
        if (plugin == null || plugin.lang() == null)
            return List.of();
        List<String> out = plugin.lang().getList("moduleActionsJukebox");
        if (!out.isEmpty())
            return out;
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

    private static String resolveJukeboxMode(ModularPacksPlugin plugin, ItemStack moduleItem) {
        if (plugin == null || moduleItem == null || !moduleItem.hasItemMeta())
            return "";

        ItemMeta meta = moduleItem.getItemMeta();
        if (meta == null)
            return "";

        Keys keys = plugin.keys();
        String raw = meta.getPersistentDataContainer().get(keys.MODULE_JUKEBOX_MODE, PersistentDataType.STRING);
        String s = raw == null ? "" : raw.trim().toUpperCase(java.util.Locale.ROOT);

        return switch (s) {
            case "SHUFFLE" -> plugin.lang().get("jukeboxMode.shuffle", "&7Mode: &fShuffle");
            case "REPEAT_ONE" -> plugin.lang().get("jukeboxMode.repeatOne", "&7Mode: &fRepeat 1");
            case "REPEAT_ALL" -> plugin.lang().get("jukeboxMode.repeatAll", "&7Mode: &fRepeat All");
            default -> plugin.lang().get("jukeboxMode.repeatAll", "&7Mode: &fRepeat All");
        };
    }

    private static String resolveFeedingMode(ModularPacksPlugin plugin, ItemStack moduleItem) {
        if (plugin == null || moduleItem == null || !moduleItem.hasItemMeta())
            return "";

        ItemMeta meta = moduleItem.getItemMeta();
        if (meta == null)
            return "";

        Keys keys = plugin.keys();
        var pdc = meta.getPersistentDataContainer();

        String rawMode = pdc.get(keys.MODULE_FEEDING_SELECTION_MODE, PersistentDataType.STRING);
        String rawPref = pdc.get(keys.MODULE_FEEDING_PREFERENCE, PersistentDataType.STRING);

        if (rawMode == null || rawMode.isBlank()) {
            rawMode = plugin.getConfig().getString("Upgrades.Feeding.SelectionMode", "BestCandidate");
        }
        if (rawPref == null || rawPref.isBlank()) {
            rawPref = plugin.getConfig().getString("Upgrades.Feeding.Preference", "Nutrition");
        }

        String mode = rawMode.trim().toUpperCase(java.util.Locale.ROOT);
        String pref = rawPref.trim().toUpperCase(java.util.Locale.ROOT);

        boolean whitelist = mode.contains("WHITELIST") || mode.contains("PREFER_FIRST");
        boolean effects = pref.contains("EFFECT");

        if (!whitelist && !effects) {
            return plugin.lang().get("feedingMode.candidateNutrition", "&7Mode: &fBest Candidate: Prefer Nutrition");
        }
        if (!whitelist) {
            return plugin.lang().get("feedingMode.candidateEffects", "&7Mode: &fBest Candidate: Prefer Effects");
        }
        if (!effects) {
            return plugin.lang().get("feedingMode.whitelistNutrition", "&7Mode: &fFirst in Whitelist: Prefer Nutrition");
        }
        return plugin.lang().get("feedingMode.whitelistEffects", "&7Mode: &fFirst in Whitelist: Prefer Effects");
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
