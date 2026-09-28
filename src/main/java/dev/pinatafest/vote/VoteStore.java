package dev.pinatafest.vote;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Vote totals, last vote times, the queue of rewards waiting for offline players and the number
 * of votes counted towards the next pinata. Votes only carry a name, so players are kept by
 * lower-case name. Reads and writes happen on the main thread; only the file write is done elsewhere.
 */
public final class VoteStore {

    public record Queued(String service, long time) {
    }

    public static final class Entry {
        private volatile int total;
        private volatile int monthly;
        private volatile long lastVote;
        private final List<Queued> queue = new ArrayList<>();

        public int total() {
            return total;
        }

        /** Votes this month, counted from the first of the month in the configured time zone. */
        public int monthly() {
            return monthly;
        }

        public long lastVote() {
            return lastVote;
        }

        public List<Queued> queue() {
            return queue;
        }

        public void addVote(long time) {
            total++;
            monthly++;
            lastVote = time;
        }
    }

    /** Names can hold dots ("Notch.total", a Bedrock ".Steve"), so the file uses a slash between path parts, which no name has. */
    private static final char SEPARATOR = '/';

    private final Path file;
    // read by placeholder requests from other threads, changed only on the main thread
    private final Map<String, Entry> players = new java.util.concurrent.ConcurrentHashMap<>();
    private int pinataVotes;
    /** The month the monthly counts belong to, like 2026-09. */
    private String month;
    private boolean dirty;

    public VoteStore(Path file) {
        this.file = file;
    }

    public void load(Logger log) {
        if (!Files.exists(file)) {
            return;
        }
        final YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().pathSeparator(SEPARATOR);
        try {
            yaml.load(file.toFile());
        } catch (IOException | InvalidConfigurationException e) {
            // reading it as empty would make the next save replace every vote total with nothing
            final Path aside = file.resolveSibling(file.getFileName() + ".broken-" + System.currentTimeMillis() / 1000);
            try {
                Files.move(file, aside);
            } catch (IOException moveFailed) {
                log.severe("votes.yml is broken and could not be moved aside: " + moveFailed.getMessage());
            }
            log.severe("votes.yml could not be read (" + e.getMessage() + "). It was kept as " + aside.getFileName()
                    + " and counting starts from zero. Fix that file and copy the totals back.");
            return;
        }
        pinataVotes = Math.max(0, yaml.getInt("pinata_votes"));
        month = yaml.getString("month");
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
            entry.monthly = Math.max(0, saved.getInt("monthly"));
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

    /** Starts the given month. When it is a new one every monthly count goes back to zero. @return true if they were reset */
    public boolean rollover(String current) {
        if (current.equals(month)) {
            return false;
        }
        final boolean first = month == null;
        month = current;
        dirty = true;
        if (first) {
            return false;
        }
        resetMonthly();
        return true;
    }

    public void resetMonthly() {
        players.values().forEach(entry -> entry.monthly = 0);
        dirty = true;
    }

    public int monthly(String name) {
        final Entry entry = find(name);
        return entry == null ? 0 : entry.monthly;
    }

    public void setMonthly(String name, int votes) {
        entry(name).monthly = Math.max(0, votes);
    }

    /** Votes counted towards the next pinata, whether or not the voter was online. */
    public int pinataVotes() {
        return pinataVotes;
    }

    public void setPinataVotes(int votes) {
        pinataVotes = Math.max(0, votes);
        dirty = true;
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
        yaml.options().pathSeparator(SEPARATOR);
        yaml.set("pinata_votes", pinataVotes);
        yaml.set("month", month);
        players.forEach((name, entry) -> {
            final String base = "players/" + name + "/";
            yaml.set(base + "total", entry.total);
            yaml.set(base + "monthly", entry.monthly);
            yaml.set(base + "last_vote", entry.lastVote);
            if (!entry.queue.isEmpty()) {
                yaml.set(base + "queue", entry.queue.stream().map(q -> q.service() + "|" + q.time()).toList());
            }
        });
        dirty = false;
        return yaml.saveToString();
    }

    /** Puts the dirty mark back after a failed write, so the next save tries again. */
    public void writeFailed() {
        dirty = true;
    }

    /** Saves right now, on this thread. Used before something that must not be paid twice if the server dies. */
    public void flush(Logger log) {
        try {
            write(snapshot());
        } catch (IOException e) {
            dirty = true;
            log.severe("Could not save votes.yml: " + e.getMessage());
        }
    }

    /** Writes a snapshot through a temporary file so a crash never leaves half a file behind. */
    public synchronized void write(String snapshot) throws IOException {
        Files.createDirectories(file.getParent());
        final Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, snapshot, StandardCharsets.UTF_8);
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
