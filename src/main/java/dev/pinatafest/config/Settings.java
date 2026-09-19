package dev.pinatafest.config;

import dev.pinatafest.spawn.SpawnPoint;
import dev.pinatafest.util.Durations;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * config.yml, parsed once per reload so votes and hits never have to touch YAML.
 */
public record Settings(Votes votes, Party party, PinataSettings pinata, Visibility visibility,
                       RewardSets rewards) {

    public record Votes(boolean listen, boolean queueRewards, int maxQueue, Effects effects) {
    }

    public record Effects(Particle particle, int count, double spreadX, double spreadY, double spreadZ,
                          double speed, Sound sound) {
    }

    /** What starts a pinata party: enough votes, and where it happens. */
    public record Party(int votesNeeded, int amount, List<String> locations, Map<String, SpawnPoint> spots,
                        Countdown countdown) {
    }

    /** A null colour means the bar cycles through all of them. */
    public record Countdown(boolean enabled, int seconds, boolean global, BossBar.Color color,
                            BossBar.Overlay overlay, Sound start, Sound tick, Sound end) {
    }

    public record PinataSettings(Look look, Health health, Duration lifeSpan, Movement movement, Hit hit,
                                 Abilities abilities, Bar bar, int fireworks, boolean rewardEveryone,
                                 Sound hitSound) {
    }

    public record Look(String llamaColor, String carpet, boolean chest, boolean glow, String glowColor,
                       Particle particle, int particleCount, int animationTicks) {
    }

    public record Health(int base, int perPlayer, int max) {
    }

    public record Movement(boolean enabled, int interval, double range, double speed, double fleeRadius) {
    }

    public record Hit(double cooldownSeconds, String permission, Set<Material> items, Duration recentVoters) {
    }

    public record Bar(boolean enabled, boolean global, BossBar.Color color, BossBar.Overlay overlay) {
    }

    /** Hiding other players from those who asked for it while a pinata is out. */
    public record Visibility(boolean enabled, String world) {
    }

    public record Abilities(Teleport teleport, Knockback knockback, ShootUp shootUp, Baby baby, SpeedUp speedUp) {
    }

    public record Teleport(boolean enabled, double chance, double radius, double maxY, Sound sound) {
    }

    public record Knockback(boolean enabled, double chance, double radius, double force, Sound sound) {
    }

    public record ShootUp(boolean enabled, double chance, double force, Sound sound) {
    }

    public record Baby(boolean enabled, double chance, double minSeconds, double maxSeconds, Sound inSound,
                       Sound outSound) {
    }

    public record SpeedUp(boolean enabled, double chance, int level, double radius, double minSeconds,
                          double maxSeconds, Sound sound) {
    }

    public record Reward(String id, double chance, String permission, boolean stop, boolean randomLine,
                         boolean once, boolean ignoreVanished, boolean ignoreOffline, Set<String> services,
                         List<String> commands) {
    }

    public record RewardSets(List<Reward> vote, List<Reward> hit, List<Reward> lastHit, List<Reward> die) {
    }

    public static Settings load(FileConfiguration cfg, Logger log) {
        return new Loader(cfg, log).load();
    }

    private static final class Loader {
        private final FileConfiguration cfg;
        private final Logger log;

        Loader(FileConfiguration cfg, Logger log) {
            this.cfg = cfg;
            this.log = log;
        }

        Settings load() {
            return new Settings(votes(), party(), pinata(),
                    new Visibility(cfg.getBoolean("visibility.enabled", true), cfg.getString("visibility.world", "")),
                    new RewardSets(rewards("rewards.vote"), rewards("rewards.hit"),
                            rewards("rewards.last_hit"), rewards("rewards.die")));
        }

        // ---- votes ----

        private Votes votes() {
            final List<Double> spread = cfg.getDoubleList("votes.effects.particle.spread");
            final Effects effects = new Effects(
                    particle("votes.effects.particle.type", "happy_villager"),
                    Math.max(0, cfg.getInt("votes.effects.particle.count", 50)),
                    at(spread, 0), at(spread, 1), at(spread, 2),
                    cfg.getDouble("votes.effects.particle.speed", 0.05),
                    sound("votes.effects.sound"));
            return new Votes(
                    cfg.getBoolean("votes.listen", true),
                    cfg.getBoolean("votes.offline.queue_rewards", true),
                    Math.max(0, cfg.getInt("votes.offline.max_queue", 0)),
                    effects);
        }

        // ---- party ----

        private Party party() {
            final Map<String, SpawnPoint> spots = new HashMap<>();
            final ConfigurationSection section = cfg.getConfigurationSection("pinata.locations");
            if (section != null) {
                for (String name : section.getKeys(false)) {
                    final ConfigurationSection entry = section.getConfigurationSection(name);
                    final SpawnPoint point = entry == null ? null : SpawnPoint.read(entry);
                    if (point == null) {
                        log.warning("pinata.locations." + name + " is missing a world or coordinates, skipping it");
                    } else {
                        spots.put(name.toLowerCase(Locale.ROOT), point);
                    }
                }
            }

            final List<String> names = new ArrayList<>();
            for (String name : cfg.getStringList("pinata.auto_summon.locations")) {
                names.add(name.toLowerCase(Locale.ROOT));
            }
            return new Party(
                    Math.max(0, cfg.getInt("pinata.auto_summon.votes_needed", 20)),
                    Math.max(1, cfg.getInt("pinata.auto_summon.amount", 1)),
                    List.copyOf(names), Map.copyOf(spots), countdown());
        }

        private Countdown countdown() {
            return new Countdown(
                    cfg.getBoolean("pinata.countdown.enabled", true),
                    Math.max(1, cfg.getInt("pinata.countdown.seconds", 5)),
                    cfg.getBoolean("pinata.countdown.global", true),
                    barColor(cfg.getString("pinata.countdown.color", "rainbow")),
                    overlay(cfg.getString("pinata.countdown.style", "notched_20")),
                    sound("pinata.countdown.sounds.start"),
                    sound("pinata.countdown.sounds.tick"),
                    sound("pinata.countdown.sounds.end"));
        }

        // ---- pinata ----

        private PinataSettings pinata() {
            final Look look = new Look(
                    cfg.getString("pinata.look.llama_color", "random"),
                    cfg.getString("pinata.look.carpet", "cycle"),
                    cfg.getBoolean("pinata.look.chest", false),
                    cfg.getBoolean("pinata.look.glow.enabled", true),
                    cfg.getString("pinata.look.glow.color", "cycle"),
                    particle("pinata.look.particles.type", "firework"),
                    Math.max(0, cfg.getInt("pinata.look.particles.count", 2)),
                    Math.max(0, cfg.getInt("pinata.look.animation_ticks", 10)));
            final Health health = new Health(
                    Math.max(1, cfg.getInt("pinata.health.base", 5)),
                    Math.max(0, cfg.getInt("pinata.health.per_player", 1)),
                    Math.max(0, cfg.getInt("pinata.health.max", 0)));
            final Movement movement = new Movement(
                    cfg.getBoolean("pinata.movement.enabled", true),
                    Math.max(5, cfg.getInt("pinata.movement.interval", 20)),
                    cfg.getDouble("pinata.movement.range", 12.0),
                    cfg.getDouble("pinata.movement.speed", 1.4),
                    cfg.getDouble("pinata.movement.flee_radius", 6.0));
            final Hit hit = new Hit(
                    Math.max(0.0, cfg.getDouble("pinata.hit.cooldown", 0.85)),
                    cfg.getString("pinata.hit.permission", ""),
                    materials("pinata.hit.items"),
                    duration("pinata.hit.recent_voters", Duration.ZERO));
            final Bar bar = new Bar(
                    cfg.getBoolean("pinata.bar.enabled", true),
                    cfg.getBoolean("pinata.bar.global", true),
                    barColor(cfg.getString("pinata.bar.color", "rainbow")),
                    overlay(cfg.getString("pinata.bar.style", "notched_20")));
            return new PinataSettings(look, health, duration("pinata.life_span", Duration.ofMinutes(5)), movement,
                    hit, abilities(), bar, Math.max(0, cfg.getInt("pinata.fireworks_on_death", 10)),
                    cfg.getBoolean("pinata.reward_everyone", false), sound("pinata.hit_sound"));
        }

        private Abilities abilities() {
            final String p = "pinata.abilities.";
            return new Abilities(
                    new Teleport(cfg.getBoolean(p + "teleport.enabled", true), cfg.getDouble(p + "teleport.chance", 12.0),
                            cfg.getDouble(p + "teleport.radius", 8.0), cfg.getDouble(p + "teleport.max_y", 6.0),
                            sound(p + "teleport.sound")),
                    new Knockback(cfg.getBoolean(p + "knockback.enabled", true), cfg.getDouble(p + "knockback.chance", 12.0),
                            cfg.getDouble(p + "knockback.radius", 6.0), cfg.getDouble(p + "knockback.force", 1.35),
                            sound(p + "knockback.sound")),
                    new ShootUp(cfg.getBoolean(p + "shoot_up.enabled", true), cfg.getDouble(p + "shoot_up.chance", 8.0),
                            cfg.getDouble(p + "shoot_up.force", 1.5), sound(p + "shoot_up.sound")),
                    new Baby(cfg.getBoolean(p + "baby.enabled", true), cfg.getDouble(p + "baby.chance", 8.0),
                            cfg.getDouble(p + "baby.min_seconds", 2.0), cfg.getDouble(p + "baby.max_seconds", 8.0),
                            sound(p + "baby.in_sound"), sound(p + "baby.out_sound")),
                    new SpeedUp(cfg.getBoolean(p + "speed_up.enabled", true), cfg.getDouble(p + "speed_up.chance", 6.0),
                            Math.max(1, cfg.getInt(p + "speed_up.level", 3)), cfg.getDouble(p + "speed_up.radius", 12.0),
                            cfg.getDouble(p + "speed_up.min_seconds", 2.5), cfg.getDouble(p + "speed_up.max_seconds", 5.0),
                            sound(p + "speed_up.sound")));
        }

        // ---- rewards ----

        private List<Reward> rewards(String path) {
            final List<Reward> list = new ArrayList<>();
            final ConfigurationSection section = cfg.getConfigurationSection(path);
            if (section == null) {
                return list;
            }
            for (String id : section.getKeys(false)) {
                final ConfigurationSection entry = section.getConfigurationSection(id);
                if (entry == null) {
                    continue;
                }
                final List<String> commands = entry.getStringList("commands");
                if (commands.isEmpty()) {
                    log.warning(path + "." + id + " has no commands, skipping it");
                    continue;
                }
                final Set<String> services = new HashSet<>();
                for (String service : entry.getStringList("services")) {
                    services.add(service.toLowerCase(Locale.ROOT));
                }
                list.add(new Reward(id,
                        Math.max(0.0, Math.min(100.0, entry.getDouble("chance", 100.0))),
                        entry.getString("permission"),
                        entry.getBoolean("stop", false),
                        entry.getBoolean("random_line", false),
                        entry.getBoolean("once", false),
                        entry.getBoolean("ignore_vanished", false),
                        entry.getBoolean("ignore_offline", false),
                        Set.copyOf(services), List.copyOf(commands)));
            }
            return List.copyOf(list);
        }

        // ---- helpers ----

        private static double at(List<Double> list, int index) {
            return index < list.size() ? list.get(index) : 0.0;
        }

        private Particle particle(String path, String fallback) {
            final String type = cfg.getString(path, fallback);
            if (type.equalsIgnoreCase("none")) {
                return null;
            }
            final NamespacedKey key = NamespacedKey.fromString(type.toLowerCase(Locale.ROOT));
            final Particle particle = key == null ? null : Registry.PARTICLE_TYPE.get(key);
            if (particle == null) {
                log.warning(path + ": unknown particle " + type + ", using none");
            }
            return particle;
        }

        /** A section with key, volume and pitch; null when it is missing or the key is none. */
        private Sound sound(String path) {
            final ConfigurationSection section = cfg.getConfigurationSection(path);
            if (section == null) {
                return null;
            }
            final String key = section.getString("key", "none");
            if (key.isBlank() || key.equalsIgnoreCase("none")) {
                return null;
            }
            try {
                return Sound.sound(Key.key(key), Sound.Source.MASTER,
                        (float) section.getDouble("volume", 1.0), (float) section.getDouble("pitch", 1.0));
            } catch (IllegalArgumentException e) {
                log.warning(path + ".key is not a valid sound key, no sound will play");
                return null;
            }
        }

        private Set<Material> materials(String path) {
            final Set<Material> found = EnumSet.noneOf(Material.class);
            for (String name : cfg.getStringList(path)) {
                final Material material = Material.matchMaterial(name);
                if (material == null) {
                    log.warning(path + ": unknown material " + name + ", skipping it");
                } else {
                    found.add(material);
                }
            }
            return found;
        }

        private BossBar.Color barColor(String name) {
            if (name.equalsIgnoreCase("rainbow")) {
                return null;
            }
            try {
                return BossBar.Color.valueOf(name.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                log.warning("Unknown boss bar colour " + name + ", using pink");
                return BossBar.Color.PINK;
            }
        }

        private BossBar.Overlay overlay(String name) {
            try {
                return BossBar.Overlay.valueOf(name.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                log.warning("Unknown boss bar style " + name + ", using notched_20");
                return BossBar.Overlay.NOTCHED_20;
            }
        }

        private Duration duration(String path, Duration fallback) {
            final String raw = String.valueOf(cfg.get(path, ""));
            if (raw.isBlank()) {
                return fallback;
            }
            try {
                return Durations.parse(raw);
            } catch (IllegalArgumentException e) {
                log.warning(path + ": '" + raw + "' is not a duration, using " + fallback.toSeconds() + "s");
                return fallback;
            }
        }
    }
}
