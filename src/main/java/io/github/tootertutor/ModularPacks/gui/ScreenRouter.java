package io.github.tootertutor.ModularPacks.gui;

import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import io.github.tootertutor.ModularPacks.ModularPacksPlugin;
import io.github.tootertutor.ModularPacks.config.ScreenType;
import io.github.tootertutor.ModularPacks.data.BackpackData;
import io.github.tootertutor.ModularPacks.data.ItemStackCodec;
import io.github.tootertutor.ModularPacks.modules.AnvilModuleLogic;
import io.github.tootertutor.ModularPacks.modules.CraftingModuleLogic;
import io.github.tootertutor.ModularPacks.modules.FurnaceStateCodec;
import io.github.tootertutor.ModularPacks.modules.SmithingModuleLogic;
import io.github.tootertutor.ModularPacks.modules.StonecutterModuleLogic;
import net.kyori.adventure.text.Component;

public final class ScreenRouter {

	private final ModularPacksPlugin plugin;

	public ScreenRouter(ModularPacksPlugin plugin) {
		this.plugin = plugin;
	}

	/**
	 * Open a module screen backed by moduleStates (persistent).
	 */
	public void open(Player player, UUID backpackId, String backpackType, UUID moduleId, ScreenType screenType) {
		if (screenType == ScreenType.NONE)
			return;

		if (screenType == ScreenType.ANVIL) {
			AnvilModuleLogic.open(plugin, player, backpackId, backpackType, moduleId);
			return;
		}

		// Holder so listeners can identify and persist this module screen
		ModuleScreenHolder holder = new ModuleScreenHolder(backpackId, backpackType, moduleId, screenType);

		Inventory inv = switch (screenType) {
			case CRAFTING -> Bukkit.createInventory(holder, InventoryType.WORKBENCH, Component.text("Crafting Module"));
			case SMITHING -> Bukkit.createInventory(holder, InventoryType.SMITHING, Component.text("Smithing Module"));
			case SMELTING -> Bukkit.createInventory(holder, InventoryType.FURNACE, Component.text("Smelting Module"));
			case BLASTING ->
				Bukkit.createInventory(holder, InventoryType.BLAST_FURNACE, Component.text("Blasting Module"));
			case SMOKING -> Bukkit.createInventory(holder, InventoryType.SMOKER, Component.text("Smoking Module"));
			case STONECUTTER ->
				Bukkit.createInventory(holder, InventoryType.STONECUTTER, Component.text("Stonecutter Module"));
			case ANVIL -> Bukkit.createInventory(holder, InventoryType.ANVIL, Component.text("Anvil Module"));
			case DROPPER -> Bukkit.createInventory(holder, InventoryType.DROPPER, Component.text("Dropper Module"));
			case HOPPER -> Bukkit.createInventory(holder, InventoryType.HOPPER, Component.text("Hopper Module"));
			default -> Bukkit.createInventory(holder, 27, Component.text("Module"));
		};

		holder.setInventory(inv);

		// Load saved state into this inventory (from the backpack row + moduleStates
		// map)
		BackpackData data = plugin.repo().loadOrCreate(backpackId, backpackType);
		byte[] state = data.moduleStates().get(moduleId);
		if (state == null) {
			player.openInventory(inv);
			return;
		}

		if (screenType == ScreenType.SMELTING || screenType == ScreenType.BLASTING
				|| screenType == ScreenType.SMOKING) {
			// Furnace-like uses FurnaceStateCodec bytes
			var fs = FurnaceStateCodec.decode(state);

			// Migration fallback: if decode returned empty but we might have old gzipped
			// ItemStack[]
			if ((fs.input == null && fs.fuel == null && fs.output == null) && state != null && state.length > 0) {
				try {
					ItemStack[] old = ItemStackCodec.fromBytes(state);
					if (old.length >= 3) {
						fs.input = old[0];
						fs.fuel = old[1];
						fs.output = old[2];
					}
				} catch (Exception ignored) {
					// leave empty
				}
			}

			inv.setItem(0, fs.input);
			inv.setItem(1, fs.fuel);
			inv.setItem(2, fs.output);

		} else {
			// Everything else uses ItemStackCodec bytes (gzipped)
			ItemStack[] saved = ItemStackCodec.fromBytes(state);
			int limit = Math.min(inv.getSize(), saved.length);
			for (int i = 0; i < limit; i++) {
				inv.setItem(i, saved[i]);
			}
		}

		// Result/output slots are derived; never trust persisted values.
		if (screenType == ScreenType.CRAFTING) {
			inv.setItem(0, null);
			CraftingModuleLogic.updateResult(plugin.recipes(), player, inv);
		}
		if (screenType == ScreenType.STONECUTTER) {
			if (inv.getSize() > 1) {
				inv.setItem(1, null);
				StonecutterModuleLogic.updateResult(inv);
			}
		}
		if (screenType == ScreenType.SMITHING) {
			if (inv.getSize() > 3) {
				inv.setItem(3, null);
				SmithingModuleLogic.updateResult(inv);
			}
		}

		player.openInventory(inv);

	}

}
