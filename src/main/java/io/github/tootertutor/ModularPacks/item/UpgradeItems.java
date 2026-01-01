package io.github.tootertutor.ModularPacks.item;

import java.util.List;
import java.util.UUID;

import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import io.github.tootertutor.ModularPacks.ModularPacksPlugin;
import io.github.tootertutor.ModularPacks.config.Placeholders;
import io.github.tootertutor.ModularPacks.config.UpgradeDef;
import io.github.tootertutor.ModularPacks.modules.TankModuleLogic;
import io.github.tootertutor.ModularPacks.modules.TankStateCodec;
import io.github.tootertutor.ModularPacks.text.Text;

public final class UpgradeItems {

    private final ModularPacksPlugin plugin;

    public UpgradeItems(ModularPacksPlugin plugin) {
        this.plugin = plugin;
    }

    public ItemStack create(String upgradeId) {
        UpgradeDef def = plugin.cfg().getUpgrade(upgradeId);
        if (def == null)
            throw new IllegalArgumentException("Unknown upgrade: " + upgradeId);

        ItemStack item = new ItemStack(def.material());
        ItemMeta meta = item.getItemMeta();

        meta.displayName(Text.c(Placeholders.expandText(plugin, def, item, def.displayName())));
        if (def.customModelData() > 0) {
            meta.setCustomModelData(def.customModelData());
        }
        if (def.glint()) {
            meta.setEnchantmentGlintOverride(true);
        }

        // PDC: make it portable + unambiguous (no name-matching hacks)
        //
        // CraftingTemplate is intentionally stackable (it's a crafting ingredient, not an installable module),
        // so it does NOT get a unique MODULE_ID.
        if (!"CraftingTemplate".equalsIgnoreCase(upgradeId)) {
            UUID moduleId = UUID.randomUUID();
            meta.getPersistentDataContainer().set(plugin.keys().MODULE_ID, PersistentDataType.STRING, moduleId.toString());
        }
        meta.getPersistentDataContainer().set(plugin.keys().MODULE_TYPE, PersistentDataType.STRING, upgradeId);
        meta.getPersistentDataContainer().set(plugin.keys().MODULE_ENABLED, PersistentDataType.BYTE, (byte) 1);

        // Tank has dynamic visuals; initialize empty state so the placeholder renders.
        if ("Tank".equalsIgnoreCase(upgradeId)) {
            byte[] state = TankStateCodec.encode(new TankStateCodec.State());
            meta.getPersistentDataContainer().set(plugin.keys().MODULE_STATE_B64, PersistentDataType.STRING,
                    java.util.Base64.getEncoder().encodeToString(state));
            item.setItemMeta(meta);
            TankModuleLogic.applyVisuals(plugin, item, state);
            return item;
        }

        List<String> expanded = Placeholders.expandLore(plugin, def, def.lore());
        meta.lore(Text.lore(expanded));

        item.setItemMeta(meta);
        return item;
    }
}
