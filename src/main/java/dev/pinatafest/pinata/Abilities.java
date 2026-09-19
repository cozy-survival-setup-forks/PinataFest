package dev.pinatafest.pinata;

import dev.pinatafest.config.Settings;
import dev.pinatafest.message.Messages;
import net.kyori.adventure.sound.Sound;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Llama;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.concurrent.ThreadLocalRandom;

/**
 * The tricks a pinata can pull after being hit, to make it harder to catch. At most one goes off
 * per hit, tried in a fixed order.
 */
final class Abilities {

    private final Settings.Abilities settings;
    private final Messages messages;

    Abilities(Settings.Abilities settings, Messages messages) {
        this.settings = settings;
        this.messages = messages;
    }

    void roll(Pinata pinata) {
        final ThreadLocalRandom random = ThreadLocalRandom.current();
        if (settings.teleport().enabled() && chance(random, settings.teleport().chance()) && teleport(pinata)) {
            return;
        }
        if (settings.knockback().enabled() && chance(random, settings.knockback().chance())) {
            knockback(pinata);
        } else if (settings.shootUp().enabled() && chance(random, settings.shootUp().chance())) {
            shootUp(pinata);
        } else if (settings.baby().enabled() && chance(random, settings.baby().chance())) {
            baby(pinata, random);
        } else if (settings.speedUp().enabled() && chance(random, settings.speedUp().chance())) {
            speedUp(pinata, random);
        }
    }

    private static boolean chance(ThreadLocalRandom random, double percent) {
        return random.nextDouble() * 100.0 < percent;
    }

    private boolean teleport(Pinata pinata) {
        final Settings.Teleport tp = settings.teleport();
        final Llama llama = pinata.entity();
        final Location from = llama.getLocation();
        final World world = from.getWorld();
        final ThreadLocalRandom random = ThreadLocalRandom.current();

        for (int attempt = 0; attempt < 10; attempt++) {
            final double angle = random.nextDouble(Math.PI * 2);
            final double distance = 2 + random.nextDouble() * Math.max(0, tp.radius() - 2);
            final double x = from.getX() + Math.cos(angle) * distance;
            final double z = from.getZ() + Math.sin(angle) * distance;
            final int y = world.getHighestBlockYAt((int) Math.floor(x), (int) Math.floor(z)) + 1;
            if (Math.abs(y - from.getY()) > tp.maxY()
                    || world.getBlockAt((int) Math.floor(x), y - 1, (int) Math.floor(z)).isLiquid()) {
                continue;
            }

            final Location to = new Location(world, x, y, z, from.getYaw(), from.getPitch());
            play(world, from, tp.sound());
            llama.teleport(to);
            play(world, to, tp.sound());
            messages.broadcast("pinata_teleport", Messages.text("x", to.getBlockX()), Messages.text("y", to.getBlockY()),
                    Messages.text("z", to.getBlockZ()));
            return true;
        }
        return false;
    }

    private void knockback(Pinata pinata) {
        final Settings.Knockback kb = settings.knockback();
        final Location center = pinata.location();
        play(center.getWorld(), center, kb.sound());
        for (Player player : center.getWorld().getNearbyPlayers(center, kb.radius())) {
            final Vector push = player.getLocation().toVector().subtract(center.toVector()).setY(0);
            if (push.lengthSquared() < 0.01) {
                push.setX(1);
            }
            player.setVelocity(push.normalize().multiply(kb.force()).setY(0.45));
        }
    }

    private void shootUp(Pinata pinata) {
        final Settings.ShootUp up = settings.shootUp();
        final Location at = pinata.location();
        pinata.entity().setVelocity(new Vector(0, up.force(), 0));
        play(at.getWorld(), at, up.sound());
    }

    private void baby(Pinata pinata, ThreadLocalRandom random) {
        final Settings.Baby baby = settings.baby();
        final double seconds = between(random, baby.minSeconds(), baby.maxSeconds());
        pinata.makeBaby(pinata.age() + (int) (seconds * 20));
        play(pinata.location().getWorld(), pinata.location(), baby.inSound());
    }

    private void speedUp(Pinata pinata, ThreadLocalRandom random) {
        final Settings.SpeedUp speed = settings.speedUp();
        final int ticks = (int) (between(random, speed.minSeconds(), speed.maxSeconds()) * 20);
        final Location center = pinata.location();
        play(center.getWorld(), center, speed.sound());
        for (Player player : center.getWorld().getNearbyPlayers(center, speed.radius())) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, ticks, speed.level() - 1));
            player.addPotionEffect(new PotionEffect(PotionEffectType.DOLPHINS_GRACE, ticks, speed.level() - 1));
        }
    }

    /** Called by the pinata when its baby form runs out. */
    void grewUp(Pinata pinata) {
        play(pinata.location().getWorld(), pinata.location(), settings.baby().outSound());
    }

    private static double between(ThreadLocalRandom random, double min, double max) {
        return max <= min ? min : min + random.nextDouble() * (max - min);
    }

    private static void play(World world, Location at, Sound sound) {
        if (sound != null) {
            world.playSound(sound, at.getX(), at.getY(), at.getZ());
        }
    }
}
