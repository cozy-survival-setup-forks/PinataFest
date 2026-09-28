package dev.pinatafest;

import dev.pinatafest.command.Perms;
import dev.pinatafest.command.PinataFestCommand;
import dev.pinatafest.config.Settings;
import dev.pinatafest.hook.PapiHook;
import dev.pinatafest.hook.VotifierHook;
import dev.pinatafest.message.Messages;
import dev.pinatafest.pinata.PinataListener;
import dev.pinatafest.pinata.PinataService;
import dev.pinatafest.spawn.SpawnStore;
import dev.pinatafest.vote.VoteService;
import dev.pinatafest.vote.VoteStore;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class PinataFestPlugin extends JavaPlugin implements Listener {

    private static final long SAVE_EVERY_TICKS = 20L * 60 * 5;
    private static final long PAYOUT_DELAY_TICKS = 40L;
    private static final long MONTH_CHECK_TICKS = 20L * 5;

    private volatile Settings settings;
    private Messages messages;
    private VoteStore store;
    private SpawnStore spawns;
    private VoteService votes;
    private PinataService pinatas;

    @Override
    public void onEnable() {
        try {
            enableInner();
        } catch (RuntimeException e) {
            getLogger().log(java.util.logging.Level.SEVERE, "PinataFest could not start, check config.yml, lang.yml, votes.yml and spawns.yml for mistakes", e);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    private void enableInner() {
        saveDefaultConfig();
        YamlConfiguration config = parseConfig();
        if (config == null) {
            getLogger().severe("config.yml has a mistake in it, so the built-in settings are used until it is fixed and reloaded.");
            config = bundledConfig();
        }
        settings = Settings.load(config, getLogger());
        Perms.register(getServer().getPluginManager());

        store = new VoteStore(getDataFolder().toPath().resolve("votes.yml"));
        store.load(getLogger());
        messages = new Messages(this);
        messages.load();
        spawns = new SpawnStore(getDataFolder().toPath().resolve("spawns.yml"));
        spawns.load(getLogger());
        pinatas = new PinataService(this, () -> settings, messages, store, spawns);
        votes = new VoteService(this, () -> settings, store, messages, pinatas);

        if (!VotifierHook.register(this, votes::receive)) {
            getLogger().warning("No Votifier, NuVotifier or VotifierPlus found. Votes will not arrive until one is installed"
                    + " (/pinatafest fake still works for testing).");
        }
        if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            new PapiHook(this, store, pinatas).register();
        }

        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(new PinataListener(this, pinatas), this);

        final PinataFestCommand commands = new PinataFestCommand(this, () -> settings, store, votes, pinatas, spawns, messages);
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            event.registrar().register(commands.pinatafest().build(), "Votes, pinatas and admin tools", List.of("pf"));
            event.registrar().register(commands.visibility().build(), "Hide other players during pinata parties", List.of());
        });

        getServer().getScheduler().runTaskTimer(this, this::saveAsync, SAVE_EVERY_TICKS, SAVE_EVERY_TICKS);
        // the reset on the first of the month, checked every few seconds so it lands on time and cheaply
        getServer().getScheduler().runTaskTimer(this, votes::checkMonth, 20L, MONTH_CHECK_TICKS);
        pinatas.start();
        Banner.print(this, "Thanks for making every vote feel like a party.");
    }

    @Override
    public void onDisable() {
        if (pinatas != null) {
            pinatas.stop();
        }
        if (store != null && store.isDirty()) {
            try {
                store.write(store.snapshot());
            } catch (IOException e) {
                getLogger().severe("Could not save votes.yml: " + e.getMessage());
            }
        }
    }

    /**
     * Re-reads config.yml and lang.yml. Pinatas that are already out keep the look they were spawned with.
     *
     * @return false if a file has a mistake in it: that file is left as it was loaded before
     */
    public boolean reload() {
        final YamlConfiguration config = parseConfig();
        boolean ok = config != null;
        if (ok) {
            settings = Settings.load(config, getLogger());
        }
        spawns.load(getLogger());
        return messages.load() && ok;
    }

    /** config.yml as a fresh parse, or null (and a message in the log) if it is broken. */
    private YamlConfiguration parseConfig() {
        final YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(new File(getDataFolder(), "config.yml"));
            return yaml;
        } catch (IOException | InvalidConfigurationException e) {
            getLogger().log(java.util.logging.Level.SEVERE, "config.yml could not be read: " + e.getMessage());
            return null;
        }
    }

    private YamlConfiguration bundledConfig() {
        return YamlConfiguration.loadConfiguration(new InputStreamReader(getResource("config.yml"), StandardCharsets.UTF_8));
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
                getServer().getScheduler().runTask(this, store::writeFailed);
            }
        });
    }
}
