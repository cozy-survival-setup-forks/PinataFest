package dev.pinatafest.util;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UtilTest {

    @Test
    void parsesDurations() {
        assertEquals(Duration.ofSeconds(3), Durations.parse("3s"));
        assertEquals(Duration.ofSeconds(90), Durations.parse("1m 30s"));
        assertEquals(Duration.ofSeconds(5), Durations.parse("5"));
    }

    @Test
    void rejectsGarbage() {
        assertThrows(IllegalArgumentException.class, () -> Durations.parse("soon"));
        assertThrows(IllegalArgumentException.class, () -> Durations.parse(""));
    }

    @Test
    void convertsLegacyCodes() {
        assertEquals("<#FF8C8C>hi<white>", Legacy.toMiniMessage("&#FF8C8Chi&f"));
        assertEquals("<bold>plain</bold>", Legacy.toMiniMessage("<bold>plain</bold>"));
    }
}
