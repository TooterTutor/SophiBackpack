package io.github.tootertutor.ModularPacks.item;

import java.util.List;

import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;

public final class CustomModelDataUtil {

    private CustomModelDataUtil() {
    }

    public static void setCustomModelData(ItemMeta meta, Integer customModelData) {
        if (meta == null)
            return;

        if (customModelData == null) {
            meta.setCustomModelDataComponent(null);
            return;
        }

        CustomModelDataComponent component = meta.getCustomModelDataComponent();
        if (component == null)
            return;

        component.setFloats(List.of((float) customModelData.intValue()));
        meta.setCustomModelDataComponent(component);
    }
}

