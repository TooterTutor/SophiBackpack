package io.github.tootertutor.ModularPacks.modules;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.JukeboxPlayableComponent;
import org.bukkit.persistence.PersistentDataType;

import io.github.tootertutor.ModularPacks.ModularPacksPlugin;
import io.github.tootertutor.ModularPacks.config.ScreenType;
import io.github.tootertutor.ModularPacks.data.BackpackData;
import io.github.tootertutor.ModularPacks.data.ItemStackCodec;
import io.github.tootertutor.ModularPacks.gui.ModuleScreenHolder;
import io.github.tootertutor.ModularPacks.item.Keys;

final class JukeboxEngine {

    private final ModularPacksPlugin plugin;
    private final Map<UUID, JukeboxPlayback> jukeboxByPlayer = new HashMap<>();

    JukeboxEngine(ModularPacksPlugin plugin) {
        this.plugin = plugin;
    }

    void stopIfActiveBackpackMissing(Player player, ItemStack[] inventoryContents) {
        if (player == null || inventoryContents == null || inventoryContents.length == 0)
            return;

        JukeboxPlayback active = jukeboxByPlayer.get(player.getUniqueId());
        if (active == null)
            return;

        Keys keys = plugin.keys();
        if (!containsBackpack(keys, inventoryContents, active.backpackId())) {
            stopJukebox(player);
        }
    }

    void cleanupOfflinePlayers() {
        jukeboxByPlayer.entrySet().removeIf(e -> Bukkit.getPlayer(e.getKey()) == null);
    }

    void tickJukebox(
            Player player,
            UUID backpackId,
            BackpackData data,
            UUID moduleId,
            ItemStack moduleSnapshot) {
        if (player == null || backpackId == null || data == null)
            return;

        UUID playerId = player.getUniqueId();
        JukeboxPlayback current = jukeboxByPlayer.get(playerId);

        // Only one backpack per player can provide music at a time; if another is
        // currently active, do nothing.
        if (current != null && !current.backpackId().equals(backpackId))
            return;

        if (moduleId == null) {
            // Disabled or not installed: stop if this backpack was active.
            if (current != null && current.backpackId().equals(backpackId)) {
                stopJukebox(player);
            }
            return;
        }

        List<Material> playlist = readJukeboxPlaylist(player, backpackId, moduleId, data);

        // If the module doesn't contain any discs, it can't play anything.
        if (playlist.isEmpty()) {
            if (current != null && current.backpackId().equals(backpackId))
                stopJukebox(player);
            return;
        }

        JukeboxMode mode = readJukeboxMode(moduleSnapshot);

        float volume = (float) plugin.getConfig().getDouble("Upgrades.Jukebox.Volume", 1.0);
        float pitch = (float) plugin.getConfig().getDouble("Upgrades.Jukebox.Pitch", 1.0);

        int now = Bukkit.getCurrentTick();
        if (current == null) {
            int nextIndex = chooseInitialIndex(mode, playlist.size());
            if (nextIndex < 0)
                return;
            Material nextDisc = playlist.get(nextIndex);
            int duration = discDurationTicks(nextDisc);
            startJukebox(player, backpackId, moduleId, mode, nextDisc, duration, volume, pitch);
            return;
        }

        // If mode changed, update (no need to restart unless rotation triggers)
        if (current.mode() != mode) {
            current = new JukeboxPlayback(current.backpackId(), current.moduleId(), current.disc(),
                    current.sound(), current.listeners(), current.endTick(), mode);
            jukeboxByPlayer.put(playerId, current);
        }

        // If the currently-playing disc is no longer present, advance/stop.
        boolean discStillPresent = current.disc() != null && playlist.contains(current.disc());
        boolean timeUp = now >= current.endTick();
        if (!discStillPresent || timeUp) {
            int currentIndex = playlist.indexOf(current.disc());
            int nextIndex = chooseNextIndex(mode, playlist.size(), currentIndex);
            if (nextIndex < 0) {
                stopJukebox(player);
                return;
            }

            Material nextDisc = playlist.get(nextIndex);
            int duration = discDurationTicks(nextDisc);
            switchJukeboxTrack(player, backpackId, moduleId, mode, nextDisc, duration, volume, pitch, current);
        }
    }

