package dev.pinatafest.vote;

import dev.pinatafest.safe.Db;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoteDatabaseTest {

    private static final Logger LOG = Logger.getAnonymousLogger();

    @TempDir
    Path dir;

    private VoteStore open() throws Exception {
        final VoteStore store = new VoteStore(dir.resolve("votes.yml"));
        store.load(LOG, 3);
        return store;
    }

    private long files(String prefix) throws IOException {
        try (Stream<Path> list = Files.list(dir)) {
            return list.filter(p -> p.getFileName().toString().startsWith(prefix)).count();
        }
    }

    private String url() {
        return "jdbc:sqlite:" + dir.resolve("votes.db").toAbsolutePath();
    }

    private static final String OLD_FILE = """
            pinata_votes: 4
            month: 2026-09
            players:
              steve:
                total: 12
                monthly: 5
                last_vote: 1000
                queue:
                  - "SiteA|50"
                  - "SiteB|60"
              alex:
                total: 3
                monthly: 3
                last_vote: 2000
              notch.total:
                total: 1
                monthly: 0
                last_vote: 7
            """;

    @Test
    void namesWithDotsStayApart() throws Exception {
        final VoteStore store = open();
        store.entry("Notch").addVote(1);
        store.entry("Notch").addVote(2);
        store.entry("Notch.total").addVote(3);
        store.entry(".Steve").addVote(4);
        store.entry("Steve").addVote(5);
        store.entry("Steve").queue().add(new VoteStore.Queued("SiteA", 6));
        assertTrue(store.flush(LOG));
        store.close(LOG);

        final VoteStore loaded = open();
        assertEquals(2, loaded.find("Notch").total());
        assertEquals(1, loaded.find("Notch.total").total());
        assertEquals(1, loaded.find(".Steve").total());
        assertTrue(loaded.find(".Steve").queue().isEmpty());
        assertEquals(1, loaded.find("Steve").queue().size());
        loaded.close(LOG);
    }

    @Test
    void anOldVotesFileIsBroughtInOnceAndSetAside() throws Exception {
        Files.writeString(dir.resolve("votes.yml"), OLD_FILE);
        final VoteStore store = open();

        assertEquals(12, store.find("Steve").total());
        assertEquals(5, store.find("Steve").monthly());
        assertEquals(1000L, store.find("Steve").lastVote());
        assertEquals(2, store.find("Steve").queue().size());
        assertEquals("SiteB", store.find("Steve").queue().get(1).service());
        assertEquals(3, store.find("Alex").total());
        assertEquals(4, store.pinataVotes());
        assertFalse(Files.exists(dir.resolve("votes.yml")));
        assertTrue(Files.exists(dir.resolve("votes.yml.migrated")));
        store.close(LOG);

        // a second start reads the database, not the file, and does not bring anything in twice
        Files.writeString(dir.resolve("votes.yml"), "players:\n  steve:\n    total: 999\n");
        final VoteStore again = open();
        assertEquals(12, again.find("Steve").total());
        assertFalse(Files.exists(dir.resolve("votes.yml")));
        again.close(LOG);
    }

    @Test
    void twoSpellingsOfOneNameAreAddedTogether() throws Exception {
        Files.writeString(dir.resolve("votes.yml"), """
                players:
                  Steve:
                    total: 2
                    monthly: 1
                    last_vote: 5
                    queue: ["SiteA|1"]
                  steve:
                    total: 3
                    monthly: 2
                    last_vote: 9
                    queue: ["SiteB|2"]
                """);
        final VoteStore store = open();
        assertEquals(5, store.find("steve").total());
        assertEquals(3, store.find("steve").monthly());
        assertEquals(9L, store.find("steve").lastVote());
        assertEquals(2, store.find("steve").queue().size());
        store.close(LOG);
    }

    @Test
    void anImportThatStopsHalfWayLeavesTheFileAndNothingElse() throws Exception {
        Files.writeString(dir.resolve("votes.yml"), OLD_FILE);
        // a database that refuses one of the names, as a stand-in for the server stopping in the middle
        try (Connection c = DriverManager.getConnection(url()); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
            s.execute("CREATE TABLE players (name TEXT PRIMARY KEY, total INTEGER NOT NULL, monthly INTEGER NOT NULL, last_vote INTEGER NOT NULL)");
            s.execute("CREATE TABLE queue (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, service TEXT NOT NULL, time INTEGER NOT NULL)");
            s.execute("CREATE TRIGGER stop_here BEFORE INSERT ON players WHEN NEW.name='alex' BEGIN SELECT RAISE(ABORT, 'stopped'); END");
            s.execute("PRAGMA user_version=1");
        }
        final VoteStore first = new VoteStore(dir.resolve("votes.yml"));
        assertThrows(SQLException.class, () -> first.load(LOG, 3));
        assertTrue(Files.exists(dir.resolve("votes.yml")), "the old file stays until everything is in and checked");
        assertEquals(OLD_FILE, Files.readString(dir.resolve("votes.yml")));
        try (Connection c = DriverManager.getConnection(url()); Statement s = c.createStatement()) {
            try (var rs = s.executeQuery("SELECT COUNT(*) FROM players")) {
                rs.next();
                assertEquals(0, rs.getInt(1), "nothing was kept from the half import");
            }
            s.execute("DROP TRIGGER stop_here");
        }

        // the next start does the whole import
        final VoteStore second = open();
        assertEquals(12, second.find("Steve").total());
        assertEquals(3, second.find("Alex").total());
        assertTrue(Files.exists(dir.resolve("votes.yml.migrated")));
        second.close(LOG);
    }

    @Test
    void anUnreadableVotesFileStopsTheStartAndIsLeftAlone() throws Exception {
        final String broken = "players:\n  Steve: [unclosed\n";
        Files.writeString(dir.resolve("votes.yml"), broken);
        final VoteStore store = new VoteStore(dir.resolve("votes.yml"));
        assertThrows(IOException.class, () -> store.load(LOG, 3));
        assertEquals(broken, Files.readString(dir.resolve("votes.yml")));
        assertFalse(store.isOpen());
    }

    @Test
    void aDamagedDatabaseIsNotReplacedByAnEmptyOne() throws Exception {
        final VoteStore store = open();
        store.entry("Steve").addVote(1);
        assertTrue(store.flush(LOG));
        store.close(LOG);

        final byte[] garbage = "this is not a database, at all, really not one".getBytes();
        Files.write(dir.resolve("votes.db"), garbage);
        final VoteStore damaged = new VoteStore(dir.resolve("votes.yml"));
        assertThrows(Db.CorruptException.class, () -> damaged.load(LOG, 3));
        assertTrue(Arrays.equals(garbage, Files.readAllBytes(dir.resolve("votes.db"))), "the damaged file is untouched");
        assertEquals(1, files("votes.db.corrupt-"), "and a copy of it is kept");
    }

    @Test
    void aDamagedDatabaseComesBackFromTheNewestGoodBackup() throws Exception {
        final VoteStore store = open();
        store.entry("Steve").addVote(1);
        store.entry("Steve").addVote(2);
        store.entry("Steve").queue().add(new VoteStore.Queued("SiteA", 3));
        assertTrue(store.flush(LOG));
        assertTrue(store.backup());
        store.close(LOG);

        Files.write(dir.resolve("votes.db"), "garbage garbage garbage garbage".getBytes());
        Files.deleteIfExists(dir.resolve("votes.db-wal"));
        final VoteStore restored = open();
        assertEquals(2, restored.find("Steve").total());
        assertEquals(1, restored.find("Steve").queue().size());
        restored.close(LOG);
    }

    @Test
    void aDatabaseFromANewerVersionIsNotTouched() throws Exception {
        open().close(LOG);
        try (Connection c = DriverManager.getConnection(url()); Statement s = c.createStatement()) {
            s.execute("PRAGMA user_version=99");
        }
        final byte[] before = Files.readAllBytes(dir.resolve("votes.db"));
        final VoteStore store = new VoteStore(dir.resolve("votes.yml"));
        assertThrows(Db.NewerSchemaException.class, () -> store.load(LOG, 3));
        assertTrue(Arrays.equals(before, Files.readAllBytes(dir.resolve("votes.db"))));
    }

    @Test
    void anOlderSnapshotNeverOverwritesANewerOne() throws Exception {
        final VoteStore store = open();
        store.entry("Steve").queue().add(new VoteStore.Queued("SiteA", 1));
        final VoteStore.Snapshot older = store.snapshot();
        store.entry("Steve").queue().clear();
        assertTrue(store.flush(LOG));
        store.write(older); // arrives late, from the background
        store.close(LOG);

        final VoteStore loaded = open();
        assertTrue(loaded.find("Steve").queue().isEmpty(), "a paid reward must not come back");
        loaded.close(LOG);
    }

    @Test
    void aPayoutLeftUnfinishedIsFlaggedAndNotRepeated() throws Exception {
        final VoteStore store = open();
        store.entry("Steve").queue().add(new VoteStore.Queued("SiteA", 1));
        assertTrue(store.flush(LOG));
        final String id = store.journal().begin("queued-votes", "player=Steve count=1 services=SiteA");
        store.close(LOG); // the server stopped before the payout was marked as done

        final VoteStore again = open();
        assertEquals(1, again.journal().unknown().size());
        assertTrue(again.journal().unknown().get(0).startsWith(id));
        assertTrue(again.journal().resolve(id));
        assertTrue(again.journal().unknown().isEmpty());
        again.close(LOG);
    }

    @Test
    void theServerIdSurvivesAndIsNotChanged() throws Exception {
        final VoteStore store = open();
        assertNull(store.serverIdSlot().read());
        store.serverIdSlot().write("0a1b2c3d-1111-2222-3333-444455556666");
        store.close(LOG);
        final VoteStore again = open();
        assertEquals("0a1b2c3d-1111-2222-3333-444455556666", again.serverIdSlot().read());
        assertNotNull(again.journal());
        again.close(LOG);
    }
}
