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

    private static Settings.Reward reward(String id, double chance, boolean stop, boolean randomLine,
                                          boolean ignoreVanished, boolean ignoreOffline, String... commands) {
        return new Settings.Reward(id, chance, null, stop, randomLine, ignoreVanished, ignoreOffline,
                Set.of(), List.of(commands));
    }

    private static Rewards.Voter voter(int total, boolean vanished, boolean offline, Predicate<String> perms) {
        return new Rewards.Voter("Steve", "SiteA", total, vanished, offline, perms);
    }

    @Test
    void fillsInPlaceholders() {
        final var rewards = List.of(reward("a", 100, false, false, false, false, "give %player% %service% %votes%"));
        assertEquals(List.of("give Steve SiteA 7"),
                Rewards.forVote(rewards, voter(7, false, false, p -> true), fixed(0.5)));
    }

    @Test
    void chanceDecidesTheRoll() {
        final var rewards = List.of(reward("a", 25, false, false, false, false, "x"));
        assertEquals(List.of("x"), Rewards.forVote(rewards, voter(1, false, false, p -> true), fixed(0.24)));
        assertTrue(Rewards.forVote(rewards, voter(1, false, false, p -> true), fixed(0.26)).isEmpty());
    }

    @Test
    void stopEndsTheRolling() {
        final var rewards = List.of(
                reward("a", 100, true, false, false, false, "first"),
                reward("b", 100, false, false, false, false, "second"));
        assertEquals(List.of("first"), Rewards.forVote(rewards, voter(1, false, false, p -> true), fixed(0)));
    }

    @Test
    void randomLinePicksOne() {
        final var rewards = List.of(reward("a", 100, false, true, false, false, "one", "two", "three"));
        assertEquals(List.of("one"), Rewards.forVote(rewards, voter(1, false, false, p -> true), fixed(0)));
    }

    @Test
    void vanishedAndOfflineFlagsSkipAnEntry() {
        final var rewards = List.of(reward("a", 100, false, false, true, true, "x"));
        assertTrue(Rewards.forVote(rewards, voter(1, true, false, p -> true), fixed(0)).isEmpty());
        assertTrue(Rewards.forVote(rewards, voter(1, false, true, p -> true), fixed(0)).isEmpty());
        assertEquals(1, Rewards.forVote(rewards, voter(1, false, false, p -> true), fixed(0)).size());
    }

    @Test
    void permissionAndServiceFiltersApply() {
        final var rewards = List.of(new Settings.Reward("a", 100, "pinatafest.vip", false, false, false, false,
                Set.of("sitea"), List.of("x")));
        assertTrue(Rewards.forVote(rewards, voter(1, false, false, p -> false), fixed(0)).isEmpty());
        assertEquals(1, Rewards.forVote(rewards, voter(1, false, false, p -> true), fixed(0)).size());
        assertTrue(Rewards.forVote(rewards, new Rewards.Voter("Steve", "SiteB", 1, false, false, p -> true),
                fixed(0)).isEmpty());
    }

    @Test
    void milestonesFireOnTotalsAndMultiples() {
        final var milestones = List.of(
                new Settings.Milestone("ten", Settings.MilestoneType.TOTAL, 10, null, List.of("ten %votes%")),
                new Settings.Milestone("four", Settings.MilestoneType.EVERY, 4, null, List.of("four")));

        assertEquals(List.of("ten 10"), Rewards.forMilestones(milestones, voter(10, false, false, p -> true)));
        assertEquals(List.of("four"), Rewards.forMilestones(milestones, voter(8, false, false, p -> true)));
        assertTrue(Rewards.forMilestones(milestones, voter(9, false, false, p -> true)).isEmpty());
        assertTrue(Rewards.forMilestones(milestones, voter(20, false, false, p -> true)).size() == 1);
    }
}
