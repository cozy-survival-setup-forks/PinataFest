package dev.pinatafest.reward;

import dev.pinatafest.config.Settings;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RewardsTest {

    /** A "random" source that always returns the same fraction, so rolls are predictable. */
    private static RandomGenerator fixed(double value) {
        return new RandomGenerator() {
            @Override
            public long nextLong() {
                return 0;
            }

            @Override
            public double nextDouble() {
                return value;
            }

            @Override
            public int nextInt(int bound) {
                return 0;
            }
        };
    }

    private static Settings.Reward reward(double chance, boolean stop, boolean randomLine, boolean once,
                                          boolean ignoreVanished, boolean ignoreOffline, String... commands) {
        return new Settings.Reward("id", chance, null, stop, randomLine, once, ignoreVanished, ignoreOffline,
                Set.of(), List.of(commands));
    }

    private static Rewards.Voter voter(int total, boolean vanished, boolean offline, Predicate<String> perms) {
        return new Rewards.Voter("Steve", "SiteA", total, vanished, offline, perms);
    }

    @Test
    void fillsInPlaceholders() {
        final var rewards = List.of(reward(100, false, false, false, false, false, "give %player% %service% %votes%"));
        assertEquals(List.of("give Steve SiteA 7"),
                Rewards.forPlayer(rewards, voter(7, false, false, p -> true), fixed(0.5)));
    }

    @Test
    void chanceDecidesTheRoll() {
        final var rewards = List.of(reward(25, false, false, false, false, false, "x"));
        assertEquals(List.of("x"), Rewards.forPlayer(rewards, voter(1, false, false, p -> true), fixed(0.24)));
        assertTrue(Rewards.forPlayer(rewards, voter(1, false, false, p -> true), fixed(0.26)).isEmpty());
    }

    @Test
    void stopEndsTheRolling() {
        final var rewards = List.of(
                reward(100, true, false, false, false, false, "first"),
                reward(100, false, false, false, false, false, "second"));
        assertEquals(List.of("first"), Rewards.forPlayer(rewards, voter(1, false, false, p -> true), fixed(0)));
    }

    @Test
    void randomLinePicksOne() {
        final var rewards = List.of(reward(100, false, true, false, false, false, "one", "two", "three"));
        assertEquals(List.of("one"), Rewards.forPlayer(rewards, voter(1, false, false, p -> true), fixed(0)));
    }

    @Test
    void vanishedAndOfflineFlagsSkipAnEntry() {
        final var rewards = List.of(reward(100, false, false, false, true, true, "x"));
        assertTrue(Rewards.forPlayer(rewards, voter(1, true, false, p -> true), fixed(0)).isEmpty());
        assertTrue(Rewards.forPlayer(rewards, voter(1, false, true, p -> true), fixed(0)).isEmpty());
        assertEquals(1, Rewards.forPlayer(rewards, voter(1, false, false, p -> true), fixed(0)).size());
    }

    @Test
    void permissionAndServiceFiltersApply() {
        final var rewards = List.of(new Settings.Reward("a", 100, "pinatafest.vip", false, false, false, false, false,
                Set.of("sitea"), List.of("x")));
        assertTrue(Rewards.forPlayer(rewards, voter(1, false, false, p -> false), fixed(0)).isEmpty());
        assertEquals(1, Rewards.forPlayer(rewards, voter(1, false, false, p -> true), fixed(0)).size());
        assertTrue(Rewards.forPlayer(rewards, new Rewards.Voter("Steve", "SiteB", 1, false, false, p -> true),
                fixed(0)).isEmpty());
    }

    @Test
    void onceEntriesRunSeparatelyAndWithoutAPlayer() {
        final var rewards = List.of(
                reward(100, false, false, true, false, false, "say the pinata fell"),
                reward(100, false, false, false, false, false, "give %player% diamond"));

        assertEquals(List.of("give Steve diamond"),
                Rewards.forPlayer(rewards, voter(1, false, false, p -> true), fixed(0)));
        assertEquals(List.of("say the pinata fell"), Rewards.once(rewards, fixed(0)));
    }
}
