package dev.pinatafest.message;

import dev.pinatafest.util.Legacy;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Loads lang.yml and sends its messages as chat, action bar, title and sound. A message is parsed
 * when it is sent, so placeholders such as {@code <player>} are just tag resolvers.
 */
public final class Messages {

    private record Entry(boolean enabled, boolean chat, boolean actionBar, boolean title, String chatText,
                         String actionBarText, String titleText, String subtitleText, Title.Times times,
                         Sound sound) {
    }

    private final JavaPlugin plugin;
    private final MiniMessage mini = MiniMessage.miniMessage();
    private final Map<String, Entry> entries = new HashMap<>();

    public Messages(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * @return false if lang.yml has a mistake in it. On a reload the messages already loaded are kept; on the
     * first load there are none, so the bundled texts are used.
     */
    public boolean load() {
        final File file = new File(plugin.getDataFolder(), "lang.yml");
        final YamlConfiguration bundled = bundled();
        YamlConfiguration lang = parse(file);
        if (lang != null && lang.getInt("lang_version", 0) < bundled.getInt("lang_version", 0)) {
            final File old = new File(plugin.getDataFolder(), "lang.yml.old");
            old.delete();
            if (file.renameTo(old)) {
                plugin.getLogger().warning("lang.yml was from an older version, so it was saved as lang.yml.old and"
                        + " replaced. Copy any changes you made across.");
            }
        }
        if (!file.exists()) {
            plugin.saveResource("lang.yml", false);
            lang = parse(file);
        }
        boolean ok = true;
        if (lang == null) {
            plugin.getLogger().severe("lang.yml has a mistake in it, "
                    + (entries.isEmpty() ? "using the bundled texts" : "keeping the messages already loaded") + ".");
            if (!entries.isEmpty()) {
                return false;
            }
            lang = new YamlConfiguration();
            ok = false;
        }
        lang.setDefaults(bundled);

        // keys missing from the file on disk fall back to the bundled ones, so messages added in
        // newer versions still work with an older lang.yml
        final Set<String> keys = new LinkedHashSet<>(lang.getKeys(false));
        if (lang.getDefaults() != null) {
            keys.addAll(lang.getDefaults().getKeys(false));
        }
        entries.clear();
        for (String key : keys) {
            ConfigurationSection section = lang.getConfigurationSection(key);
            if (section == null && lang.getDefaults() != null) {
                section = lang.getDefaults().getConfigurationSection(key);
            }
            if (section != null) {
                entries.put(key, readEntry(key, section));
            }
        }
        return ok;
    }

    /** lang.yml parsed strictly: a file with a mistake is null, not an empty file. */
    private YamlConfiguration parse(File file) {
        if (!file.exists()) {
            return null;
        }
        final YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
            return yaml;
        } catch (IOException | InvalidConfigurationException e) {
            plugin.getLogger().severe("lang.yml could not be read: " + e.getMessage());
            return null;
        }
    }

    /**
     * The text under a key. The one-argument getString falls back to the bundled lang.yml, the two-argument one
     * never does, so a key an older file lacks still shows something.
     */
    private static String text(ConfigurationSection section, String key) {
        final String value = section.getString(key);
        return value == null ? "" : value;
    }

