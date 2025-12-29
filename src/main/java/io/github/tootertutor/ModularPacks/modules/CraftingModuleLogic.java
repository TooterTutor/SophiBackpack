package io.github.tootertutor.ModularPacks.modules;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;

import io.github.tootertutor.ModularPacks.recipes.RecipeManager;
import io.github.tootertutor.ModularPacks.text.Text;

public final class CraftingModuleLogic {

    private static final int RESULT_SLOT = 0;
    private static final int MATRIX_FIRST_SLOT = 1;
    private static final int MATRIX_SIZE = 9;

    private CraftingModuleLogic() {
    }

    public static void updateResult(Inventory inv) {
        updateResult(null, null, inv);
    }

    public static void updateResult(Player player, Inventory inv) {
        updateResult(null, player, inv);
    }

    public static void updateResult(RecipeManager recipes, Player player, Inventory inv) {
        if (inv == null || inv.getSize() < MATRIX_FIRST_SLOT + MATRIX_SIZE)
            return;
        ItemStack[] matrix = readMatrix(inv);
        CraftMatch match = findMatch(recipes, player, matrix);
        inv.setItem(RESULT_SLOT, match == null ? null : match.result.clone());
    }

    public static boolean handleResultClick(RecipeManager recipes, InventoryClickEvent e, Player player) {
        Inventory inv = e.getView().getTopInventory();
        if (inv.getSize() < MATRIX_FIRST_SLOT + MATRIX_SIZE)
            return false;

        int raw = e.getRawSlot();
        if (raw != RESULT_SLOT)
            return false;

        // Don't allow placing items into the result slot.
        if (e.getAction() == InventoryAction.PLACE_ALL
                || e.getAction() == InventoryAction.PLACE_SOME
                || e.getAction() == InventoryAction.PLACE_ONE
                || e.getAction() == InventoryAction.SWAP_WITH_CURSOR
                || e.getAction() == InventoryAction.HOTBAR_SWAP) {
            e.setCancelled(true);
            return true;
        }

        e.setCancelled(true);

        // Only implement standard pick-up and shift-click; block weird edge cases for
        // now.
        boolean shift = e.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY
                || e.getClick() == ClickType.SHIFT_LEFT
                || e.getClick() == ClickType.SHIFT_RIGHT;
        boolean pickup = e.getAction() == InventoryAction.PICKUP_ALL
                || e.getAction() == InventoryAction.PICKUP_SOME
                || e.getAction() == InventoryAction.PICKUP_HALF
                || e.getAction() == InventoryAction.PICKUP_ONE
                || shift;

        if (!pickup)
            return true;

        if (shift) {
            craftShift(recipes, player, inv);
        } else {
            craftOnceToCursor(recipes, e, player, inv);
        }

        updateResult(recipes, player, inv);
        player.updateInventory();
        return true;
    }

    private static void craftShift(RecipeManager recipes, Player player, Inventory inv) {
        // Recompute per-iteration so shapeless assignment stays correct.
        for (int i = 0; i < 64; i++) {
            ItemStack[] matrix = readMatrix(inv);
            CraftMatch match = findMatch(recipes, player, matrix);
            if (match == null)
                return;

            if (recipes != null && recipes.isDynamicRecipe(match.recipe)) {
                player.sendMessage(Text.c("&cCraft one at a time for modularpacks items."));
                return;
            }

            ItemStack out = craftResult(recipes, player, match, matrix);
            if (out == null || out.getType().isAir())
                return;
            var leftovers = player.getInventory().addItem(out);
            if (!leftovers.isEmpty())
                return;

            applyConsumption(inv, matrix, match.consumePerSlot);
        }
    }

    private static void craftOnceToCursor(RecipeManager recipes, InventoryClickEvent e, Player player, Inventory inv) {
        ItemStack[] matrix = readMatrix(inv);
        CraftMatch match = findMatch(recipes, player, matrix);
        if (match == null)
            return;

        ItemStack cursor = e.getCursor();
        boolean dynamic = recipes != null && recipes.isDynamicRecipe(match.recipe);
        if (dynamic && cursor != null && !cursor.getType().isAir()) {
            // Dynamic UUID-bearing items should not stack.
            return;
        }

        ItemStack out = craftResult(recipes, player, match, matrix);
        if (out == null || out.getType().isAir())
            return;

        if (cursor != null && !cursor.getType().isAir()) {
            if (!cursor.isSimilar(out))
                return;
            int space = cursor.getMaxStackSize() - cursor.getAmount();
            if (space < out.getAmount())
                return;
            cursor = cursor.clone();
            cursor.setAmount(cursor.getAmount() + out.getAmount());
        } else {
            cursor = out;
        }

        player.setItemOnCursor(cursor);
        applyConsumption(inv, matrix, match.consumePerSlot);
    }

