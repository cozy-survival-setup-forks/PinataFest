package dev.pinatafest.vote;

import dev.pinatafest.spawn.SpawnPoint;
import dev.pinatafest.spawn.SpawnStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoreSafetyTest {

    private static final Logger LOG = Logger.getAnonymousLogger();

    @TempDir
    Path dir;

    private long files(String prefix) throws IOException {
        try (Stream<Path> list = Files.list(dir)) {
            return list.filter(p -> p.getFileName().toString().startsWith(prefix)).count();
        }
    }

    @Test
    void namesWithDotsStayApart() throws IOException {
        final Path file = dir.resolve("votes.yml");
        final VoteStore store = new VoteStore(file);
        store.entry("Notch").addVote(1);
        store.entry("Notch").addVote(2);
        store.entry("Notch.total").addVote(3);
        store.entry(".Steve").addVote(4);
        store.entry("Steve").addVote(5);
        store.entry("Steve").queue().add(new VoteStore.Queued("SiteA", 6));
        store.write(store.snapshot());

        final VoteStore loaded = new VoteStore(file);
        loaded.load(LOG);
        assertEquals(2, loaded.find("Notch").total());
        assertEquals(1, loaded.find("Notch.total").total());
        assertEquals(1, loaded.find(".Steve").total());
        assertTrue(loaded.find(".Steve").queue().isEmpty());
        assertEquals(1, loaded.find("Steve").queue().size());
    }

    @Test
    void aBrokenVotesFileIsKeptNotReplaced() throws IOException {
        final Path file = dir.resolve("votes.yml");
        Files.writeString(file, "players:\n  Steve: [unclosed\n");
        final VoteStore store = new VoteStore(file);
        store.load(LOG);
        assertNull(store.find("Steve"));
        assertEquals(1, files("votes.yml.broken-"));

        store.entry("Alex").addVote(1);
        store.write(store.snapshot());
        assertEquals(1, files("votes.yml.broken-"));
        assertNotNull(Files.readString(file));
    }

    @Test
    void spawnNamesWithDotsSurviveAReload() throws IOException {
        final Path file = dir.resolve("spawns.yml");
        final SpawnStore spawns = new SpawnStore(file);
        spawns.put("arena.north", new SpawnPoint.Fixed("world", 1, 64, 2, 0f));
        spawns.put("field", new SpawnPoint.Fixed("world", 5, 64, 6, 0f));

        final SpawnStore loaded = new SpawnStore(file);
        loaded.load(LOG);
        assertEquals(2, loaded.all().size());
        assertNotNull(loaded.all().get("arena.north"));
    }

    @Test
    void aBrokenSpawnFileKeepsTheLoadedPoints() throws IOException {
        final Path file = dir.resolve("spawns.yml");
        final SpawnStore spawns = new SpawnStore(file);
        spawns.put("field", new SpawnPoint.Fixed("world", 5, 64, 6, 0f));
        Files.writeString(file, "field: [unclosed\n");

        spawns.load(LOG);
        assertEquals(1, spawns.all().size());
        assertEquals(1, files("spawns.yml.broken-"));
        spawns.put("hill", new SpawnPoint.Fixed("world", 0, 70, 0, 0f));
        final SpawnStore loaded = new SpawnStore(file);
        loaded.load(LOG);
        assertEquals(2, loaded.all().size());
    }
}
