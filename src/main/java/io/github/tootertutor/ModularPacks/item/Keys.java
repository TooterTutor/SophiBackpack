package io.github.tootertutor.ModularPacks.item;

import org.bukkit.NamespacedKey;
import org.bukkit.plugin.java.JavaPlugin;

public final class Keys {

    public final NamespacedKey BACKPACK_ID; // UUID string
    public final NamespacedKey BACKPACK_TYPE; // e.g., "Leather"

    public final NamespacedKey MODULE_ID; // UUID string
    public final NamespacedKey MODULE_TYPE; // e.g., "Smelting"
    public final NamespacedKey MODULE_ENABLED; // byte 0/1
    public final NamespacedKey MODULE_STATE; // byte[] (serialized state)
    public final NamespacedKey MODULE_FILTER_MODE;
    public final NamespacedKey MODULE_FILTER_LIST;
    public final NamespacedKey MODULE_STATE_B64;
    public final NamespacedKey MODULE_FEEDING_SELECTION_MODE; // string enum
    public final NamespacedKey MODULE_FEEDING_PREFERENCE; // string enum
    public final NamespacedKey MODULE_JUKEBOX_MODE; // string enum

    public Keys(JavaPlugin plugin) {
        BACKPACK_ID = new NamespacedKey(plugin, "backpack_id");
        BACKPACK_TYPE = new NamespacedKey(plugin, "backpack_type");

        MODULE_ID = new NamespacedKey(plugin, "module_id");
        MODULE_TYPE = new NamespacedKey(plugin, "module_type");
        MODULE_ENABLED = new NamespacedKey(plugin, "module_enabled");
        MODULE_STATE = new NamespacedKey(plugin, "module_state");
        MODULE_FILTER_MODE = new NamespacedKey(plugin, "module_filter_mode");
        MODULE_FILTER_LIST = new NamespacedKey(plugin, "module_filter_list");
        MODULE_STATE_B64 = new NamespacedKey(plugin, "module_state_b64");
        MODULE_FEEDING_SELECTION_MODE = new NamespacedKey(plugin, "module_feeding_selection_mode");
        MODULE_FEEDING_PREFERENCE = new NamespacedKey(plugin, "module_feeding_preference");
        MODULE_JUKEBOX_MODE = new NamespacedKey(plugin, "module_jukebox_mode");

    }

}
