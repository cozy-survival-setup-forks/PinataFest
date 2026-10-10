package dev.pinatafest.vote;

import dev.pinatafest.safe.Db;
import dev.pinatafest.safe.DbBackups;
import dev.pinatafest.safe.Health;
import dev.pinatafest.safe.Journal;
import dev.pinatafest.safe.SafeIo;
import dev.pinatafest.safe.SaveQueue;
import dev.pinatafest.safe.ServerId;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Vote totals, last vote times, the queue of rewards waiting for offline players and the number
 * of votes counted towards the next pinata. Votes only carry a name, so players are kept by
 * lower-case name. The working copy is in memory and is read and changed on the main thread; it is
 * written to {@code votes.db} (SQLite) in one transaction, and the file can be rebuilt from its backups.
 * Without {@link #load} the store only lives in memory, which is what the tests of other classes use.
 */
public final class VoteStore {

    public static final int SCHEMA = 1;
    private static final String BACKUP_PREFIX = "votes";

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

    /** One player as written to the database. */
    public record Row(String name, int total, int monthly, long lastVote, List<Queued> queue) {
    }

    /** The whole state at one moment, with a number so an older one never overwrites a newer one. */
    public record Snapshot(long seq, int pinataVotes, String month, List<Row> rows) {
    }

    /** Names can hold dots ("Notch.total", a Bedrock ".Steve"), so the old file used a slash between path parts, which no name has. */
    private static final char SEPARATOR = '/';

    private final Path legacy;
    private final Path folder;
    private final Path dbFile;
    private final Path backupDir;
    // read by placeholder requests from other threads, changed only on the main thread
    private final Map<String, Entry> players = new java.util.concurrent.ConcurrentHashMap<>();
    private int pinataVotes;
    /** The month the monthly counts belong to, like 2026-09. */
    private String month;
    private volatile boolean dirty;
    private long sequence;

    private Db db;
    private Journal journal;
    private DbBackups backups;
    private SaveQueue saves;
    private final Object writeLock = new Object();
    private long lastWritten;
    private boolean imported;

    /** @param legacyFile the old {@code votes.yml}; the database and the backup folder are beside it */
    public VoteStore(Path legacyFile) {
        this.legacy = legacyFile;
        this.folder = legacyFile.toAbsolutePath().getParent();
        this.dbFile = folder.resolve("votes.db");
        this.backupDir = folder.resolve("backups");
    }

    /**
     * Opens the database (a damaged one is replaced by the newest backup that verifies), brings in an old
     * {@code votes.yml} once, and reads everything into memory.
     *
     * @throws IOException  when the data cannot be used and must not be replaced by an empty set
     * @throws SQLException when the database cannot be read
     */
    public void load(Logger log, int backupKeep) throws IOException, SQLException {
        db = Db.openRecovering(dbFile, SCHEMA, backupDir, BACKUP_PREFIX, true, log);
        try {
            db.tx(c -> {
                try (Statement s = c.createStatement()) {
                    s.execute("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
                    s.execute("CREATE TABLE IF NOT EXISTS players (name TEXT PRIMARY KEY, total INTEGER NOT NULL, "
                            + "monthly INTEGER NOT NULL, last_vote INTEGER NOT NULL)");
                    s.execute("CREATE TABLE IF NOT EXISTS queue (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, "
                            + "service TEXT NOT NULL, time INTEGER NOT NULL)");
                    s.execute("CREATE INDEX IF NOT EXISTS queue_name ON queue (name)");
                }
            });
            if (db.userVersion() < SCHEMA) {
                db.setUserVersion(SCHEMA);
            }
            journal = new Journal(db);
            journal.recover(log);
            importLegacy(log);
            readAll(log);
        } catch (IOException | SQLException | RuntimeException e) {
            db.close();
            db = null;
            throw e;
        }
        configureBackups(backupKeep, log);
        saves = new SaveQueue("PinataFest", 64, log);
        Health.file("votes.db", imported ? "in use, votes.yml was brought in" : "in use");
    }

    /** Sets how many backups are kept. Call again after a reload. */
    public void configureBackups(int keep, Logger log) {
        if (db != null) {
            backups = new DbBackups(db, backupDir, BACKUP_PREFIX, keep, log);
        }
    }

    /** Makes and verifies one backup. @return true when it worked (also when there is no database to copy) */
    public boolean backup() {
        return backups == null || backups.run();
    }

    /** The file name of the newest database copy in the backups folder, or null when there is none. */
    public String newestBackup() {
        final List<Path> found = DbBackups.list(backupDir, BACKUP_PREFIX);
        return found.isEmpty() ? null : found.get(0).getFileName().toString();
    }

    public boolean isOpen() {
        return db != null;
    }

    /** The record of payouts, or null when the store is only in memory. */
    public Journal journal() {
        return journal;
    }

    public Path databaseFile() {
        return dbFile;
    }

    public ServerId.Slot serverIdSlot() {
        return new ServerId.Slot() {
            @Override
            public String read() throws SQLException {
                return db.read(c -> metaValue(c, "server-id"));
            }

            @Override
            public void write(String id) throws SQLException {
                db.tx(c -> putMeta(c, "server-id", id));
            }
        };
    }

    // ---- bringing in votes.yml

    /** What an old file holds, parsed before anything is written. */
    private record Parsed(int pinataVotes, String month, List<Row> rows) {
        int queued() {
            return rows.stream().mapToInt(r -> r.queue().size()).sum();
        }

        long totals() {
            return rows.stream().mapToLong(Row::total).sum();
        }

        long monthlies() {
            return rows.stream().mapToLong(Row::monthly).sum();
        }
    }

    private void importLegacy(Logger log) throws IOException, SQLException {
        if (!Files.exists(legacy)) {
            return;
        }
        if (metaValue("import") != null) {
            // brought in earlier; only the rename was left to do
            moveAside(log);
            return;
        }
        if (countPlayers() > 0) {
            log.severe("votes.yml is there, but votes.db already holds votes and it was not brought in from that file. "
                    + "votes.yml was left alone and votes.db is used. Remove one of them if this is not what you want.");
            return;
        }
        final Parsed parsed = parse(log);
        db.tx(c -> {
            // one transaction: a stop in the middle leaves nothing behind, and the next start does it all again
            insertAll(c, parsed.rows());
            putMeta(c, "pinata_votes", Integer.toString(parsed.pinataVotes()));
            if (parsed.month() != null) {
                putMeta(c, "month", parsed.month());
            }
            final long players = scalar(c, "SELECT COUNT(*) FROM players");
            final long totals = scalar(c, "SELECT COALESCE(SUM(total),0) FROM players");
            final long monthlies = scalar(c, "SELECT COALESCE(SUM(monthly),0) FROM players");
            final long queued = scalar(c, "SELECT COUNT(*) FROM queue");
            if (players != parsed.rows().size() || totals != parsed.totals() || monthlies != parsed.monthlies()
                    || queued != parsed.queued()) {
                throw new SQLException("what was written does not match votes.yml (players " + players + "/" + parsed.rows().size()
                        + ", total votes " + totals + "/" + parsed.totals() + ", monthly " + monthlies + "/" + parsed.monthlies()
                        + ", queued " + queued + "/" + parsed.queued() + ")");
            }
            putMeta(c, "import", "done " + players + " players, " + totals + " votes, " + queued + " queued");
        });
        imported = true;
        log.info("votes.yml was brought into votes.db and checked: " + parsed.rows().size() + " players, "
                + parsed.totals() + " votes, " + parsed.queued() + " queued rewards.");
        moveAside(log);
    }

    private Parsed parse(Logger log) throws IOException {
        final YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().pathSeparator(SEPARATOR);
        try {
            yaml.load(legacy.toFile());
        } catch (IOException | InvalidConfigurationException e) {
            // starting from zero would pay every queued reward and lose every total: the file stays and the plugin stops
            log.severe("votes.yml could not be read (" + e.getMessage() + "). It was left where it is, untouched, and PinataFest "
                    + "will not start until it is fixed or removed.");
            Health.failure("votes.yml could not be read");
            throw new IOException("votes.yml could not be read: " + e.getMessage(), e);
        }
        // names are kept in lower case, so two spellings of one name (only possible by hand) become one player
        final Map<String, Row> merged = new java.util.LinkedHashMap<>();
        final ConfigurationSection section = yaml.getConfigurationSection("players");
        if (section != null) {
            for (String name : section.getKeys(false)) {
                final ConfigurationSection saved = section.getConfigurationSection(name);
                if (saved == null) {
                    continue;
                }
                final List<Queued> queue = new ArrayList<>();
                for (String line : saved.getStringList("queue")) {
                    final int split = line.lastIndexOf('|');
                    try {
                        queue.add(new Queued(line.substring(0, split), Long.parseLong(line.substring(split + 1))));
                    } catch (RuntimeException e) {
                        // not guessed at: the file is stopped on, so nothing is dropped without being seen
                        log.severe("votes.yml: a queued reward of " + name + " could not be read.");
                        throw new IOException("votes.yml has a queued reward that cannot be read (player " + name + ")", e);
                    }
                }
                final Row row = new Row(key(name), saved.getInt("total"), Math.max(0, saved.getInt("monthly")),
                        saved.getLong("last_vote"), queue);
                merged.merge(row.name(), row, (a, b) -> {
                    final List<Queued> both = new ArrayList<>(a.queue());
                    both.addAll(b.queue());
                    return new Row(a.name(), a.total() + b.total(), a.monthly() + b.monthly(),
                            Math.max(a.lastVote(), b.lastVote()), both);
                });
            }
        }
        final List<Row> rows = new ArrayList<>(merged.values());
        return new Parsed(Math.max(0, yaml.getInt("pinata_votes")), yaml.getString("month"), rows);
    }

    private void moveAside(Logger log) {
        Path target = legacy.resolveSibling(legacy.getFileName() + ".migrated");
        if (Files.exists(target)) {
            target = legacy.resolveSibling(legacy.getFileName() + ".migrated-" + SafeIo.stamp());
        }
        try {
            Files.move(legacy, target, StandardCopyOption.ATOMIC_MOVE);
            log.info("The old votes.yml was renamed to " + target.getFileName() + ". It can be removed once you are happy with votes.db.");
        } catch (IOException e) {
            log.warning("votes.yml could not be renamed (" + e.getMessage() + "); it will not be read again.");
        }
    }

    // ---- reading and writing the database

    private void readAll(Logger log) throws SQLException {
        players.clear();
        db.read(c -> {
            final String saved = metaValue(c, "pinata_votes");
            pinataVotes = saved == null ? 0 : Math.max(0, parseInt(saved));
            month = metaValue(c, "month");
            try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT name, total, monthly, last_vote FROM players")) {
                while (rs.next()) {
                    final Entry entry = new Entry();
                    entry.total = rs.getInt(2);
                    entry.monthly = Math.max(0, rs.getInt(3));
                    entry.lastVote = rs.getLong(4);
                    players.put(rs.getString(1), entry);
                }
            }
            try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT name, service, time FROM queue ORDER BY id")) {
                while (rs.next()) {
                    final Entry entry = players.get(rs.getString(1));
                    if (entry != null) {
                        entry.queue.add(new Queued(rs.getString(2), rs.getLong(3)));
                    } else {
                        log.warning("votes.db: a queued reward belongs to a player that is not stored.");
                    }
                }
            }
            return null;
        });
    }

    private static int parseInt(String text) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String metaValue(String key) throws SQLException {
        return db.read(c -> metaValue(c, key));
    }

    private static String metaValue(java.sql.Connection c, String key) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT value FROM meta WHERE key=?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static void putMeta(java.sql.Connection c, String key, String value) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO meta (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        }
    }

    private static long scalar(java.sql.Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    private long countPlayers() throws SQLException {
        return db.read(c -> scalar(c, "SELECT COUNT(*) FROM players"));
    }

    private static void insertAll(java.sql.Connection c, List<Row> rows) throws SQLException {
        try (PreparedStatement player = c.prepareStatement("INSERT INTO players (name, total, monthly, last_vote) VALUES (?,?,?,?)");
             PreparedStatement queue = c.prepareStatement("INSERT INTO queue (name, service, time) VALUES (?,?,?)")) {
            for (Row row : rows) {
                player.setString(1, row.name());
                player.setInt(2, row.total());
                player.setInt(3, row.monthly());
                player.setLong(4, row.lastVote());
                player.addBatch();
                for (Queued q : row.queue()) {
                    queue.setString(1, row.name());
                    queue.setString(2, q.service());
                    queue.setLong(3, q.time());
                    queue.addBatch();
                }
            }
            player.executeBatch();
            queue.executeBatch();
        }
    }

    /** The current state as it is to be written, and marks it as saved. Call on the main thread. */
    public Snapshot snapshot() {
        final List<Row> rows = new ArrayList<>(players.size());
        players.forEach((name, entry) -> rows.add(new Row(name, entry.total, entry.monthly, entry.lastVote, List.copyOf(entry.queue))));
        dirty = false;
        return new Snapshot(++sequence, pinataVotes, month, rows);
    }

    /** Writes a snapshot in one transaction. An older snapshot than the one already on disk is skipped. */
    public void write(Snapshot snapshot) throws SQLException {
        if (db == null) {
            return;
        }
        synchronized (writeLock) {
            if (snapshot.seq() < lastWritten) {
                return;
            }
            db.tx(c -> {
                try (Statement s = c.createStatement()) {
                    s.executeUpdate("DELETE FROM queue");
                    s.executeUpdate("DELETE FROM players");
                }
                insertAll(c, snapshot.rows());
                putMeta(c, "pinata_votes", Integer.toString(snapshot.pinataVotes()));
                if (snapshot.month() != null) {
                    putMeta(c, "month", snapshot.month());
                }
            });
            lastWritten = snapshot.seq();
        }
    }

    /** Puts the dirty mark back after a failed write, so the next save tries again. */
    public void writeFailed() {
        dirty = true;
    }

    /** Saves right now, on this thread. Used before something that must not be paid twice if the server dies. */
    public boolean flush(Logger log) {
        if (db == null) {
            return true;
        }
        try {
            write(snapshot());
            return true;
        } catch (SQLException e) {
            dirty = true;
            log.severe("Could not save votes.db: " + e.getMessage());
            Health.failure("votes.db could not be saved: " + e.getMessage());
            return false;
        }
    }

    /** Takes a snapshot on this (the main) thread and has it written in the background, in order with the others. */
    public void saveSoon(Logger log) {
        if (saves == null || !dirty) {
            return;
        }
        final Snapshot snapshot = snapshot();
        try {
            saves.latest("votes", () -> {
                try {
                    write(snapshot);
                } catch (SQLException e) {
                    dirty = true;
                    log.severe("Could not save votes.db: " + e.getMessage());
                    Health.failure("votes.db could not be saved: " + e.getMessage());
                }
            });
        } catch (IllegalStateException e) {
            dirty = true;
        }
    }

    /** Waits for the background saves (up to five seconds), writes what is left, and closes the database. */
    public void close(Logger log) {
        if (db == null) {
            return;
        }
        if (saves != null) {
            saves.shutdown(5000);
        }
        if (dirty) {
            flush(log);
        }
        db.close();
        db = null;
    }

    public int pendingSaves() {
        return saves == null ? 0 : saves.pending();
    }

    public long failedSaves() {
        return saves == null ? 0 : saves.failedSaves();
    }

    /** Counts for the doctor command: no names. */
    public String summary() {
        int queued = 0;
        for (Entry e : players.values()) {
            queued += e.queue.size();
        }
        return players.size() + " players, " + queued + " rewards waiting for offline players";
    }

    public int schemaVersion() {
        try {
            return db == null ? SCHEMA : db.userVersion();
        } catch (SQLException e) {
            return -1;
        }
    }

    // ---- the working copy

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

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
