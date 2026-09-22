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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiConsumer;
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

    /** Votes waiting to be announced, by player, while more from the same player may still arrive. */
    private final Map<String, Integer> pending = new HashMap<>();
    private BiConsumer<String, Integer> announcer = this::broadcastVote;

    /** A vote site that never got our ack resends the same vote, so the last few are remembered here
     * to stop it being paid twice. Key is name + service, value is when it was first seen. */
    private final Map<String, Long> recentVotes = new ConcurrentHashMap<>();
    private static final long DUPLICATE_WINDOW_MILLIS = 4_000;

    public VoteService(Plugin plugin, Supplier<Settings> settings, VoteStore store, Messages messages,
                       PinataService pinatas) {
        this.plugin = plugin;
        this.settings = settings;
        this.store = store;
        this.messages = messages;
        this.pinatas = pinatas;
    }

    /** What a player name can look like: Java names, and Bedrock names with a prefix such as a dot. */
    private static final java.util.regex.Pattern NAME = java.util.regex.Pattern.compile("[A-Za-z0-9_.*-]{1,32}");

    /** Service names end up in reward commands, so only plain characters are kept. */
    static String cleanService(String service) {
        final String kept = service == null ? "" : service.replaceAll("[^A-Za-z0-9_.-]", "");
        return kept.isEmpty() ? "unknown" : kept.substring(0, Math.min(64, kept.length()));
    }

    /** True if a vote's username can safely be stored and shown. Anything else is dropped. */
    static boolean validName(String username) {
        return username != null && NAME.matcher(username).matches();
    }

    /** Entry point for a vote from any thread. */
    public void receive(String username, String service) {
        if (!validName(username)) {
            plugin.getLogger().warning("Ignored a vote with an invalid username.");
            return;
        }
        final String cleaned = cleanService(service);
        if (isDuplicate(username, cleaned)) {
            return;
        }
        if (Bukkit.isPrimaryThread()) {
            handle(username, cleaned);
        } else {
            Bukkit.getScheduler().runTask(plugin, () -> handle(username, cleaned));
        }
    }

    /** True if the same name and service voted within the last few seconds. Also records this one. */
    private boolean isDuplicate(String username, String service) {
        final long now = System.currentTimeMillis();
        recentVotes.entrySet().removeIf(e -> now - e.getValue() > DUPLICATE_WINDOW_MILLIS);
        return recentVotes.put(username.toLowerCase(Locale.ROOT) + '|' + service, now) != null;
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
        announce(name, votes.announceWindowTicks());

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

    /** Tells everyone about a vote, joining the votes that follow within the window into one message. */
    private void announce(String name, int windowTicks) {
        if (windowTicks <= 0) {
            announcer.accept(name, 1);
            return;
        }
        if (pending.merge(name, 1, Integer::sum) == 1) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                final Integer count = pending.remove(name);
                if (count != null) {
                    announcer.accept(name, count);
                }
            }, windowTicks);
        }
    }

    private void broadcastVote(String name, int count) {
        if (count == 1) {
            messages.broadcast("vote_broadcast", Messages.text("player", name));
        } else {
            messages.broadcast("vote_broadcast_multiple", Messages.text("player", name), Messages.text("count", count));
        }
    }

    /** For tests: replaces what is done with an announcement. */
    void announcer(BiConsumer<String, Integer> announcer) {
        this.announcer = announcer;
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
