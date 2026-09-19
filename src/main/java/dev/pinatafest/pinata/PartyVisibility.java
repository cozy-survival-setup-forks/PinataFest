package dev.pinatafest.pinata;

import dev.pinatafest.config.Settings;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Lets players hide everyone else while a pinata is out, which keeps busy crowds playable on
 * weaker computers. The choice is saved on the player and cached in memory while they are online.
 * Only visibility changes made here are ever undone here.
 */
public final class PartyVisibility {

    private final Plugin plugin;
    private final Supplier<Settings> settings;
    private final Supplier<Set<UUID>> activeWorlds;
    private final NamespacedKey hideKey;
    private final Set<UUID> hiders = ConcurrentHashMap.newKeySet();
    /** viewer -> the players currently hidden from them by this class */
    private final Map<UUID, Set<UUID>> hidden = new HashMap<>();

    private Set<UUID> lastWorlds = Set.of();
    private boolean dirty;

    PartyVisibility(Plugin plugin, Supplier<Settings> settings, Supplier<Set<UUID>> activeWorlds) {
        this.plugin = plugin;
        this.settings = settings;
        this.activeWorlds = activeWorlds;
        this.hideKey = new NamespacedKey(plugin, "hide_players");
        Bukkit.getOnlinePlayers().forEach(this::load);
    }

    public boolean hides(Player player) {
        return hiders.contains(player.getUniqueId());
    }

    /** Reads a player's saved choice into the cache. Call when they join. */
    public void load(Player player) {
        if (player.getPersistentDataContainer().has(hideKey, PersistentDataType.BYTE)) {
            hiders.add(player.getUniqueId());
        } else {
            hiders.remove(player.getUniqueId());
        }
        dirty = true;
    }

    /** Saves the choice and applies it straight away. */
    public void set(Player player, boolean hide) {
        if (hide) {
            hiders.add(player.getUniqueId());
            player.getPersistentDataContainer().set(hideKey, PersistentDataType.BYTE, (byte) 1);
        } else {
            hiders.remove(player.getUniqueId());
            player.getPersistentDataContainer().remove(hideKey);
        }
        refresh();
    }

    /** Something changed that could affect who sees whom; the next check will look at it. */
    public void markDirty() {
        dirty = true;
    }

    /** Called about once a second. Does real work only if a pinata came or went, or something was marked. */
    public void check() {
        if (dirty || !activeWorlds.get().equals(lastWorlds)) {
            refresh();
        }
    }

    /** Brings everyone's visibility in line with the pinatas that are out right now. */
    public void refresh() {
        dirty = false;
        final Settings.Visibility config = settings.get().visibility();
        final Set<UUID> worlds = config.enabled() ? activeWorlds.get() : Set.of();
        lastWorlds = Set.copyOf(activeWorlds.get());

        // only players who chose to hide need looking at, everyone else is left alone
        for (UUID id : hiders) {
            final Player viewer = Bukkit.getPlayer(id);
            if (viewer == null) {
                continue;
            }
            final Set<UUID> wanted = new HashSet<>();
            if (worlds.contains(viewer.getWorld().getUID()) && inConfiguredWorld(viewer, config)) {
                for (Player target : viewer.getWorld().getPlayers()) {
                    if (!target.getUniqueId().equals(viewer.getUniqueId())) {
                        wanted.add(target.getUniqueId());
                    }
                }
            }
            apply(viewer, wanted);
        }

        // viewers who stopped hiding, or logged off, have their leftovers cleared
        for (UUID id : new HashSet<>(hidden.keySet())) {
            if (hiders.contains(id) && Bukkit.getPlayer(id) != null) {
                continue;
            }
            final Player viewer = Bukkit.getPlayer(id);
            if (viewer != null) {
                apply(viewer, Set.of());
            }
            hidden.remove(id);
        }
    }

    /** Puts everybody back, for shutdown. */
    public void restoreAll() {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            apply(viewer, Set.of());
        }
        hidden.clear();
    }

    public void forget(Player player) {
        hiders.remove(player.getUniqueId());
        hidden.remove(player.getUniqueId());
        hidden.values().forEach(targets -> targets.remove(player.getUniqueId()));
    }

    private void apply(Player viewer, Set<UUID> wanted) {
        final Set<UUID> current = hidden.computeIfAbsent(viewer.getUniqueId(), id -> new HashSet<>());
        for (UUID id : new HashSet<>(current)) {
            if (!wanted.contains(id)) {
                final Player target = Bukkit.getPlayer(id);
                if (target != null) {
                    viewer.showPlayer(plugin, target);
                }
                current.remove(id);
            }
        }
        for (UUID id : wanted) {
            if (current.add(id)) {
                final Player target = Bukkit.getPlayer(id);
                if (target != null) {
                    viewer.hidePlayer(plugin, target);
                }
            }
        }
    }

    private static boolean inConfiguredWorld(Player player, Settings.Visibility config) {
        return config.world().isBlank() || player.getWorld().getName().equalsIgnoreCase(config.world());
    }
}
