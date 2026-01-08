package io.github.tootertutor.ModularPacks.gui;

import java.util.Comparator;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.bukkit.Material;
import org.bukkit.Registry;
import org.bukkit.inventory.CreativeCategory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import io.github.tootertutor.ModularPacks.ModularPacksPlugin;
import io.papermc.paper.datacomponent.DataComponentType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

public enum BackpackSortMode {
    REGISTRY("Registry"),
    CREATIVE_MENU("Creative Menu"),
    ALPHABETICALLY("Alphabetically"),
    COUNT("Count"),
    TAGS("Tags");

    private final String displayName;

    BackpackSortMode(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public BackpackSortMode next() {
        BackpackSortMode[] all = values();
        return all[(ordinal() + 1) % all.length];
    }

    public static Comparator<ItemStack> comparator(ModularPacksPlugin plugin, BackpackSortMode mode) {
        return switch (mode) {
            case REGISTRY -> byMaterialRegistryOrder();
            case CREATIVE_MENU -> byCreativeMenuOrder();
            case ALPHABETICALLY -> byAlphabeticalName();
            case COUNT -> byCount();
            case TAGS -> byDataComponents();
        };
    }

    private static Comparator<ItemStack> byMaterialRegistryOrder() {
        // "Registered first" should appear closest to the top-left slot.
        return Comparator
                .comparingInt((ItemStack it) -> materialRegistryIndex(type(it)))
                .thenComparing(displayNameComparator());
    }

    private static Comparator<ItemStack> byCreativeMenuOrder() {
        // Earlier creative tabs should appear closer to the top-left slot.
        return Comparator
                .comparingInt((ItemStack it) -> creativeCategoryIndex(type(it)))
                .thenComparingInt(it -> materialRegistryIndex(type(it)))
                .thenComparing(displayNameComparator());
    }

    private static Comparator<ItemStack> byCount() {
        return Comparator
                .comparingInt(BackpackSortMode::amount)
                .reversed()
                .thenComparingInt(it -> materialRegistryIndex(type(it)))
                .thenComparing(displayNameComparator());
    }

    private static Comparator<ItemStack> byDataComponents() {
        Map<ItemStack, int[]> componentIndexCache = new IdentityHashMap<>();

        return Comparator
                .comparingInt((ItemStack it) -> dataComponentCount(it))
                .reversed()
                .thenComparing((a, b) -> compareComponentIndices(componentIndexCache, a, b))
                .thenComparingInt(it -> materialRegistryIndex(type(it)))
                .thenComparing(displayNameComparator());
    }

    private static Comparator<ItemStack> byAlphabeticalName() {
        Map<ItemStack, AlphaKey> cache = new IdentityHashMap<>();
        return Comparator
                .comparing((ItemStack it) -> alphaKey(cache, it))
                .thenComparingInt(it -> materialRegistryIndex(type(it)));
    }

    // Rules:
    // - Names with 1-2 characters sort above all others.
    // - Non-alphanumeric names (ex: "%") sort last.
    // - Otherwise, compare by first 3 characters (ascending), then full name (ascending).
    private record AlphaKey(int group, int shortLen, String prefix3, String full) implements Comparable<AlphaKey> {
        @Override
        public int compareTo(AlphaKey o) {
            if (o == null)
                return -1;

            int byGroup = Integer.compare(group, o.group);
            if (byGroup != 0)
                return byGroup;

            if (group == 0) { // 1-2 char names
                int byLen = Integer.compare(shortLen, o.shortLen);
                if (byLen != 0)
                    return byLen;
            }

            int byPrefix = String.CASE_INSENSITIVE_ORDER.compare(prefix3, o.prefix3);
            if (byPrefix != 0)
                return byPrefix;

            return String.CASE_INSENSITIVE_ORDER.compare(full, o.full);
        }
    }

    private static AlphaKey alphaKey(Map<ItemStack, AlphaKey> cache, ItemStack it) {
        if (it == null)
            return new AlphaKey(2, 0, "", "");
        return cache.computeIfAbsent(it, BackpackSortMode::computeAlphaKey);
    }

    private static AlphaKey computeAlphaKey(ItemStack it) {
        String full = displayNameKey(it);
        String trimmed = full == null ? "" : full.strip();
        String lower = trimmed.toLowerCase(Locale.ROOT);

        if (lower.isEmpty())
            return new AlphaKey(2, 0, "", "");

        char c0 = lower.charAt(0);
        if (!Character.isLetterOrDigit(c0)) {
            String p = lower.length() <= 3 ? lower : lower.substring(0, 3);
            return new AlphaKey(2, 0, p, lower);
        }

        if (lower.length() <= 2) {
            String p = lower;
            return new AlphaKey(0, lower.length(), p, lower);
        }

        String p = lower.length() <= 3 ? lower : lower.substring(0, 3);
        return new AlphaKey(1, 0, p, lower);
    }

    private static Comparator<ItemStack> displayNameComparator() {
        return Comparator.comparing(BackpackSortMode::displayNameKey, String.CASE_INSENSITIVE_ORDER);
    }

    private static String displayNameKey(ItemStack it) {
        if (it == null)
            return "";

        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            try {
                Component dn = meta.displayName();
                if (dn != null) {
                    String plain = PlainTextComponentSerializer.plainText().serialize(dn);
                    if (plain != null && !plain.isBlank())
                        return plain;
                }
            } catch (Exception ignored) {
            }
        }

        Material t = type(it);
        if (t.isAir())
            return "";

        return t.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private static int amount(ItemStack it) {
        if (it == null)
            return 0;
        try {
            return it.getAmount();
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static int dataComponentCount(ItemStack it) {
        if (it == null)
            return 0;
        try {
            return it.getDataTypes().size();
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static int compareComponentIndices(Map<ItemStack, int[]> cache, ItemStack a, ItemStack b) {
        int[] aa = cache.computeIfAbsent(a, BackpackSortMode::componentIndices);
        int[] bb = cache.computeIfAbsent(b, BackpackSortMode::componentIndices);

        int n = Math.min(aa.length, bb.length);
        for (int i = 0; i < n; i++) {
            // "Registered first" component types should appear closer to the top-left slot.
            int cmp = Integer.compare(aa[i], bb[i]);
            if (cmp != 0)
                return cmp;
        }
        return Integer.compare(aa.length, bb.length);
    }

    private static int[] componentIndices(ItemStack it) {
        if (it == null)
            return new int[0];
        Set<DataComponentType> types;
        try {
            types = it.getDataTypes();
        } catch (Exception ignored) {
            return new int[0];
        }
        if (types == null || types.isEmpty())
            return new int[0];

        int[] idx = new int[types.size()];
        int i = 0;
        for (DataComponentType t : types) {
            idx[i++] = dataComponentTypeRegistryIndex(t);
        }
        java.util.Arrays.sort(idx);
        return idx;
    }

    private static Material type(ItemStack it) {
        if (it == null)
            return Material.AIR;
        Material t = it.getType();
        return t == null ? Material.AIR : t;
    }

    private static int creativeCategoryIndex(Material material) {
        if (material == null)
            return Integer.MAX_VALUE;
        CreativeCategory cat;
        try {
            cat = material.getCreativeCategory();
        } catch (Exception ignored) {
            cat = null;
        }
        return cat == null ? Integer.MAX_VALUE : cat.ordinal();
    }

    private static volatile Map<Material, Integer> materialRegistryOrder;

    private static int materialRegistryIndex(Material material) {
        if (material == null)
            return Integer.MAX_VALUE;
        ensureMaterialRegistryOrder();
        return materialRegistryOrder.getOrDefault(material, Integer.MAX_VALUE);
    }

    private static void ensureMaterialRegistryOrder() {
        if (materialRegistryOrder != null)
            return;
        synchronized (BackpackSortMode.class) {
            if (materialRegistryOrder != null)
                return;
            Map<Material, Integer> map = new EnumMap<>(Material.class);
            AtomicInteger i = new AtomicInteger(0);
            Registry.MATERIAL.stream().forEach(m -> map.put(m, i.getAndIncrement()));
            materialRegistryOrder = Map.copyOf(map);
        }
    }

    private static volatile Map<DataComponentType, Integer> dataComponentTypeRegistryOrder;

    private static int dataComponentTypeRegistryIndex(DataComponentType type) {
        if (type == null)
            return Integer.MAX_VALUE;
        ensureDataComponentTypeRegistryOrder();
        return dataComponentTypeRegistryOrder.getOrDefault(type, Integer.MAX_VALUE);
    }

    private static void ensureDataComponentTypeRegistryOrder() {
        if (dataComponentTypeRegistryOrder != null)
            return;
        synchronized (BackpackSortMode.class) {
            if (dataComponentTypeRegistryOrder != null)
                return;
            Map<DataComponentType, Integer> map = new IdentityHashMap<>();
            AtomicInteger i = new AtomicInteger(0);
            Registry.DATA_COMPONENT_TYPE.stream().forEach(t -> map.put(t, i.getAndIncrement()));
            dataComponentTypeRegistryOrder = Map.copyOf(map);
        }
    }
}
