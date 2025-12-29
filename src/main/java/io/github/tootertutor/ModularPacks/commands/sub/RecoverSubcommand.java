package io.github.tootertutor.ModularPacks.commands.sub;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import io.github.tootertutor.ModularPacks.ModularPacksPlugin;
import io.github.tootertutor.ModularPacks.commands.CommandContext;
import io.github.tootertutor.ModularPacks.commands.Subcommand;
import io.github.tootertutor.ModularPacks.data.ItemStackCodec;
import io.github.tootertutor.ModularPacks.data.SQLiteBackpackRepository.VoidedItemRecord;
import io.github.tootertutor.ModularPacks.data.SQLiteBackpackRepository.VoidedItemSummary;
import io.github.tootertutor.ModularPacks.item.BackpackItems;
import net.kyori.adventure.text.Component;

public final class RecoverSubcommand implements Subcommand {

    private final ModularPacksPlugin plugin;
    private final BackpackItems backpackItems;

    public RecoverSubcommand(ModularPacksPlugin plugin) {
        this.plugin = plugin;
        this.backpackItems = new BackpackItems(plugin);
    }

    @Override
    public String name() {
        return "recover";
    }

    @Override
    public String description() {
        return "Recover lost backpacks and voided items (admin)";
    }

    @Override
    public void execute(CommandContext ctx) {
        if (!ctx.sender().hasPermission("modularpacks.admin")) {
            ctx.sender().sendMessage(Component.text("You do not have permission."));
            return;
        }

        if (ctx.size() < 1) {
            sendUsage(ctx.sender());
            return;
        }

        String kind = ctx.arg(0);
        if ("void".equalsIgnoreCase(kind)) {
            handleVoid(ctx);
            return;
        }
        if ("backpack".equalsIgnoreCase(kind)) {
            handleBackpack(ctx);
            return;
        }

        sendUsage(ctx.sender());
    }

    private void handleVoid(CommandContext ctx) {
        if (ctx.size() < 3) {
            ctx.sender().sendMessage(Component.text("Usage: /backpack recover void <player|uuid> list [limit] [all]"));
            ctx.sender().sendMessage(Component.text("   or: /backpack recover void <player|uuid> <id|latest> [receiver]"));
            return;
        }

        UUID playerUuid = parsePlayerOrUuid(ctx.arg(1));
        if (playerUuid == null) {
            ctx.sender().sendMessage(Component.text("Unknown player/UUID: " + ctx.arg(1)));
            return;
        }

        String action = ctx.arg(2);
        if ("list".equalsIgnoreCase(action)) {
            int limit = 20;
            boolean includeRecovered = false;
            for (int i = 3; i < ctx.size(); i++) {
                String a = ctx.arg(i);
                if (a == null)
                    continue;
                if ("all".equalsIgnoreCase(a) || "--all".equalsIgnoreCase(a) || "recovered".equalsIgnoreCase(a)) {
                    includeRecovered = true;
                    continue;
                }
                Integer n = parseInt(a);
                if (n != null)
                    limit = clamp(n, 1, 200);
            }

            List<VoidedItemSummary> rows = plugin.repo().listVoidedItemsByPlayer(playerUuid, limit, includeRecovered);
            if (rows.isEmpty()) {
                ctx.sender().sendMessage(Component.text("No voided items found for " + playerUuid + "."));
                return;
            }

            ctx.sender().sendMessage(Component.text(
                    "Voided items for " + playerUuid + " (showing " + rows.size() + (includeRecovered ? ", including recovered" : ", unrecovered only") + "):"));
            for (VoidedItemSummary row : rows) {
                String when = Instant.ofEpochMilli(row.createdAt).toString();
                String status = row.recoveredAt == null ? "UNRECOVERED" : "RECOVERED";
                String backpackShort = row.backpackId == null ? "?" : (row.backpackId.length() >= 8 ? row.backpackId.substring(0, 8) + "…" : row.backpackId);
                ctx.sender().sendMessage(Component.text(
                        " - #" + row.id + " [" + status + "] " + when + " " + row.itemType + " x" + row.amount + " (bp " + backpackShort + ")"));
            }
            return;
        }

        long id;
        if ("latest".equalsIgnoreCase(action)) {
            List<VoidedItemSummary> rows = plugin.repo().listVoidedItemsByPlayer(playerUuid, 1, false);
            if (rows.isEmpty()) {
                ctx.sender().sendMessage(Component.text("No unrecovered voided items found for " + playerUuid + "."));
                return;
            }
            id = rows.get(0).id;
        } else {
            Long parsed = parseLong(action);
            if (parsed == null || parsed <= 0) {
                ctx.sender().sendMessage(Component.text("Expected an id number or 'latest'."));
                return;
            }
            id = parsed;
        }

        VoidedItemRecord rec = plugin.repo().getVoidedItem(id);
        if (rec == null) {
            ctx.sender().sendMessage(Component.text("Voided item not found: #" + id));
            return;
        }
        if (rec.playerUuid != null && !rec.playerUuid.equalsIgnoreCase(playerUuid.toString())) {
            ctx.sender().sendMessage(Component.text("That voided item does not belong to " + playerUuid + "."));
            return;
        }
        if (rec.recoveredAt != null) {
            ctx.sender().sendMessage(Component.text("That voided item was already recovered: #" + id));
            return;
        }

        ItemStack[] decoded = ItemStackCodec.fromBytes(rec.itemBytes);
        if (decoded.length == 0 || decoded[0] == null || decoded[0].getType().isAir()) {
            ctx.sender().sendMessage(Component.text("Failed to decode stored item data for #" + id + "."));
            return;
        }

        Player receiver = resolveOnlinePlayer(ctx.arg(3));
        if (receiver == null && ctx.sender() instanceof Player p)
            receiver = p;
        if (receiver == null) {
            ctx.sender().sendMessage(Component.text("Console must specify an online receiver."));
            return;
        }

        ItemStack item = decoded[0].clone();
        Map<Integer, ItemStack> leftovers = receiver.getInventory().addItem(item);
        if (!leftovers.isEmpty()) {
            for (ItemStack it : leftovers.values()) {
                receiver.getWorld().dropItemNaturally(receiver.getLocation(), it);
            }
        }

        UUID recoveredByUuid = (ctx.sender() instanceof Player p) ? p.getUniqueId() : null;
        String recoveredByName = ctx.sender().getName();
        plugin.repo().markVoidedItemRecovered(id, recoveredByUuid, recoveredByName);

        ctx.sender().sendMessage(Component.text("Recovered voided item #" + id + " to " + receiver.getName() + "."));
    }

