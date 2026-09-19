package dev.pinatafest.reward;

import dev.pinatafest.config.Settings;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.random.RandomGenerator;

/**
 * Works out which commands a reward list earns. It only builds the list, running the commands is
 * left to the caller, which keeps the rolling easy to test.
 */
public final class Rewards {

    /** Who is being rewarded and what is known about them right now. */
    public record Voter(String name, String service, int totalVotes, boolean vanished, boolean votedOffline,
                        Predicate<String> hasPermission) {
    }

    private Rewards() {
    }

    /** Commands for one player, rolled in config order. Entries marked once are left out. */
    public static List<String> forPlayer(List<Settings.Reward> rewards, Voter voter, RandomGenerator random) {
        final List<String> commands = new ArrayList<>();
        for (Settings.Reward reward : rewards) {
            if (reward.once() || !eligible(reward, voter) || !rolled(reward, random)) {
                continue;
            }
            addCommands(commands, reward, voter, random);
            if (reward.stop()) {
                break;
            }
        }
        return commands;
    }

    /** Commands from entries marked once, which run one time for everybody and never mention a player. */
    public static List<String> once(List<Settings.Reward> rewards, RandomGenerator random) {
        final Voter nobody = new Voter("", "", 0, false, false, permission -> true);
        final List<String> commands = new ArrayList<>();
        for (Settings.Reward reward : rewards) {
            if (!reward.once() || !rolled(reward, random)) {
                continue;
            }
            addCommands(commands, reward, nobody, random);
            if (reward.stop()) {
                break;
            }
        }
        return commands;
    }

    private static boolean rolled(Settings.Reward reward, RandomGenerator random) {
        return random.nextDouble() * 100.0 < reward.chance();
    }

    private static void addCommands(List<String> out, Settings.Reward reward, Voter voter, RandomGenerator random) {
        if (reward.randomLine()) {
            out.add(fill(reward.commands().get(random.nextInt(reward.commands().size())), voter));
        } else {
            reward.commands().forEach(command -> out.add(fill(command, voter)));
        }
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
        final String permission = reward.permission();
        return permission == null || permission.isBlank() || voter.hasPermission().test(permission);
    }

    private static String fill(String command, Voter voter) {
        return command
                .replace("%player%", voter.name())
                .replace("%service%", voter.service())
                .replace("%votes%", Integer.toString(voter.totalVotes()));
    }
}