    private static ItemStack craftResult(RecipeManager recipes, Player player, CraftMatch match, ItemStack[] matrix) {
        if (match == null)
            return null;
        if (recipes == null || match.recipe == null)
            return match.result == null ? null : match.result.clone();

        ItemStack out = recipes.createCraftResult(player, match.recipe, matrix);
        return out == null ? null : out.clone();
    }

    private static void applyConsumption(Inventory inv, ItemStack[] matrix, int[] consumePerSlot) {
        for (int i = 0; i < MATRIX_SIZE; i++) {
            int consume = consumePerSlot[i];
            ItemStack in = matrix[i];
            if (consume <= 0 || in == null || in.getType().isAir())
                continue;

            int remainingAmount = in.getAmount() - consume;
            if (remainingAmount > 0) {
                ItemStack copy = in.clone();
                copy.setAmount(remainingAmount);
                inv.setItem(MATRIX_FIRST_SLOT + i, copy);
                continue;
            }

            // Consumed fully; place remaining item (e.g. bucket) if any.
            Material remainingMat = in.getType().getCraftingRemainingItem();
            if (remainingMat != null && !remainingMat.isAir()) {
                inv.setItem(MATRIX_FIRST_SLOT + i, new ItemStack(remainingMat, 1));
            } else {
                inv.setItem(MATRIX_FIRST_SLOT + i, null);
            }
        }
    }

    private static ItemStack[] readMatrix(Inventory inv) {
        ItemStack[] matrix = new ItemStack[MATRIX_SIZE];
        for (int i = 0; i < MATRIX_SIZE; i++) {
            matrix[i] = inv.getItem(MATRIX_FIRST_SLOT + i);
        }
        return matrix;
    }

    private static boolean isEmpty(ItemStack s) {
        return s == null || s.getType().isAir();
    }

    private static final class CraftMatch {
        final Recipe recipe;
        final ItemStack result;
        final int[] consumePerSlot;

        CraftMatch(Recipe recipe, ItemStack result, int[] consumePerSlot) {
            this.recipe = recipe;
            this.result = result;
            this.consumePerSlot = consumePerSlot;
        }
    }

    private static CraftMatch findMatch(ItemStack[] matrix) {
        return findMatch(null, null, matrix);
    }

    private static CraftMatch findMatch(RecipeManager recipes, Player player, ItemStack[] matrix) {
        if (matrix == null || matrix.length != MATRIX_SIZE)
            return null;

        // Prefer the server's recipe matcher when available (Paper/Spigot API differs
        // across versions). This is more reliable than iterating Bukkit recipes
        // ourselves.
        Recipe direct = tryGetCraftingRecipe(player, matrix);
        if (direct != null) {
            if (recipes != null && recipes.isDynamicRecipe(direct) && !recipes.validateDynamicIngredients(direct, matrix))
                return null;

            ItemStack out = direct.getResult();
            if (out == null || out.getType().isAir())
                return null;

            // The server already confirmed this recipe matches the matrix; don't try
            // to re-match it ourselves (vanilla recipes can be represented in ways
            // that are hard to mirror perfectly with Bukkit RecipeChoice).
            int[] consume = new int[MATRIX_SIZE];
            for (int i = 0; i < MATRIX_SIZE; i++) {
                if (!isEmpty(matrix[i]))
                    consume[i] = 1;
            }
            return new CraftMatch(direct, out.clone(), consume);
        }

        Iterator<Recipe> it = Bukkit.recipeIterator();
        while (it.hasNext()) {
            Recipe r = it.next();
            if (r instanceof ShapedRecipe shaped) {
                CraftMatch match = matchShaped(r, shaped, matrix);
                if (match != null) {
                    if (recipes != null && recipes.isDynamicRecipe(match.recipe)
                            && !recipes.validateDynamicIngredients(match.recipe, matrix))
                        continue;
                    return match;
                }
            } else if (r instanceof ShapelessRecipe shapeless) {
                CraftMatch match = matchShapeless(r, shapeless, matrix);
                if (match != null) {
                    if (recipes != null && recipes.isDynamicRecipe(match.recipe)
                            && !recipes.validateDynamicIngredients(match.recipe, matrix))
                        continue;
                    return match;
                }
            }
        }
        return null;
    }

    private static volatile boolean lookedUpCraftingMethod = false;
    private static volatile java.lang.reflect.Method serverGetCraftingRecipe = null;
    private static volatile java.lang.reflect.Method bukkitGetCraftingRecipe = null;