    private void handleBackpack(CommandContext ctx) {
        if (ctx.size() < 3) {
            ctx.sender().sendMessage(Component.text("Usage: /backpack recover backpack <player> <backpackUuid>"));
            return;
        }

        Player target = Bukkit.getPlayerExact(ctx.arg(1));
        if (target == null) {
            ctx.sender().sendMessage(Component.text("Player must be online: " + ctx.arg(1)));
            return;
        }

        UUID backpackId = parseUuid(ctx.arg(2));
        if (backpackId == null) {
            ctx.sender().sendMessage(Component.text("Invalid backpack UUID."));
            return;
        }

        String typeId = plugin.repo().findBackpackType(backpackId);
        if (typeId == null) {
            ctx.sender().sendMessage(Component.text("Backpack not found in DB: " + backpackId));
            return;
        }

        ItemStack item = backpackItems.createExisting(backpackId, typeId);
        Map<Integer, ItemStack> leftovers = target.getInventory().addItem(item);
        if (!leftovers.isEmpty()) {
            for (ItemStack it : leftovers.values()) {
                target.getWorld().dropItemNaturally(target.getLocation(), it);
            }
        }

        plugin.repo().ensureBackpackExists(backpackId, typeId, target.getUniqueId(), target.getName());
        ctx.sender().sendMessage(Component.text("Recreated backpack item " + typeId + " (" + backpackId + ") for " + target.getName() + "."));
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(Component.text("Usage: /backpack recover void <player|uuid> list [limit] [all]"));
        sender.sendMessage(Component.text("   or: /backpack recover void <player|uuid> <id|latest> [receiver]"));
        sender.sendMessage(Component.text("   or: /backpack recover backpack <player> <backpackUuid>"));
    }

    @Override
    public List<String> tabComplete(CommandContext ctx) {
        if (ctx.size() == 1) {
            String prefix = safeLower(ctx.arg(0));
            return filterPrefix(List.of("void", "backpack"), prefix);
        }
        if ("void".equalsIgnoreCase(ctx.arg(0))) {
            if (ctx.size() == 2) {
                String prefix = safeLower(ctx.arg(1));
                List<String> out = new ArrayList<>();
                out.addAll(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());
                out.addAll(Bukkit.getOnlinePlayers().stream().map(p -> p.getUniqueId().toString()).toList());
                return filterPrefix(out, prefix);
            }
            if (ctx.size() == 3) {
                return filterPrefix(List.of("list", "latest"), safeLower(ctx.arg(2)));
            }
            if (ctx.size() == 4) {
                String prefix = safeLower(ctx.arg(3));
                return filterPrefix(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), prefix);
            }
        }
        if ("backpack".equalsIgnoreCase(ctx.arg(0))) {
            if (ctx.size() == 2) {
                String prefix = safeLower(ctx.arg(1));
                return filterPrefix(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), prefix);
            }
        }
        return List.of();
    }

    private UUID parsePlayerOrUuid(String raw) {
        UUID asUuid = parseUuid(raw);
        if (asUuid != null)
            return asUuid;

        OfflinePlayer op = raw == null ? null : Bukkit.getOfflinePlayer(raw);
        if (op == null)
            return null;

        // Allow recovering for offline players if the server has their UUID cached.
        if (!op.isOnline() && !op.hasPlayedBefore())
            return null;

        return op.getUniqueId();
    }

    private static Player resolveOnlinePlayer(String nameOrUuid) {
        if (nameOrUuid == null)
            return null;
        Player byName = Bukkit.getPlayerExact(nameOrUuid);
        if (byName != null)
            return byName;
        UUID u = parseUuid(nameOrUuid);
        return u == null ? null : Bukkit.getPlayer(u);
    }

    private static UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank())
            return null;
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static Integer parseInt(String raw) {
        if (raw == null)
            return null;
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Long parseLong(String raw) {
        if (raw == null)
            return null;
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static String safeLower(String s) {
        return s == null ? "" : s.toLowerCase();
    }

    private static List<String> filterPrefix(List<String> items, String prefix) {
        return items.stream()
                .distinct()
                .filter(s -> s != null && s.toLowerCase().startsWith(prefix))
                .sorted()
                .toList();
    }
}

