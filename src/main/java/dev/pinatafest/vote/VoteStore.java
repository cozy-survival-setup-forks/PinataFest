package dev.pinatafest.vote;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Vote totals, last vote times and the queue of votes waiting for offline players. Votes only
 * carry a name, so everything is kept by lower-case name. Reads and writes happen on the main
 * thread; only the file write itself is done elsewhere.
 */
public final class VoteStore {

    public record Queued(String service, long time) {
    }

    public static final class Entry {
        private int total;
        private long lastVote;
        private final List<Queued> queue = new ArrayList<>();

        public int total() {
            return total;
        }

        public long lastVote() {
            return lastVote;
        }

        public List<Queued> queue() {
            return queue;
        }

        public void addVote() {
            total++;
        }

        public void setLastVote(long time) {
            lastVote = time;
        }
    }

    private final Path file;
    private final Map<String, Entry> players = new HashMap<>();
    private boolean dirty;

    public VoteStore(Path file) {
        this.file = file;
    }

    public void load(Logger log) {
        if (!Files.exists(file)) {
            return;
        }
        final YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file.toFile());
        final ConfigurationSection section = yaml.getConfigurationSection("players");
        if (section == null) {
            return;
        }
        for (String name : section.getKeys(false)) {
            final ConfigurationSection saved = section.getConfigurationSection(name);
            if (saved == null) {
                continue;
            }
            final Entry entry = new Entry();
            entry.total = saved.getInt("total");
            entry.lastVote = saved.getLong("last_vote");
            for (String line : saved.getStringList("queue")) {
                final int split = line.lastIndexOf('|');
                try {
                    entry.queue.add(new Queued(line.substring(0, split), Long.parseLong(line.substring(split + 1))));
                } catch (RuntimeException e) {
                    log.warning("votes.yml: skipping a queued vote for " + name + " that could not be read");
                }
            }
            players.put(name, entry);
        }
    }

    /** The entry for a player, created if they have never voted. */
    public Entry entry(String name) {
        dirty = true;
        return players.computeIfAbsent(key(name), k -> new Entry());
    }

    /** The entry for a player, or null if they have never voted. Does not change anything. */
    public Entry find(String name) {
        return players.get(key(name));
    }

    public void markDirty() {
        dirty = true;
    }

    public boolean isDirty() {
        return dirty;
    }

    /** The current contents as YAML, and marks them as saved. Call on the main thread. */
    public String snapshot() {
        final YamlConfiguration yaml = new YamlConfiguration();
        players.forEach((name, entry) -> {
            final String base = "players." + name + ".";
            yaml.set(base + "total", entry.total);
            yaml.set(base + "last_vote", entry.lastVote);
            if (!entry.queue.isEmpty()) {
                yaml.set(base + "queue", entry.queue.stream().map(q -> q.service() + "|" + q.time()).toList());
            }
        });
        dirty = false;
        return yaml.saveToString();
    }

    /** Writes a snapshot through a temporary file so a crash never leaves half a file behind. */
    public void write(String snapshot) throws IOException {
        Files.createDirectories(file.getParent());
        final Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, snapshot, StandardCharsets.UTF_8);
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
