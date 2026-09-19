package dev.pinatafest.reward;

import dev.pinatafest.config.Settings;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.random.RandomGenerator;

/**
 * Works out which commands a vote earns. It only builds the list, running the commands is left to
 * the caller, which keeps the rolling easy to test.
 */
public final class Rewards {

    /** Who voted and what is known about them right now. */
    public record Voter(String name, String service, int totalVotes, boolean vanished, boolean votedOffline,
                        Predicate<String> hasPermission) {
    }

    private Rewards() {
    }

    /** Commands from the per-vote rewards, rolled in config order. */
    public static List<String> forVote(List<Settings.Reward> rewards, Voter voter, RandomGenerator random) {
        final List<String> commands = new ArrayList<>();
        for (Settings.Reward reward : rewards) {
            if (!eligible(reward, voter) || random.nextDouble() * 100.0 >= reward.chance()) {
                continue;
            }
            if (reward.randomLine()) {
                commands.add(fill(reward.commands().get(random.nextInt(reward.commands().size())), voter));
            } else {
                reward.commands().forEach(command -> commands.add(fill(command, voter)));
            }
            if (reward.stop()) {
                break;
            }
        }
        return commands;
    }

    /** Commands from the milestones the new vote total has just reached. */
    public static List<String> forMilestones(List<Settings.Milestone> milestones, Voter voter) {
        final List<String> commands = new ArrayList<>();
        for (Settings.Milestone milestone : milestones) {
            final boolean reached = milestone.type() == Settings.MilestoneType.TOTAL
                    ? voter.totalVotes() == milestone.votes()
                    : voter.totalVotes() % milestone.votes() == 0;
            if (reached && permitted(milestone.permission(), voter)) {
                milestone.commands().forEach(command -> commands.add(fill(command, voter)));
            }
        }
        return commands;
    }

    private static boolean eligible(Settings.Reward reward, Voter voter) {
        if (reward.ignoreVanished() && voter.vanished()) {
            return false;
        }
        if (reward.ignoreOffline() && voter.votedOffline()) {
            return false;
        }
        if (!reward.services().isEmpty() && !reward.services().contains(voter.service().toLowerCase(Locale.ROOT))) {
            return false;
        }
        return permitted(reward.permission(), voter);
    }

    private static boolean permitted(String permission, Voter voter) {
        return permission == null || permission.isBlank() || voter.hasPermission().test(permission);
    }

    private static String fill(String command, Voter voter) {
        return command
                .replace("%player%", voter.name())
                .replace("%service%", voter.service())
                .replace("%votes%", Integer.toString(voter.totalVotes()));
    }
}
