package io.github.tootertutor.ModularPacks.config;

import java.util.List;

import org.bukkit.Material;

public record UpgradeDef(
                String id,
                String displayName,
                Material material,
                List<String> lore,
                boolean enabled,
                boolean toggleable,
                boolean secondaryAction,
                ScreenType screenType) {
}
