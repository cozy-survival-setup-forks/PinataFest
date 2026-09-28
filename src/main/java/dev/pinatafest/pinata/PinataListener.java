package dev.pinatafest.pinata;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerLeashEntityEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * A pinata never takes real damage: every hit is cancelled and counted instead. The same class
 * keeps player visibility in step with logins, logouts and world changes.
 */
public final class PinataListener implements Listener {

    /** Marks the fireworks of a broken pinata, which are for show and never hurt anyone. */
    static final NamespacedKey FIREWORK = new NamespacedKey("pinatafest", "firework");

    private final Plugin plugin;
    private final PinataService service;

    public PinataListener(Plugin plugin, PinataService service) {
        this.plugin = plugin;
        this.service = service;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onFireworkDamage(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Firework firework
                && firework.getPersistentDataContainer().has(FIREWORK, PersistentDataType.BYTE)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDamage(EntityDamageEvent event) {
        final Pinata pinata = service.pinataOf(event.getEntity());
        if (pinata == null) {
            return;
        }
        event.setCancelled(true);

        if (event instanceof EntityDamageByEntityEvent byEntity) {
            final Player attacker = attacker(byEntity);
            if (attacker != null) {
                // a thrown or shot hit has no tool in hand: it cannot satisfy a required item
                final ItemStack held = byEntity.getDamager() instanceof Player
                        ? attacker.getInventory().getItemInMainHand() : ItemStack.empty();
                service.hit(pinata, attacker, held);
            }
        }
    }

    /** No riding it, no opening its inventory. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEntityEvent event) {
        if (service.pinataOf(event.getRightClicked()) != null) {
            event.setCancelled(true);
        }
    }

    /** No leashing it away from everyone else. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onLeash(PlayerLeashEntityEvent event) {
        if (service.pinataOf(event.getEntity()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        service.visibility().load(event.getPlayer());
        Bukkit.getScheduler().runTask(plugin, service.visibility()::refresh);
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Bukkit.getScheduler().runTask(plugin, service.visibility()::refresh);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        service.visibility().forget(event.getPlayer());
    }

    private static Player attacker(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player) {
            return player;
        }
        if (event.getDamager() instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter) {
            return shooter;
        }
        return null;
    }
}
