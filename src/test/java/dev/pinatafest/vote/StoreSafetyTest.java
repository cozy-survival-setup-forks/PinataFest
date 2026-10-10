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