    void stopJukebox(Player player) {
        if (player == null)
            return;
        JukeboxPlayback current = jukeboxByPlayer.remove(player.getUniqueId());
        if (current != null)
            stopDiscForListeners(current);
    }

    private static boolean containsBackpack(Keys keys, ItemStack[] invContents, UUID backpackId) {
        if (keys == null || invContents == null || backpackId == null)
            return false;
        for (ItemStack it : invContents) {
            UUID id = readBackpackId(keys, it);
            if (id != null && id.equals(backpackId))
                return true;
        }
        return false;
    }

    private static UUID readBackpackId(Keys keys, ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta())
            return null;
        ItemMeta meta = item.getItemMeta();
        if (meta == null)
            return null;
        String idStr = meta.getPersistentDataContainer().get(keys.BACKPACK_ID, PersistentDataType.STRING);
        if (idStr == null || idStr.isBlank())
            return null;
        try {
            return UUID.fromString(idStr);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    // ------------------------------------------------------------------------
    // Jukebox module implementation
    // ------------------------------------------------------------------------
    private enum JukeboxMode {
        SHUFFLE,
        REPEAT_ONE,
        REPEAT_ALL;

        static JukeboxMode fromString(String raw, String fallbackRaw) {
            JukeboxMode parsed = parse(raw);
            if (parsed != null)
                return parsed;
            parsed = parse(fallbackRaw);
            if (parsed != null)
                return parsed;
            return REPEAT_ALL;
        }

        private static JukeboxMode parse(String raw) {
            if (raw == null)
                return null;
            String s = raw.trim().toUpperCase(java.util.Locale.ROOT);
            if (s.isEmpty())
                return null;
            return switch (s) {
                case "SHUFFLE", "RANDOM" -> SHUFFLE;
                case "REPEAT_ONE", "REPEAT1", "ONE" -> REPEAT_ONE;
                case "REPEAT_ALL", "REPEATALL", "ALL" -> REPEAT_ALL;
                default -> null;
            };
        }
    }

    private record JukeboxPlayback(
            UUID backpackId,
            UUID moduleId,
            Material disc,
            org.bukkit.Sound sound,
            Set<UUID> listeners,
            int endTick,
            JukeboxMode mode) {
    }

    private static boolean isMusicDisc(Material mat) {
        return mat != null && mat.name().startsWith("MUSIC_DISC_");
    }

    private List<Material> readJukeboxPlaylist(Player player, UUID backpackId, UUID moduleId, BackpackData data) {
        if (player == null || backpackId == null || moduleId == null || data == null)
            return java.util.Collections.emptyList();

        ItemStack[] items = null;

        // If the player is currently editing this jukebox module, use the live UI
        // inventory so changes take effect immediately.
        Inventory top = player.getOpenInventory() == null ? null : player.getOpenInventory().getTopInventory();
        if (top != null && top.getHolder() instanceof ModuleScreenHolder msh) {
            if (msh.screenType() == ScreenType.DROPPER
                    && backpackId.equals(msh.backpackId())
                    && moduleId.equals(msh.moduleId())) {
                items = top.getContents();
            }
        }

        if (items == null) {
            byte[] state = data.moduleStates().get(moduleId);
            if (state == null || state.length == 0)
                return java.util.Collections.emptyList();
            items = ItemStackCodec.fromBytes(state);
        }

        java.util.ArrayList<Material> playlist = new java.util.ArrayList<>();
        for (ItemStack it : items) {
            if (it == null || it.getType().isAir())
                continue;
            Material m = it.getType();
            if (!isMusicDisc(m))
                continue;
            if (it.getAmount() <= 0)
                continue;
            playlist.add(m);
        }
        return playlist;
    }

    private JukeboxMode readJukeboxMode(ItemStack moduleItem) {
        if (moduleItem == null || !moduleItem.hasItemMeta())
            return JukeboxMode.REPEAT_ALL;
        ItemMeta meta = moduleItem.getItemMeta();
        if (meta == null)
            return JukeboxMode.REPEAT_ALL;
        String raw = meta.getPersistentDataContainer().get(plugin.keys().MODULE_JUKEBOX_MODE,
                PersistentDataType.STRING);
        String fallback = plugin.getConfig().getString("Upgrades.Jukebox.Mode", "RepeatAll");
        return JukeboxMode.fromString(raw, fallback);
    }

    private static int chooseInitialIndex(JukeboxMode mode, int playlistSize) {
        if (playlistSize <= 0)
            return -1;
        if (mode == JukeboxMode.SHUFFLE) {
            return java.util.concurrent.ThreadLocalRandom.current().nextInt(playlistSize);
        }
        return 0;
    }

    private static int chooseNextIndex(JukeboxMode mode, int playlistSize, int currentIndex) {
        if (playlistSize <= 0)
            return -1;

        if (mode == JukeboxMode.REPEAT_ONE) {
            return currentIndex >= 0 ? currentIndex : -1;
        }

        if (mode == JukeboxMode.SHUFFLE) {
            if (playlistSize == 1)
                return 0;
            int idx;
            do {
                idx = java.util.concurrent.ThreadLocalRandom.current().nextInt(playlistSize);
            } while (idx == currentIndex);
            return idx;
        }

        // REPEAT_ALL
        if (currentIndex < 0)
            return 0;
        return (currentIndex + 1) % playlistSize;
    }

    private void startJukebox(
            Player player,
            UUID backpackId,
            UUID moduleId,
            JukeboxMode mode,
            Material disc,
            int durationTicks,
            float volume,
            float pitch) {
        org.bukkit.Sound sound = discToSound(disc);
        if (sound == null)
            return;

        Set<UUID> listeners = playDiscAround(player, sound, volume, pitch);
        int now = Bukkit.getCurrentTick();
        jukeboxByPlayer.put(player.getUniqueId(),
                new JukeboxPlayback(backpackId, moduleId, disc, sound, listeners, now + durationTicks, mode));
    }

    private void switchJukeboxTrack(
            Player player,
            UUID backpackId,
            UUID moduleId,
            JukeboxMode mode,
            Material disc,
            int durationTicks,
            float volume,
            float pitch,
            JukeboxPlayback current) {
        if (player == null)
            return;

        if (current != null) {
            stopDiscForListeners(current);
        }

        startJukebox(player, backpackId, moduleId, mode, disc, durationTicks, volume, pitch);
    }

    private Set<UUID> playDiscAround(Player emitter, org.bukkit.Sound sound, float volume, float pitch) {
        if (emitter == null || sound == null)
            return java.util.Collections.emptySet();

        double hearRadius = Math.max(0.0, plugin.getConfig().getDouble("Upgrades.Jukebox.HearRadius", 32.0));
        double hearRadiusSq = hearRadius * hearRadius;

        java.util.HashSet<UUID> listeners = new java.util.HashSet<>();

        var loc = emitter.getLocation();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p == null || !p.isOnline())
                continue;
            if (p.getWorld() == null || emitter.getWorld() == null)
                continue;
            if (!p.getWorld().equals(emitter.getWorld()))
                continue;

            if (hearRadius > 0.0) {
                if (p.getLocation().distanceSquared(loc) > hearRadiusSq)
                    continue;
            }

            listeners.add(p.getUniqueId());

            // Prefer an entity-emitter sound so it follows the player while moving.
            if (!EntitySound.tryPlay(p, emitter, sound, SoundCategory.RECORDS, volume, pitch)) {
                p.playSound(loc, sound, SoundCategory.RECORDS, volume, pitch);
            }
        }

