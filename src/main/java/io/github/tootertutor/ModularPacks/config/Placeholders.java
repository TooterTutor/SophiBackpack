package io.github.tootertutor.ModularPacks.config;

import java.util.ArrayList;
import java.util.List;

import io.github.tootertutor.ModularPacks.ModularPacksPlugin;

public final class Placeholders {
    private Placeholders() {
    }

    public static List<String> expandLore(ModularPacksPlugin plugin, List<String> lore) {
        if (plugin == null)
            return lore == null ? List.of() : lore;
        // Back-compat: treat as "primary" actions.
        return expandLore(plugin, lore, plugin.lang().moduleActionsPrimary());
    }

    public static List<String> expandLore(ModularPacksPlugin plugin, UpgradeDef def, List<String> lore) {
        if (plugin == null)
            return lore == null ? List.of() : lore;
        if (def == null)
            return expandLore(plugin, lore);

        List<String> actions;
        if (def.id() != null && def.id().equalsIgnoreCase("Tank")) {
            actions = plugin.lang().moduleActionsTank();
        } else if (def.secondaryAction()) {
            actions = plugin.lang().moduleActionsSecondary();
        } else if (def.screenType() == ScreenType.NONE) {
            actions = plugin.lang().moduleActionsPassive();
        } else {
            actions = plugin.lang().moduleActionsPrimary();
        }

        return expandLore(plugin, lore, actions);
    }

    private static List<String> expandLore(ModularPacksPlugin plugin, List<String> lore, List<String> moduleActions) {
        if (lore == null || lore.isEmpty())
            return List.of();

        List<String> out = new ArrayList<>();
        for (String line : lore) {
            if (line == null)
                continue;

            if (line.contains("{moduleActions}")) {
                // If the line is just the placeholder, replace it with the list.
                if (line.trim().equals("{moduleActions}")) {
                    out.addAll(moduleActions);
                } else {
                    // inline replace: insert each action line with the line wrapper
                    for (String a : moduleActions) {
                        out.add(line.replace("{moduleActions}", a));
                    }
                }
                continue;
            }

            out.add(line);
        }
        return out;
    }
}
