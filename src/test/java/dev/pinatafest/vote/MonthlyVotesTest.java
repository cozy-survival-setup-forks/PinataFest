package dev.pinatafest.vote;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MonthlyVotesTest {

    @TempDir
    Path dir;

    @Test
    void votesCountForTheMonthAndTheTotal() {
        final VoteStore store = new VoteStore(dir.resolve("votes.yml"));
        store.rollover("2026-09");
        store.entry("Steve").addVote(1);
        store.entry("steve").addVote(2);
        assertEquals(2, store.monthly("STEVE"));
        assertEquals(2, store.find("steve").total());
    }

    @Test
    void aNewMonthResetsTheMonthlyCountsOnly() {
        final VoteStore store = new VoteStore(dir.resolve("votes.yml"));
        store.rollover("2026-09");
        store.entry("Steve").addVote(1);
        assertFalse(store.rollover("2026-09"));
        assertTrue(store.rollover("2026-10"));
        assertEquals(0, store.monthly("Steve"));
        assertEquals(1, store.find("Steve").total());
    }

    @Test
    void theMonthAndCountsSurviveARestart() throws Exception {
        final Path file = dir.resolve("votes.yml");
        final VoteStore store = new VoteStore(file);
        store.rollover("2026-09");
        store.entry("Steve").addVote(1);
        store.write(store.snapshot());

        final VoteStore loaded = new VoteStore(file);
        loaded.load(Logger.getAnonymousLogger());
        assertEquals(1, loaded.monthly("Steve"));
        assertFalse(loaded.rollover("2026-09"));
        assertTrue(loaded.rollover("2026-10"));
        assertEquals(0, loaded.monthly("Steve"));
    }

    @Test
    void settingAndResettingOnePlayer() {
        final VoteStore store = new VoteStore(dir.resolve("votes.yml"));
        store.setMonthly("Alex", 7);
        store.setMonthly("Bob", 3);
        store.setMonthly("Alex", 0);
        assertEquals(0, store.monthly("Alex"));
        assertEquals(3, store.monthly("Bob"));
        store.resetMonthly();
        assertEquals(0, store.monthly("Bob"));
    }
}
