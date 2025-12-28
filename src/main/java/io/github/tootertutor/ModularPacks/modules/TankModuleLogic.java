package io.github.tootertutor.ModularPacks.modules;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import io.github.tootertutor.ModularPacks.ModularPacksPlugin;
import io.github.tootertutor.ModularPacks.config.Placeholders;
import io.github.tootertutor.ModularPacks.config.UpgradeDef;
import io.github.tootertutor.ModularPacks.text.Text;

public final class TankModuleLogic {

    public static final int MAX_FLUID_BUCKETS = 16;
    public static final int MAX_EXP_LEVELS = 100;

    private TankModuleLogic() {
    }

    public static boolean isSupportedFluidBucket(Material mat) {
        return mat == Material.WATER_BUCKET
                || mat == Material.LAVA_BUCKET
                || mat == Material.MILK_BUCKET
                || mat == Material.POWDER_SNOW_BUCKET;
    }

    public static Material iconMaterial(TankStateCodec.State s, Material fallback) {
        if (s == null)
            return fallback;
        if (s.expMode || s.expLevels > 0)
            return Material.EXPERIENCE_BOTTLE;
        if (s.fluidBuckets > 0 && s.fluidBucketMaterial != null) {
            Material m = Material.matchMaterial(s.fluidBucketMaterial);
            if (m != null)
                return m;
        }
        return fallback;
    }

    public static void applyVisuals(ModularPacksPlugin plugin, ItemStack moduleItem, byte[] stateBytes) {
        if (plugin == null || moduleItem == null)
            return;
        TankStateCodec.State s = TankStateCodec.decode(stateBytes);
        applyVisuals(plugin, moduleItem, s);
    }

    public static void applyVisuals(ModularPacksPlugin plugin, ItemStack moduleItem, TankStateCodec.State s) {
        if (plugin == null || moduleItem == null || s == null)
            return;

        UpgradeDef def = plugin.cfg().findUpgrade("Tank");
        Material fallback = (def != null ? def.material() : Material.BUCKET);

        Material icon = iconMaterial(s, fallback);
        if (icon != null && moduleItem.getType() != icon) {
            moduleItem.setType(icon);
        }

        ItemMeta meta = moduleItem.getItemMeta();
        if (meta == null)
            return;

        if (def != null) {
            meta.displayName(Text.c(def.displayName()));

            List<String> base = Placeholders.expandLore(plugin, def, def.lore());
            meta.lore(Text.lore(expandContainedFluid(plugin, base, s)));
        }

        moduleItem.setItemMeta(meta);
    }

    private static List<String> expandContainedFluid(ModularPacksPlugin plugin, List<String> lore, TankStateCodec.State s) {
        if (lore == null || lore.isEmpty())
            return List.of();

        List<String> injected = (s.expMode || s.expLevels > 0)
                ? plugin.lang().getList("tankContainedExp")
                : plugin.lang().getList("tankContainedFluid");

        // fallbacks if lang entries are missing
        if (injected.isEmpty()) {
            if (s.expMode || s.expLevels > 0) {
                injected = List.of("&7Stored XP: &f{levels}/{maxLevels} &7levels");
            } else {
                injected = List.of("&7Stored: &f{amount}/{max} &7buckets (&f{fluid}&7)");
            }
        }

        List<String> expandedInjected = new ArrayList<>(injected.size());
        for (String line : injected) {
            expandedInjected.add(replaceTankTokens(line, s));
        }

        List<String> out = new ArrayList<>();
        for (String line : lore) {
            if (line == null)
                continue;
            if (!line.contains("{containedFluid}")) {
                out.add(line);
                continue;
            }

            if (line.trim().equals("{containedFluid}")) {
                out.addAll(expandedInjected);
            } else {
                for (String repl : expandedInjected) {
                    out.add(line.replace("{containedFluid}", repl));
                }
            }
        }

        return out;
    }

    private static String replaceTankTokens(String line, TankStateCodec.State s) {
        if (line == null)
            return "";

        String fluid = formatFluidName(s.fluidBucketMaterial);
        String out = line;
        out = out.replace("{amount}", Integer.toString(Math.max(0, s.fluidBuckets)));
        out = out.replace("{max}", Integer.toString(MAX_FLUID_BUCKETS));
        out = out.replace("{fluid}", fluid);
        out = out.replace("{levels}", Integer.toString(Math.max(0, s.expLevels)));
        out = out.replace("{maxLevels}", Integer.toString(MAX_EXP_LEVELS));
        out = out.replace("{mode}", (s.expMode || s.expLevels > 0) ? "EXP" : "FLUID");
        return out;
    }

    private static String formatFluidName(String bucketMaterialName) {
        if (bucketMaterialName == null || bucketMaterialName.isBlank())
            return "None";

        String key = bucketMaterialName.trim().toUpperCase(Locale.ROOT);
        return switch (key) {
            case "WATER_BUCKET" -> "Water";
            case "LAVA_BUCKET" -> "Lava";
            case "MILK_BUCKET" -> "Milk";
            case "POWDER_SNOW_BUCKET" -> "Powdered Snow";
            default -> key;
        };
    }
}
