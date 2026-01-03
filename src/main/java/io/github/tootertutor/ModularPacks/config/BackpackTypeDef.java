package io.github.tootertutor.ModularPacks.config;

import java.util.List;

import org.bukkit.Material;

public record BackpackTypeDef(
		String id,
		String displayName,
		int rows,
		int upgradeSlots,
		Material outputMaterial,
		List<String> lore,
		int customModelData,
		String skullData) {
}
