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
import java.util.function.Supplier;

/**
 * Lets players hide everyone else while a pinata is out, which keeps busy crowds playable on
 * weaker computers. The choice is saved on the player, and only visibility changes made here are
 * ever undone here.
 */
public final class PartyVisibility {

    private final Plugin plugin;
    private final Supplier<Settings> settings;
    private final Supplier<Set<UUID>> activeWorlds;
    private final NamespacedKey hideKey;
    /** viewer -> the players currently hidden from them by this class */
    private final Map<UUID, Set<UUID>> hidden = new HashMap<>();

    PartyVisibility(Plugin plugin, Supplier<Settings> settings, Supplier<Set<UUID>> activeWorlds) {
        this.plugin = plugin;
        this.settings = settings;
        this.activeWorlds = activeWorlds;
        this.hideKey = new NamespacedKey(plugin, "hide_players");
    }

    public boolean hides(Player player) {
        return player.getPersistentDataContainer().has(hideKey, PersistentDataType.BYTE);
    }

    /** Saves the choice and applies it straight away. */
    public void set(Player player, boolean hide) {
        if (hide) {
            player.getPersistentDataContainer().set(hideKey, PersistentDataType.BYTE, (byte) 1);
        } else {
            player.getPersistentDataContainer().remove(hideKey);
        }
        refresh();
    }

    /** Brings everyone's visibility in line with the pinatas that are out right now. */
    public void refresh() {
        final Settings.Visibility config = settings.get().visibility();
        final Set<UUID> worlds = config.enabled() ? activeWorlds.get() : Set.of();

        for (Player viewer : Bukkit.getOnlinePlayers()) {
            final Set<UUID> wanted = new HashSet<>();
            if (hides(viewer) && worlds.contains(viewer.getWorld().getUID()) && inConfiguredWorld(viewer, config)) {
                for (Player target : viewer.getWorld().getPlayers()) {
                    if (!target.getUniqueId().equals(viewer.getUniqueId())) {
                        wanted.add(target.getUniqueId());
                    }
                }
            }
            apply(viewer, wanted);
        }
        // viewers who logged off no longer matter
        hidden.keySet().removeIf(id -> Bukkit.getPlayer(id) == null);
    }

    /** Puts everybody back, for shutdown and reload. */
    public void restoreAll() {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            apply(viewer, Set.of());
        }
        hidden.clear();
    }

    public void forget(Player player) {
        hidden.remove(player.getUniqueId());
        hidden.values().forEach(targets -> targets.remove(player.getUniqueId()));
    }

    public boolean anyHidden() {
        return hidden.values().stream().anyMatch(targets -> !targets.isEmpty());
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
