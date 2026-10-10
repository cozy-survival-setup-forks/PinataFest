package dev.pinatafest;

import dev.pinatafest.command.Perms;
import dev.pinatafest.command.PinataFestCommand;
import dev.pinatafest.config.Settings;
import dev.pinatafest.hook.PapiHook;
import dev.pinatafest.hook.VotifierHook;
import dev.pinatafest.message.Messages;
import dev.pinatafest.pinata.PinataListener;
import dev.pinatafest.pinata.PinataService;
import dev.pinatafest.safe.ConfigMigrator;
import dev.pinatafest.safe.Doctor;
import dev.pinatafest.safe.FileBackups;
import dev.pinatafest.safe.Guard;
import dev.pinatafest.safe.Health;
import dev.pinatafest.safe.Prep;
import dev.pinatafest.safe.ServerId;
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
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class PinataFestPlugin extends JavaPlugin implements Listener {

    private static final long SAVE_EVERY_TICKS = 20L * 60 * 5;
    private static final long PAYOUT_DELAY_TICKS = 40L;
    private static final long MONTH_CHECK_TICKS = 20L * 5;

    private static final int CONFIG_VERSION = 1;
    private static final int LANG_VERSION = 1;

    private final List<Prep.Spec> files = List.of(
            new Prep.Spec("config.yml", "config-version", CONFIG_VERSION, Prep.configMigrator(CONFIG_VERSION), rules -> {
                rules.range("backup.interval-hours", 1, 168);
                rules.range("backup.keep", 1, 90);
            }),
            new Prep.Spec("lang.yml", "lang-version", LANG_VERSION, new ConfigMigrator("lang-version", LANG_VERSION), null));

    private volatile Settings settings;
    private Messages messages;
    private BukkitTask backupTask;
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
        Health.storage("SQLite votes.db for votes and queued rewards; YAML for config.yml, lang.yml and spawns.yml");
        Prep.startup(this, files);
        YamlConfiguration config = parseConfig();
        if (config == null) {
            getLogger().severe("config.yml has a mistake in it, so the built-in settings are used until it is fixed and reloaded.");
            config = bundledConfig();
        }
        settings = Settings.load(config, getLogger());
        Perms.register(getServer().getPluginManager());

        store = new VoteStore(getDataFolder().toPath().resolve("votes.yml"));
        try {
            store.load(getLogger(), backupKeep(config));
        } catch (IOException | SQLException e) {
            // the vote data is never replaced by an empty set: the plugin stays off until it is sorted out
            getLogger().severe("PinataFest cannot use its vote data and is switching itself off so nothing is reset or paid twice: "
                    + e.getMessage());
            Health.failure("vote data could not be opened: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
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
        scheduleBackups(config);
        // the reset on the first of the month, checked every few seconds so it lands on time and cheaply
        getServer().getScheduler().runTaskTimer(this, votes::checkMonth, 20L, MONTH_CHECK_TICKS);
        pinatas.start();
        final boolean beacon = config.getBoolean("metrics.enabled", true);
        Metrics.start(this, ServerId.resolve(getDataFolder().toPath(), store.serverIdSlot(), beacon, getLogger()));
        Banner.print(this, "Thanks for making every vote feel like a party.");
    }

    @Override
    public void onDisable() {
        if (pinatas != null) {
            pinatas.stop();
        }
        if (backupTask != null) {
            backupTask.cancel();
        }
        if (store != null) {
            // waits (a few seconds at most) for the saves still running, then writes what is left
            store.close(getLogger());
        }
    }

    /**
     * Re-reads config.yml and lang.yml. Pinatas that are already out keep the look they were spawned with.
     *
     * @return false if a file has a mistake in it: that file is left as it was loaded before
     */
    public boolean reload() {
        final List<Guard.Problem> problems = Prep.validate(this, files);
        if (!problems.isEmpty()) {
            Prep.logRejected(this, problems);
            return false;
        }
        final YamlConfiguration config = parseConfig();
        boolean ok = config != null;
        if (ok) {
            settings = Settings.load(config, getLogger());
            scheduleBackups(config);
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

    /** Takes the snapshot on the main thread and has it written from another one, in order with the other saves. */
    private void saveAsync() {
        store.saveSoon(getLogger());
    }

    private static int backupKeep(YamlConfiguration config) {
        return Math.max(1, Math.min(90, config.getInt("backup.keep", 7)));
    }

    /** (Re)starts the timer of the database copies from backup.interval-hours and backup.keep. */
    private void scheduleBackups(YamlConfiguration config) {
        final int hours = Math.max(1, Math.min(168, config.getInt("backup.interval-hours", 6)));
        store.configureBackups(backupKeep(config), getLogger());
        if (backupTask != null) {
            backupTask.cancel();
        }
        backupTask = getServer().getScheduler().runTaskTimerAsynchronously(this, store::backup, 20L * 60, hours * 3600L * 20L);
    }

    /** The text of /pinatafest doctor. */
    public List<String> doctor() {
        final List<String> extra = new ArrayList<>(Prep.versionLines(this, files));
        extra.add("Vote database: votes.db, schema " + store.schemaVersion() + " (this plugin writes " + VoteStore.SCHEMA + ")");
        extra.add("Stored: " + store.summary());
        extra.add("Newest database copy on disk: " + (store.newestBackup() == null ? "none yet" : store.newestBackup()));
        extra.add("Pending writes: " + store.pendingSaves() + ", failed background saves: " + store.failedSaves());
        try {
            final List<String> unknown = store.journal() == null ? List.of() : store.journal().unknown();
            extra.add("Payouts that may or may not have been made (not repeated): " + unknown.size());
            unknown.forEach(line -> extra.add("  " + line + "   (after checking: /pinatafest doctor resolve <id>)"));
        } catch (SQLException e) {
            extra.add("Payout record could not be read: " + e.getMessage());
        }
        return Doctor.report(getName(), getPluginMeta().getVersion(), extra);
    }

    /** /pinatafest doctor resolve: a person has checked a payout that was left unfinished. */
    public boolean resolvePayout(String id) {
        try {
            return store.journal() != null && store.journal().resolve(id);
        } catch (SQLException e) {
            getLogger().severe("Could not update the payout record: " + e.getMessage());
            return false;
        }
    }

    /** /pinatafest backup now: a checked copy of the database and of the settings files. */
    public boolean backupNow() {
        final boolean saved = store.flush(getLogger());
        final List<String> names = new ArrayList<>(Prep.fileNames(files));
        names.add("spawns.yml");
        final boolean database = store.backup();
        final boolean settingsFiles = FileBackups.snapshot(getDataFolder().toPath(), names, 5, getLogger());
        return saved && database && settingsFiles;
    }
}