    private static Recipe tryGetCraftingRecipe(Player player, ItemStack[] matrix) {
        if (player == null || matrix == null)
            return null;
        World world = player.getWorld();
        if (world == null)
            return null;

        ensureCraftingMethodLookup();

        try {
            if (serverGetCraftingRecipe != null) {
                Object out = serverGetCraftingRecipe.invoke(Bukkit.getServer(), matrix, world);
                return (out instanceof Recipe r) ? r : null;
            }
            if (bukkitGetCraftingRecipe != null) {
                Object out = bukkitGetCraftingRecipe.invoke(null, matrix, world);
                return (out instanceof Recipe r) ? r : null;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static void ensureCraftingMethodLookup() {
        if (lookedUpCraftingMethod)
            return;
        lookedUpCraftingMethod = true;

        try {
            serverGetCraftingRecipe = Bukkit.getServer().getClass().getMethod("getCraftingRecipe", ItemStack[].class,
                    World.class);
        } catch (NoSuchMethodException ignored) {
            serverGetCraftingRecipe = null;
        }

        try {
            bukkitGetCraftingRecipe = Bukkit.class.getMethod("getCraftingRecipe", ItemStack[].class, World.class);
        } catch (NoSuchMethodException ignored) {
            bukkitGetCraftingRecipe = null;
        }
    }

    private static CraftMatch matchShapeless(Recipe recipe, ShapelessRecipe shapeless, ItemStack[] matrix) {
        List<RecipeChoice> required = shapeless.getChoiceList();
        if (required == null || required.isEmpty())
            return null;

        List<Integer> nonEmptySlots = new ArrayList<>(9);
        for (int i = 0; i < MATRIX_SIZE; i++) {
            if (!isEmpty(matrix[i]))
                nonEmptySlots.add(i);
        }

        if (nonEmptySlots.size() != required.size())
            return null;

        int[] consume = new int[MATRIX_SIZE];
        for (RecipeChoice choice : required) {
            if (choice == null)
                return null;
            boolean matched = false;
            for (int slot : nonEmptySlots) {
                ItemStack in = matrix[slot];
                if (in == null)
                    continue;
                if (in.getAmount() <= consume[slot])
                    continue;
                if (!choice.test(in))
                    continue;

                consume[slot]++;
                matched = true;
                break;
            }
            if (!matched)
                return null;
        }

        ItemStack out = shapeless.getResult();
        if (out == null || out.getType().isAir())
            return null;

        return new CraftMatch(recipe, out.clone(), consume);
    }

    private static CraftMatch matchShaped(Recipe recipe, ShapedRecipe shaped, ItemStack[] matrix) {
        String[] shape = shaped.getShape();
        if (shape == null || shape.length == 0)
            return null;

        int shapeHeight = Math.min(3, shape.length);
        int shapeWidth = 0;
        for (int y = 0; y < shapeHeight; y++) {
            shapeWidth = Math.max(shapeWidth, shape[y] == null ? 0 : Math.min(3, shape[y].length()));
        }
        if (shapeWidth <= 0)
            return null;

        Map<Character, RecipeChoice> choices = shaped.getChoiceMap();

        for (int offY = 0; offY <= 3 - shapeHeight; offY++) {
            for (int offX = 0; offX <= 3 - shapeWidth; offX++) {
                int[] consume = new int[MATRIX_SIZE];
                if (matchesAtOffset(matrix, shape, shapeWidth, shapeHeight, choices, offX, offY, consume)) {
                    ItemStack out = shaped.getResult();
                    if (out == null || out.getType().isAir())
                        return null;
                    return new CraftMatch(recipe, out.clone(), consume);
                }
            }
        }

        return null;
    }

    private static boolean matchesAtOffset(
            ItemStack[] matrix,
            String[] shape,
            int shapeWidth,
            int shapeHeight,
            Map<Character, RecipeChoice> choices,
            int offX,
            int offY,
            int[] consume) {

        for (int y = 0; y < 3; y++) {
            for (int x = 0; x < 3; x++) {
                int slot = y * 3 + x;
                ItemStack in = matrix[slot];

                boolean inShape = x >= offX && x < offX + shapeWidth && y >= offY && y < offY + shapeHeight;
                char key = ' ';
                if (inShape) {
                    String row = shape[y - offY];
                    if (row != null && (x - offX) < row.length()) {
                        key = row.charAt(x - offX);
                    }
                }

                if (key == ' ') {
                    if (!isEmpty(in))
                        return false;
                    continue;
                }

                RecipeChoice choice = choices.get(key);
                if (choice == null)
                    return false;
                if (isEmpty(in) || !choice.test(in))
                    return false;

                consume[slot] = 1;
            }
        }

        return true;
    }
}