        return java.util.Collections.unmodifiableSet(listeners);
    }

    private void stopDiscForListeners(JukeboxPlayback current) {
        if (current == null || current.sound() == null)
            return;

        Set<UUID> listeners = current.listeners();
        if (listeners == null || listeners.isEmpty())
            return;

        for (UUID id : listeners) {
            Player p = Bukkit.getPlayer(id);
            if (p == null || !p.isOnline())
                continue;
            p.stopSound(current.sound(), SoundCategory.RECORDS);
        }
    }

    private static org.bukkit.Sound discToSound(Material disc) {
        if (disc == null)
            return null;

        // The sound event key is NOT the same as the item id (ex: music_disc.13 vs
        // music_disc_13). Prefer direct registry lookup using the sound event key.
        String songId = discSongId(disc);
        if (songId != null) {
            org.bukkit.Sound s = Registry.SOUNDS.get(NamespacedKey.minecraft("music_disc." + songId));
            if (s != null)
                return s;
        }

        // Fallback: resolve via the item's jukebox-playable component.
        try {
            ItemMeta meta = new ItemStack(disc, 1).getItemMeta();
            if (meta == null || !meta.hasJukeboxPlayable())
                return null;
            JukeboxPlayableComponent playable = meta.getJukeboxPlayable();
            if (playable == null)
                return null;
            var song = playable.getSong();
            if (song == null)
                return null;
            return song.getSound();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String discSongId(Material disc) {
        if (disc == null)
            return null;
        String name = disc.name();
        if (!name.startsWith("MUSIC_DISC_"))
            return null;
        return name.substring("MUSIC_DISC_".length()).toLowerCase(Locale.ROOT);
    }

    private int discDurationTicks(Material disc) {
        int fallback = Math.max(20, plugin.getConfig().getInt("Upgrades.Jukebox.TrackDurationTicks", 2400));
        if (disc == null)
            return fallback;

        // 20 ticks = 1 second
        return switch (disc) {
            case MUSIC_DISC_13 -> 3560; // 2:58
            case MUSIC_DISC_CAT -> 3700; // 3:05
            case MUSIC_DISC_BLOCKS -> 6900; // 5:45
            case MUSIC_DISC_CHIRP -> 3700; // 3:05
            case MUSIC_DISC_FAR -> 3480; // 2:54
            case MUSIC_DISC_MALL -> 3940; // 3:17
            case MUSIC_DISC_MELLOHI -> 1920; // 1:36
            case MUSIC_DISC_STAL -> 3000; // 2:30
            case MUSIC_DISC_STRAD -> 3760; // 3:08
            case MUSIC_DISC_WARD -> 5020; // 4:11
            case MUSIC_DISC_11 -> 1420; // 1:11
            case MUSIC_DISC_WAIT -> 4740; // 3:57
            case MUSIC_DISC_PIGSTEP -> 2960; // 2:28
            case MUSIC_DISC_OTHERSIDE -> 3900; // 3:15
            case MUSIC_DISC_CREATOR -> 3520; // 2:56
            case MUSIC_DISC_CREATOR_MUSIC_BOX -> 1460; // 1:13
            case MUSIC_DISC_5 -> 3560; // 2:58
            case MUSIC_DISC_RELIC -> 4380; // 3:39
            case MUSIC_DISC_PRECIPICE -> 5980; // 4:59
            case MUSIC_DISC_TEARS -> 3500; // 2:55
            case MUSIC_DISC_LAVA_CHICKEN -> 2700; // 2:15
            default -> fallback;
        };
    }

    /**
     * Reflection wrapper for Player#playSound(Entity, Sound, SoundCategory, float,
     * float) so we can have the sound follow the player (emitter) while still
     * playing it to other nearby players.
     */
    private static final class EntitySound {
        private static volatile boolean initialized;
        private static volatile boolean available;
        private static Method playSoundEntity;

        private EntitySound() {
        }

        static boolean tryPlay(Player listener, Player emitter, org.bukkit.Sound sound, SoundCategory cat, float volume,
                float pitch) {
            if (listener == null || emitter == null || sound == null || cat == null)
                return false;
            ensureInit();
            if (!available)
                return false;
            try {
                playSoundEntity.invoke(listener, emitter, sound, cat, volume, pitch);
                return true;
            } catch (ReflectiveOperationException ex) {
                return false;
            }
        }

        private static void ensureInit() {
            if (initialized)
                return;
            synchronized (EntitySound.class) {
                if (initialized)
                    return;
                initialized = true;
                try {
                    playSoundEntity = Player.class.getMethod("playSound",
                            org.bukkit.entity.Entity.class,
                            org.bukkit.Sound.class,
                            SoundCategory.class,
                            float.class,
                            float.class);
                    available = true;
                } catch (NoSuchMethodException ex) {
                    available = false;
                }
            }
        }
    }
}
