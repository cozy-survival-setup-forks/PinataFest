package dev.pinatafest.config;

import dev.pinatafest.util.Durations;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;

/**
 * config.yml, parsed once per reload so a vote never has to touch YAML.
 */
public record Settings(Votes votes, Reminder reminder, List<Reward> rewards, List<Milestone> milestones) {

    public record Votes(boolean listen, boolean offlineEnabled, int maxQueue, Effects effects, List<Link> links) {
    }

    public record Effects(Particle particle, int count, double spreadX, double spreadY, double spreadZ,
                          double speed, Sound sound) {
    }

    public record Link(String name, String url) {
    }

    public record Reminder(boolean enabled, Duration every, Duration after) {
    }

    public record Reward(String id, double chance, String permission, boolean stop, boolean randomLine,
                         boolean ignoreVanished, boolean ignoreOffline, Set<String> services,
                         List<String> commands) {
    }

    public enum MilestoneType { TOTAL, EVERY }

    public record Milestone(String id, MilestoneType type, int votes, String permission, List<String> commands) {
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
            return new Settings(votes(), reminder(), rewards(), milestones());
        }

        private Votes votes() {
            final List<Link> links = new ArrayList<>();
            for (var raw : cfg.getMapList("votes.links")) {
                final Object name = raw.get("name");
                final Object url = raw.get("url");
                if (name != null && url != null && !url.toString().contains("<")) {
                    links.add(new Link(name.toString(), url.toString()));
                }
            }
            return new Votes(
                    cfg.getBoolean("votes.listen", true),
                    cfg.getBoolean("votes.offline.enabled", true),
                    Math.max(0, cfg.getInt("votes.offline.max_queue", 0)),
                    effects(),
                    List.copyOf(links));
        }

        private Effects effects() {
            Particle particle = null;
            final String type = cfg.getString("votes.effects.particle.type", "none");
            if (!type.equalsIgnoreCase("none")) {
                final NamespacedKey key = NamespacedKey.fromString(type.toLowerCase(Locale.ROOT));
                particle = key == null ? null : Registry.PARTICLE_TYPE.get(key);
                if (particle == null) {
                    log.warning("votes.effects.particle.type: unknown particle " + type + ", using none");
                }
            }

            final List<Double> spread = cfg.getDoubleList("votes.effects.particle.spread");
            Sound sound = null;
            if (cfg.getBoolean("votes.effects.sound.enabled", true)) {
                try {
                    sound = Sound.sound(
                            Key.key(cfg.getString("votes.effects.sound.key", "minecraft:entity.player.levelup")),
                            Sound.Source.MASTER,
                            (float) cfg.getDouble("votes.effects.sound.volume", 1.0),
                            (float) cfg.getDouble("votes.effects.sound.pitch", 1.25));
                } catch (IllegalArgumentException e) {
                    log.warning("votes.effects.sound.key is not a valid sound key, no sound will play");
                }
            }
            return new Effects(
                    particle,
                    Math.max(0, cfg.getInt("votes.effects.particle.count", 50)),
                    at(spread, 0), at(spread, 1), at(spread, 2),
                    cfg.getDouble("votes.effects.particle.speed", 0.05),
                    sound);
        }

        private static double at(List<Double> list, int index) {
            return index < list.size() ? list.get(index) : 0.0;
        }

        private Reminder reminder() {
            return new Reminder(
                    cfg.getBoolean("reminder.enabled", true),
                    duration("reminder.every", Duration.ofHours(3)),
                    duration("reminder.after", Duration.ofHours(24)));
        }

        private List<Reward> rewards() {
            final List<Reward> list = new ArrayList<>();
            final ConfigurationSection section = cfg.getConfigurationSection("rewards.vote");
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
                    log.warning("rewards.vote." + id + " has no commands, skipping it");
                    continue;
                }
                final Set<String> services = new HashSet<>();
                for (String service : entry.getStringList("services")) {
                    services.add(service.toLowerCase(Locale.ROOT));
                }
                list.add(new Reward(
                        id,
                        clamp(entry.getDouble("chance", 100.0)),
                        entry.getString("permission"),
                        entry.getBoolean("stop", false),
                        entry.getBoolean("random_line", false),
                        entry.getBoolean("ignore_vanished", false),
                        entry.getBoolean("ignore_offline", false),
                        Set.copyOf(services),
                        List.copyOf(commands)));
            }
            return List.copyOf(list);
        }

        private List<Milestone> milestones() {
            final List<Milestone> list = new ArrayList<>();
            final ConfigurationSection section = cfg.getConfigurationSection("rewards.milestones");
            if (section == null) {
                return list;
            }
            for (String id : section.getKeys(false)) {
                final ConfigurationSection entry = section.getConfigurationSection(id);
                if (entry == null) {
                    continue;
                }
                final int votes = entry.getInt("votes", 0);
                final List<String> commands = entry.getStringList("commands");
                final String type = entry.getString("type", "total").toUpperCase(Locale.ROOT);
                if (votes < 1 || commands.isEmpty() || !(type.equals("TOTAL") || type.equals("EVERY"))) {
                    log.warning("rewards.milestones." + id + " needs type total or every, votes above 0 and commands");
                    continue;
                }
                list.add(new Milestone(id, MilestoneType.valueOf(type), votes,
                        entry.getString("permission"), List.copyOf(commands)));
            }
            return List.copyOf(list);
        }

        private Duration duration(String path, Duration fallback) {
            final String raw = cfg.getString(path, "");
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

        private static double clamp(double chance) {
            return Math.max(0.0, Math.min(100.0, chance));
        }
    }
}