    private YamlConfiguration bundled() {
        try (Reader reader = new InputStreamReader(plugin.getResource("lang.yml"), StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not read the bundled lang.yml: " + e.getMessage());
            return new YamlConfiguration();
        }
    }

    private Entry readEntry(String key, ConfigurationSection section) {
        final List<String> types = types(section.get("type"));
        final List<Integer> times = section.getIntegerList("times");
        return new Entry(
                section.getBoolean("enabled", true),
                types.contains("chat"),
                types.contains("actionbar"),
                types.contains("title"),
                Legacy.toMiniMessage(text(section, "chat")),
                Legacy.toMiniMessage(text(section, "actionbar")),
                Legacy.toMiniMessage(text(section, "title")),
                Legacy.toMiniMessage(text(section, "subtitle")),
                Title.Times.times(ticks(times, 0, 10), ticks(times, 1, 50), ticks(times, 2, 10)),
                sound(key, section.getConfigurationSection("sound")));
    }

    private static Duration ticks(List<Integer> times, int index, int fallback) {
        return Duration.ofMillis(50L * (index < times.size() ? times.get(index) : fallback));
    }

    private Sound sound(String key, ConfigurationSection section) {
        if (section == null || !section.getBoolean("enabled", true)) {
            return null;
        }
        try {
            return Sound.sound(
                    Key.key(section.getString("key", "minecraft:block.note_block.pling")),
                    Sound.Source.valueOf(section.getString("source", "MASTER").toUpperCase(Locale.ROOT)),
                    (float) section.getDouble("volume", 1.0),
                    (float) section.getDouble("pitch", 1.0));
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("lang.yml: bad sound for '" + key + "', it will play no sound");
            return null;
        }
    }

    /** Accepts both {@code [chat, actionbar]} and the comma separated form {@code chat, actionbar}. */
    private static List<String> types(Object raw) {
        final String joined = raw instanceof List<?> list
                ? String.join(",", list.stream().map(String::valueOf).toList())
                : String.valueOf(raw);
        return Arrays.stream(joined.toLowerCase(Locale.ROOT).split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    public static TagResolver text(String name, Object value) {
        return Placeholder.unparsed(name, String.valueOf(value));
    }

    /** The chat text of a message, for things like boss bars. Empty if the message is missing. */
    public Component chat(String key, TagResolver... resolvers) {
        final Entry entry = entries.get(key);
        if (entry == null || !entry.enabled()) {
            return Component.empty();
        }
        return mini.deserialize(entry.chatText(), TagResolver.resolver(resolvers));
    }

    public void send(CommandSender to, String key, TagResolver... resolvers) {
        final Entry entry = entries.get(key);
        if (entry == null || !entry.enabled()) {
            return;
        }
        final Parsed parsed = parse(entry, resolvers);
        if (to instanceof Player player) {
            deliver(player, entry, parsed);
        } else if (parsed.chat() != null) {
            to.sendMessage(parsed.chat());
        }
    }

    /** Sends to everyone online, and chat also to the console. */
    public void broadcast(String key, TagResolver... resolvers) {
        broadcast(Bukkit.getOnlinePlayers(), key, resolvers);
    }

    public void broadcast(Collection<? extends Player> to, String key, TagResolver... resolvers) {
        final Entry entry = entries.get(key);
        if (entry == null || !entry.enabled()) {
            return;
        }
        final Parsed parsed = parse(entry, resolvers);
        to.forEach(player -> deliver(player, entry, parsed));
        if (parsed.chat() != null) {
            Bukkit.getConsoleSender().sendMessage(parsed.chat());
        }
    }

    private record Parsed(Component chat, Component bar, Title title) {
    }

    private Parsed parse(Entry entry, TagResolver... resolvers) {
        final TagResolver all = TagResolver.resolver(resolvers);
        return new Parsed(
                entry.chat() && !entry.chatText().isBlank() ? mini.deserialize(entry.chatText(), all) : null,
                entry.actionBar() && !entry.actionBarText().isBlank() ? mini.deserialize(entry.actionBarText(), all) : null,
                entry.title() && !(entry.titleText().isBlank() && entry.subtitleText().isBlank()) ? Title.title(mini.deserialize(entry.titleText(), all),
                        mini.deserialize(entry.subtitleText(), all), entry.times()) : null);
    }

    private void deliver(Player player, Entry entry, Parsed parsed) {
        if (parsed.chat() != null) {
            player.sendMessage(parsed.chat());
        }
        if (parsed.bar() != null) {
            player.sendActionBar(parsed.bar());
        }
        if (parsed.title() != null) {
            player.showTitle(parsed.title());
        }
        if (entry.sound() != null) {
            player.playSound(entry.sound());
        }
    }
}
