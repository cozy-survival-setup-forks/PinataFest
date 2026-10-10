package dev.pinatafest.vote;

import dev.pinatafest.config.Settings;
import dev.pinatafest.message.Messages;
import dev.pinatafest.pinata.PinataService;
import dev.pinatafest.reward.Rewards;
import dev.pinatafest.safe.Journal;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.plugin.Plugin;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
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

    /** A vote site that never got our ack resends the very same vote, stamp included, so the recent
     * ones are remembered to stop it being paid twice. Key is name + service + stamp, value is when it was seen.
     * Two separate votes always have different stamps, so fast voting is never mistaken for a resend. */
    private final Map<String, Long> recentVotes = new ConcurrentHashMap<>();
    private static final long DUPLICATE_WINDOW_MILLIS = 120_000;

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

    /** Entry point for a vote from any thread, without a stamp (commands, tests). */
    public void receive(String username, String service) {
        receive(username, service, null);
    }

    /** Entry point for a vote from any thread. The stamp is the time the vote site put on it, or null. */
    public void receive(String username, String service, String stamp) {
        if (!validName(username)) {
            plugin.getLogger().warning("Ignored a vote with an invalid username.");
            return;
        }
        final String cleaned = cleanService(service);
        if (stamp != null && isDuplicate(username, cleaned, stamp)) {
            return;
        }
        if (Bukkit.isPrimaryThread()) {
            handle(username, cleaned);
        } else {
            Bukkit.getScheduler().runTask(plugin, () -> handle(username, cleaned));
        }
    }

    /** True if this exact vote was seen in the last while. Also records it. */
    private boolean isDuplicate(String username, String service, String stamp) {
        final long now = System.currentTimeMillis();
        recentVotes.entrySet().removeIf(e -> now - e.getValue() > DUPLICATE_WINDOW_MILLIS);
        return recentVotes.put(username.toLowerCase(Locale.ROOT) + '|' + service + '|' + stamp, now) != null;
    }

    private void handle(String username, String service) {
        final Settings.Votes votes = settings.get().votes();
        if (!votes.listen()) {
            return;
        }
        checkMonth();

        // the vote counts for the player and for the pinata whether or not they are online
        final Player player = Bukkit.getPlayerExact(username);
        final String name = player != null ? player.getName() : username;
        store.entry(name).addVote(System.currentTimeMillis());
        pinatas.countVote(name);
        announce(name, votes.announceWindowTicks());

        Rewards.once(settings.get().rewards().vote(), ThreadLocalRandom.current())
                .forEach(command -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command));
        if (player != null) {
            grant(player, service, false);
            playEffects(player);
        } else if (votes.queueRewards()) {
            final VoteStore.Entry entry = store.entry(name);
            if (votes.maxQueue() == 0 || entry.queue().size() < votes.maxQueue()) {
                entry.queue().add(new VoteStore.Queued(service, System.currentTimeMillis()));
            }
        }
        store.saveSoon(plugin.getLogger());
    }

    /** Starts a new month when the first has come, in the configured time zone. @return true if the monthly votes were reset */
    public boolean checkMonth() {
        final String now = java.time.YearMonth.now(settings.get().votes().monthlyZone()).toString();
        final boolean reset = store.rollover(now);
        if (reset) {
            plugin.getLogger().info("New month (" + now + "), monthly votes reset.");
        }
        return reset;
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
        final Journal journal = store.journal();
        String record = null;
        if (journal != null) {
            try {
                // written before anything is paid: a stop in the middle is then known about, and never paid again
                record = journal.begin("queued-votes", "player=" + player.getName() + " count=" + waiting.size()
                        + " services=" + waiting.stream().map(VoteStore.Queued::service).collect(Collectors.joining(",")));
            } catch (SQLException e) {
                plugin.getLogger().severe("The payout record could not be written, so the queued rewards of " + player.getName()
                        + " were not paid yet. They stay queued. " + e.getMessage());
                return;
            }
        }
        entry.queue().clear();
        // on disk before anything is paid: if the server dies right after, the queue must not come back
        if (!store.flush(plugin.getLogger())) {
            entry.queue().addAll(waiting);
            plugin.getLogger().severe("The queue of " + player.getName() + " could not be saved, so nothing was paid and the rewards stay queued.");
            finish(journal, record, "queue could not be saved", false);
            return;
        }

        messages.send(player, "queued_paid", Messages.text("count", waiting.size()));
        waiting.forEach(vote -> grant(player, vote.service(), true));
        playEffects(player);
        finish(journal, record, null, true);
    }

    private void finish(Journal journal, String record, String reason, boolean ok) {
        if (journal == null || record == null) {
            return;
        }
        try {
            if (ok) {
                journal.succeeded(record);
            } else {
                journal.failed(record, reason);
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("The payout record could not be finished: " + e.getMessage());
        }
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
