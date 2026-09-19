package dev.pinatafest.spawn;

import org.bukkit.Location;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.nio.file.Path;
import java.util.Random;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpawnTest {

    @BeforeEach
    void setUp() {
        MockBukkit.mock().addSimpleWorld("world");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void fixedSpotIsExact() {
        final Location at = new SpawnPoint.Fixed("world", 10, 70, -5, 90f).pick(new Random(1));
        assertEquals(10, at.getX());
        assertEquals(70, at.getY());
        assertEquals(-5, at.getZ());
    }

    @Test
    void areaPointsStayInsideTheRectangle() {
        final SpawnPoint area = new SpawnPoint.Area("world", -10, 10, 20, 40);
        final Random random = new Random(7);
        for (int i = 0; i < 200; i++) {
            final Location at = area.pick(random);
            assertTrue(at.getX() >= -10 && at.getX() <= 10, "x " + at.getX());
            assertTrue(at.getZ() >= 20 && at.getZ() <= 40, "z " + at.getZ());
        }
    }

    @Test
    void zonePointsStayInsideTheBoxAndUseTheHeight() {
        final SpawnPoint zone = new SpawnPoint.Zone("world", 0, 5, 60, 90, 0, 5);
        final Random random = new Random(3);
        double lowest = Double.MAX_VALUE;
        double highest = Double.MIN_VALUE;
        for (int i = 0; i < 300; i++) {
            final Location at = zone.pick(random);
            assertTrue(at.getX() >= 0 && at.getX() <= 5);
            assertTrue(at.getZ() >= 0 && at.getZ() <= 5);
            assertTrue(at.getY() >= 60 && at.getY() <= 90);
            lowest = Math.min(lowest, at.getY());
            highest = Math.max(highest, at.getY());
        }
        assertTrue(highest - lowest > 15, "heights should be spread over the box");
    }

    @Test
    void unknownWorldGivesNothing() {
        assertNull(new SpawnPoint.Fixed("nowhere", 0, 0, 0, 0f).pick(new Random(1)));
    }

    @Test
    void savedPointsSurviveARestartInEveryShape(@TempDir Path dir) throws Exception {
        final SpawnStore first = new SpawnStore(dir.resolve("spawns.yml"));
        first.put("Spot", new SpawnPoint.Fixed("world", 1, 2, 3, 4f));
        first.put("Field", new SpawnPoint.Area("world", 0, 10, 0, 10));
        first.put("Sky", new SpawnPoint.Zone("world", 0, 10, 50, 80, 0, 10));

        final SpawnStore second = new SpawnStore(dir.resolve("spawns.yml"));
        second.load(Logger.getAnonymousLogger());
        assertEquals(3, second.all().size());
        assertEquals(new SpawnPoint.Fixed("world", 1, 2, 3, 4f), second.get("spot"));
        assertEquals(new SpawnPoint.Area("world", 0, 10, 0, 10), second.get("FIELD"));
        assertEquals(new SpawnPoint.Zone("world", 0, 10, 50, 80, 0, 10), second.get("sky"));
    }

    @Test
    void removingAPointForgetsIt(@TempDir Path dir) throws Exception {
        final SpawnStore store = new SpawnStore(dir.resolve("spawns.yml"));
        store.put("Spot", new SpawnPoint.Fixed("world", 1, 2, 3, 0f));

        assertTrue(store.remove("spot"));
        assertFalse(store.remove("spot"));

        final SpawnStore again = new SpawnStore(dir.resolve("spawns.yml"));
        again.load(Logger.getAnonymousLogger());
        assertNull(again.get("spot"));
        assertNotNull(store.all());
    }
}
