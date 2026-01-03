package io.github.tootertutor.ModularPacks.modules;

import org.bukkit.inventory.ItemStack;

final class BackpackInventoryUtil {

    private BackpackInventoryUtil() {
    }

    static ItemStack decrementOne(ItemStack stack) {
        if (stack == null)
            return null;
        ItemStack s = stack.clone();
        int amt = s.getAmount();
        if (amt <= 1)
            return null;
        s.setAmount(amt - 1);
        return s;
    }

    static ItemStack insertIntoContents(ItemStack[] contents, ItemStack stack) {
        if (contents == null)
            return stack;
        if (stack == null || stack.getType().isAir())
            return stack;

        // Merge into existing stacks first
        for (int i = 0; i < contents.length; i++) {
            ItemStack cur = contents[i];
            if (cur == null || cur.getType().isAir())
                continue;
            if (!cur.isSimilar(stack))
                continue;
            int max = cur.getMaxStackSize();
            int space = max - cur.getAmount();
            if (space <= 0)
                continue;

            int move = Math.min(space, stack.getAmount());
            ItemStack merged = cur.clone();
            merged.setAmount(cur.getAmount() + move);
            contents[i] = merged;

            stack.setAmount(stack.getAmount() - move);
            if (stack.getAmount() <= 0)
                return null;
        }

        // Empty slots
        for (int i = 0; i < contents.length; i++) {
            ItemStack cur = contents[i];
            if (cur != null && !cur.getType().isAir())
                continue;

            int toPlace = Math.min(stack.getMaxStackSize(), stack.getAmount());
            ItemStack placed = stack.clone();
            placed.setAmount(toPlace);
            contents[i] = placed;

            stack.setAmount(stack.getAmount() - toPlace);
            if (stack.getAmount() <= 0)
                return null;
        }

        return stack;
    }
}

