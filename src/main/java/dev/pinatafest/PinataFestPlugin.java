package dev.pinatafest;

import dev.pinatafest.command.PinataFestCommand;
import dev.pinatafest.command.Perms;
import dev.pinatafest.config.Settings;
import dev.pinatafest.hook.PapiHook;
import dev.pinatafest.hook.VotifierHook;
import dev.pinatafest.message.Messages;
import dev.pinatafest.vote.VoteService;
import dev.pinatafest.vote.VoteStore;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.util.List;

public class PinataFestPlugin extends JavaPlugin implements Listener {

    private static final long SAVE_EVERY_TICKS = 20L * 60 * 5;
    private static final long PAYOUT_DELAY_TICKS = 40L;

    private volatile Settings settings;
    private Messages messages;
    private VoteStore store;
    private VoteService votes;
    private BukkitTask reminderTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        settings = Settings.load(getConfig(), getLogger());
        Perms.register(getServer().getPluginManager());

        store = new VoteStore(getDataFolder().toPath().resolve("votes.yml"));
        store.load(getLogger());
        messages = new Messages(this);
        messages.load();
        votes = new VoteService(this, () -> settings, store, messages);

        if (!VotifierHook.register(this, votes::receive)) {
            getLogger().warning("No Votifier, NuVotifier or VotifierPlus found. Votes will not arrive until one is installed"
                    + " (/pinatafest fake still works for testing).");
        }
        if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            new PapiHook(this, store).register();
        }

        getServer().getPluginManager().registerEvents(this, this);
        final PinataFestCommand commands = new PinataFestCommand(this, () -> settings, store, votes, messages);
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            event.registrar().register(commands.vote().build(), "Show the vote links", List.of());
            event.registrar().register(commands.pinatafest().build(), "Vote counts and admin tools", List.of());
        });

        getServer().getScheduler().runTaskTimer(this, this::saveAsync, SAVE_EVERY_TICKS, SAVE_EVERY_TICKS);
        scheduleReminder();
    }

    @Override
    public void onDisable() {
        if (store != null && store.isDirty()) {
            try {
                store.write(store.snapshot());
            } catch (IOException e) {
                getLogger().severe("Could not save votes.yml: " + e.getMessage());
            }
        }
    }

    /** Re-reads config.yml and lang.yml. */
    public void reload() {
        reloadConfig();
        settings = Settings.load(getConfig(), getLogger());
        messages.load();
        scheduleReminder();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        final Player player = event.getPlayer();
        // give the client a moment to finish joining before rewards land in the inventory
        getServer().getScheduler().runTaskLater(this, () -> {
            if (player.isOnline()) {
                votes.payQueued(player);
            }
        }, PAYOUT_DELAY_TICKS);
    }

    private void scheduleReminder() {
        if (reminderTask != null) {
            reminderTask.cancel();
            reminderTask = null;
        }
        final Settings.Reminder reminder = settings.reminder();
        if (!reminder.enabled()) {
            return;
        }
        final long ticks = Math.max(20L, reminder.every().toSeconds() * 20L);
        reminderTask = getServer().getScheduler().runTaskTimer(this, this::remind, ticks, ticks);
    }

    private void remind() {
        final long cutoff = System.currentTimeMillis() - settings.reminder().after().toMillis();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission(Perms.NO_REMINDER)) {
                continue;
            }
            final VoteStore.Entry entry = store.find(player.getName());
            if (entry == null || entry.lastVote() < cutoff) {
                messages.send(player, "reminder");
            }
        }
    }

    /** Takes the snapshot on the main thread and writes it from another one. */
    private void saveAsync() {
        if (!store.isDirty()) {
            return;
        }
        final String snapshot = store.snapshot();
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            try {
                store.write(snapshot);
            } catch (IOException e) {
                getLogger().severe("Could not save votes.yml: " + e.getMessage());
            }
        });
    }
}
