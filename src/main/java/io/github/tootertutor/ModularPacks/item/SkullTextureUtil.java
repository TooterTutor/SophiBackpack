package io.github.tootertutor.ModularPacks.item;

import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.inventory.meta.SkullMeta;

import com.destroystokyo.paper.profile.ProfileProperty;

public final class SkullTextureUtil {

    private SkullTextureUtil() {
    }

    /**
     * Applies a Base64-encoded "textures" property to a player head.
     * The Base64 should be the standard Mojang textures payload (as used by most head databases).
     */
    public static void applyBase64Texture(SkullMeta skullMeta, String base64Textures) {
        if (skullMeta == null || base64Textures == null)
            return;
        String value = base64Textures.trim();
        if (value.isEmpty())
            return;

        var profile = Bukkit.createProfile(UUID.randomUUID());
        profile.setProperty(new ProfileProperty("textures", value));
        skullMeta.setPlayerProfile(profile);
    }
}

