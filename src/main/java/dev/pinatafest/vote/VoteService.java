package dev.pinatafest.vote;

import dev.pinatafest.config.Settings;
import dev.pinatafest.message.Messages;
import dev.pinatafest.pinata.PinataService;
import dev.pinatafest.reward.Rewards;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * Turns a received vote into a count towards the next pinata and rewards for the voter, either
 * straight away or, for a player who is offline, when they next log in.
 */
public final class VoteService {

    private final Plugin plugin;
    private final Supplier<Settings> settings;
    private final VoteStore store;
    private final Messages messages;
    private final PinataService pinatas;

    public VoteService(Plugin plugin, Supplier<Settings> settings, VoteStore store, Messages messages,
                       PinataService pinatas) {
        this.plugin = plugin;
        this.settings = settings;
        this.store = store;
        this.messages = messages;
        this.pinatas = pinatas;
    }

    /** Entry point for a vote from any thread. */
    public void receive(String username, String service) {
        if (Bukkit.isPrimaryThread()) {
            handle(username, service);
        } else {
            Bukkit.getScheduler().runTask(plugin, () -> handle(username, service));
        }
    }

    private void handle(String username, String service) {
        final Settings.Votes votes = settings.get().votes();
        if (!votes.listen()) {
            return;
        }

        // the vote counts for the player and for the pinata whether or not they are online
        final Player player = Bukkit.getPlayerExact(username);
        final String name = player != null ? player.getName() : username;
        store.entry(name).addVote(System.currentTimeMillis());
        pinatas.countVote(name);

        if (player != null) {
            grant(player, service, false);
            playEffects(player);
        } else if (votes.queueRewards()) {
            final VoteStore.Entry entry = store.entry(name);
            if (votes.maxQueue() == 0 || entry.queue().size() < votes.maxQueue()) {
                entry.queue().add(new VoteStore.Queued(service, System.currentTimeMillis()));
            }
        }
    }

    /** Pays out the rewards of votes cast while this player was away. */
    public void payQueued(Player player) {
        final VoteStore.Entry entry = store.find(player.getName());
        if (entry == null || entry.queue().isEmpty()) {
            return;
        }

        final List<VoteStore.Queued> waiting = new ArrayList<>(entry.queue());
        entry.queue().clear();
        store.markDirty();

        messages.send(player, "queued_paid", Messages.text("count", waiting.size()));
        waiting.forEach(vote -> grant(player, vote.service(), true));
        playEffects(player);
    }

    private void grant(Player player, String service, boolean votedOffline) {
        final VoteStore.Entry entry = store.entry(player.getName());
        final Rewards.Voter voter = new Rewards.Voter(player.getName(), service, entry.total(),
                isVanished(player), votedOffline, player::hasPermission);
        Rewards.forPlayer(settings.get().rewards().vote(), voter, ThreadLocalRandom.current())
                .forEach(command -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command));
    }

    private void playEffects(Player player) {
        final Settings.Effects effects = settings.get().votes().effects();
        if (effects.particle() != null && effects.count() > 0) {
            final Location at = player.getLocation().add(0, 1, 0);
            player.getWorld().spawnParticle(effects.particle(), at, effects.count(),
                    effects.spreadX(), effects.spreadY(), effects.spreadZ(), effects.speed());
        }
        if (effects.sound() != null) {
            player.playSound(effects.sound());
        }
    }

    @SuppressWarnings("deprecation")
    private static boolean isVanished(Player player) {
        for (MetadataValue value : player.getMetadata("vanished")) {
            if (value.asBoolean()) {
                return true;
            }
        }
        return false;
    }
}
