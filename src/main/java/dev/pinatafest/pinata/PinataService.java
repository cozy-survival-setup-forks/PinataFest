package dev.pinatafest.pinata;

import dev.pinatafest.config.Settings;
import dev.pinatafest.message.Messages;
import dev.pinatafest.reward.Rewards;
import dev.pinatafest.spawn.SpawnPoint;
import dev.pinatafest.spawn.SpawnStore;
import dev.pinatafest.vote.VoteStore;
import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Llama;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * Runs the pinata game: counts votes towards a party, runs the countdown, spawns pinatas, handles
 * hits and hands out rewards. One task ticks everything, so any number of pinatas costs a single
 * scheduler entry.
 */
public final class PinataService {

    private static final BossBar.Color[] BAR_COLORS = BossBar.Color.values();
    private static final double NEARBY_BLOCKS = 64.0;

    /** A countdown that ends with pinatas appearing. */
    private static final class Party {
        private final Location where;
        private final int amount;
        private final int total;
        private final BossBar bar;
        private final Set<UUID> viewers = new HashSet<>();
        private int left;

        Party(Location where, int amount, int ticks, Settings.Countdown settings) {
            this.where = where;
            this.amount = amount;
            this.total = ticks;
            this.left = ticks;
            this.bar = BossBar.bossBar(net.kyori.adventure.text.Component.empty(), 1f,
                    settings.color() == null ? BossBar.Color.PINK : settings.color(), settings.overlay());
        }
    }

    private final Plugin plugin;
    private final Supplier<Settings> settings;
    private final Messages messages;
    private final VoteStore store;
    private final SpawnStore spawns;
    private final PartyVisibility visibility;
    private final Map<UUID, Pinata> active = new HashMap<>();
    private final List<Party> parties = new ArrayList<>();

    private BukkitTask ticker;
    private long tick;
    private boolean wasActive;

    public PinataService(Plugin plugin, Supplier<Settings> settings, Messages messages, VoteStore store,
                         SpawnStore spawns) {
        this.plugin = plugin;
        this.settings = settings;
        this.messages = messages;
        this.store = store;
        this.spawns = spawns;
        this.visibility = new PartyVisibility(plugin, settings, this::activeWorlds);
    }

    public void start() {
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    /** Stops everything and removes all pinatas, for shutdown. */
    public void stop() {
        if (ticker != null) {
            ticker.cancel();
            ticker = null;
        }
        killAll();
        visibility.restoreAll();
        final var board = Bukkit.getScoreboardManager().getMainScoreboard();
        board.getTeams().stream().filter(team -> team.getName().startsWith(Pinata.TEAM_PREFIX)).forEach(team -> {
            if (team.getEntries().isEmpty()) {
                team.unregister();
            }
        });
    }

    public PartyVisibility visibility() {
        return visibility;
    }

    public Pinata pinataOf(org.bukkit.entity.Entity entity) {
        return active.get(entity.getUniqueId());
    }

    // ---- votes and parties ----

    /** Counts a vote towards the next pinata. Offline votes count too. */
    public void countVote(String voter) {
        final Settings.Party party = settings.get().party();
        final List<String> places = party.locations().isEmpty() ? List.copyOf(spawnPoints().keySet()) : party.locations();
        if (party.votesNeeded() == 0 || places.isEmpty()) {
            return;
        }

        final int count = store.pinataVotes() + 1;
        if (count >= party.votesNeeded()) {
            // the votes are only used up once there is somewhere to put the pinata
            final Location where = anySpot(places);
            if (where == null) {
                store.setPinataVotes(Math.min(count, party.votesNeeded()));
                plugin.getLogger().warning("The party goal was reached but none of the spawn points can be used, the votes are kept."
                        + " Check the world names of the spawn points.");
                return;
            }
            store.setPinataVotes(count - party.votesNeeded());
            messages.broadcast("vote_goal", Messages.text("player", voter));
            begin(where, party.amount());
        } else {
            store.setPinataVotes(count);
            messages.broadcast("vote_progress", Messages.text("player", voter), Messages.text("count", count),
                    Messages.text("needed", party.votesNeeded()));
        }
    }

    public int votesUntilNext() {
        return Math.max(0, settings.get().party().votesNeeded() - store.pinataVotes());
    }

    /** Every spawn point: the ones from config.yml, then those saved in game, which win on a shared name. */
    public Map<String, SpawnPoint> spawnPoints() {
        final Map<String, SpawnPoint> all = new LinkedHashMap<>(settings.get().party().spots());
        all.putAll(spawns.all());
        return all;
    }

    public Collection<String> locationNames() {
        return spawnPoints().keySet();
    }

    /** Starts a party at a named spawn point. Returns false if the point or its world is not usable. */
    public boolean summon(String spotName, int amount) {
        final Location where = spot(spotName);
        if (where == null) {
            return false;
        }
        begin(where, amount);
        return true;
    }

    /** A place to put a pinata at a named spawn point, or null if the point or its world is not usable. */
    private Location spot(String spotName) {
        final SpawnPoint point = spawnPoints().get(spotName.toLowerCase(Locale.ROOT));
        if (point == null) {
            return null;
        }
        final Location where = point.pick(ThreadLocalRandom.current());
        if (where == null) {
            plugin.getLogger().warning("Spawn point " + spotName + " uses world " + point.world() + ", which is not loaded");
        }
        return where;
    }

    /** Tries the given spawn points in random order and returns the first usable place. */
    private Location anySpot(List<String> places) {
        final List<String> order = new ArrayList<>(places);
        java.util.Collections.shuffle(order, ThreadLocalRandom.current());
        for (String name : order) {
            final Location where = spot(name);
            if (where != null) {
                return where;
            }
        }
        return null;
    }

    private void begin(Location where, int amount) {
        final Settings.Countdown countdown = settings.get().party().countdown();
        if (!countdown.enabled()) {
            spawnAll(where, amount);
            return;
        }
        final Party party = new Party(where, amount, countdown.seconds() * 20, countdown);
        parties.add(party);
        messages.broadcast("party_start", Messages.text("seconds", countdown.seconds()));
        play(countdown.start());
    }

    private void spawnAll(Location where, int amount) {
        for (int i = 0; i < amount; i++) {
            final Location at = i == 0 ? where.clone() : scatter(where);
            spawn(at);
        }
        messages.broadcast("pinata_spawn", Messages.text("x", where.getBlockX()), Messages.text("y", where.getBlockY()),
                Messages.text("z", where.getBlockZ()), Messages.text("world", where.getWorld().getName()));
    }

    private static Location scatter(Location center) {
        final ThreadLocalRandom random = ThreadLocalRandom.current();
        final double x = center.getX() + random.nextDouble(-4, 4);
        final double z = center.getZ() + random.nextDouble(-4, 4);
        final World world = center.getWorld();
        return new Location(world, x, Math.max(center.getY(), world.getHighestBlockYAt((int) Math.floor(x), (int) Math.floor(z)) + 1), z);
    }

    /** Creates one pinata right here, without any countdown. */
    public Pinata spawn(Location at) {
        final Settings.PinataSettings config = settings.get().pinata();
        final Settings.Health health = config.health();
        int hits = health.base() + health.perPlayer() * Bukkit.getOnlinePlayers().size();
        if (health.max() > 0) {
            hits = Math.min(hits, health.max());
        }
        return track(at.getWorld().spawn(at, Llama.class), hits);
    }

    /** Starts running a llama as a pinata. Split from spawn so the game rules can be tested. */
    Pinata track(Llama llama, int hits) {
        final Pinata pinata = new Pinata(llama, settings.get().pinata(), hits);
        active.put(llama.getUniqueId(), pinata);
        Bukkit.getScheduler().runTask(plugin, visibility::refresh);
        return pinata;
    }

    /** Removes every pinata and countdown without rewards. */
    public int killAll() {
        final int count = active.size();
        active.values().forEach(pinata -> {
            hideBar(pinata);
            pinata.cleanUp();
        });
        active.clear();
        parties.forEach(party -> party.viewers.forEach(id -> {
            final Player viewer = Bukkit.getPlayer(id);
            if (viewer != null) {
                viewer.hideBossBar(party.bar);
            }
        }));
        parties.clear();
        visibility.refresh();
        return count;
    }

    // ---- hits ----

    /** A player struck a pinata with the given item. */
    public void hit(Pinata pinata, Player player, ItemStack held) {
        final Settings.Hit rules = settings.get().pinata().hit();
        if (!rules.permission().isBlank() && !player.hasPermission(rules.permission())) {
            messages.send(player, "hit_no_permission");
            return;
        }
        if (!rules.items().isEmpty() && !rules.items().contains(held.getType())) {
            messages.send(player, "hit_wrong_item");
            return;
        }
        if (!rules.recentVoters().isZero()) {
            final VoteStore.Entry entry = store.find(player.getName());
            if (entry == null || entry.lastVote() < System.currentTimeMillis() - rules.recentVoters().toMillis()) {
                messages.send(player, "hit_need_vote");
                return;
            }
        }
        if (!pinata.tryHit(player.getUniqueId(), System.currentTimeMillis(), rules.cooldownSeconds())) {
            return;
        }

        final int left = pinata.registerHit(player.getName());
        final Location at = pinata.location();
        if (settings.get().pinata().hitSound() != null) {
            at.getWorld().playSound(settings.get().pinata().hitSound(), at.getX(), at.getY(), at.getZ());
        }
        run(Rewards.forPlayer(settings.get().rewards().hit(), voter(player), ThreadLocalRandom.current()));
        run(Rewards.once(settings.get().rewards().hit(), ThreadLocalRandom.current()));

        if (left <= 0) {
            die(pinata, player);
            return;
        }
        messages.send(player, "hit_left", Messages.text("hits", left), Messages.text("plural", left == 1 ? "" : "S"));
        new Abilities(settings.get().pinata().abilities(), messages).roll(pinata);
    }

    private void die(Pinata pinata, Player last) {
        final Settings.PinataSettings config = settings.get().pinata();
        final Location where = pinata.location();
        active.remove(pinata.entity().getUniqueId());

        final ThreadLocalRandom random = ThreadLocalRandom.current();
        run(Rewards.forPlayer(settings.get().rewards().lastHit(), voter(last), random));
        run(Rewards.once(settings.get().rewards().lastHit(), random));

        final Collection<? extends Player> winners = config.rewardEveryone()
                ? Bukkit.getOnlinePlayers()
                : pinata.participants().stream().map(Bukkit::getPlayerExact).filter(p -> p != null).toList();
        for (Player winner : winners) {
            run(Rewards.forPlayer(settings.get().rewards().die(), voter(winner), random));
        }
        run(Rewards.once(settings.get().rewards().die(), random));

        fireworks(where, config.fireworks());
        hideBar(pinata);
        pinata.cleanUp();
        messages.broadcast("pinata_die", Messages.text("player", last.getName()));
        Bukkit.getScheduler().runTask(plugin, visibility::refresh);
    }

    private void fireworks(Location at, int count) {
        final ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < count; i++) {
            final Firework firework = at.getWorld().spawn(at.clone().add(random.nextDouble(-2, 2), 1, random.nextDouble(-2, 2)),
                    Firework.class);
            final var meta = firework.getFireworkMeta();
            meta.addEffect(FireworkEffect.builder()
                    .with(FireworkEffect.Type.BALL_LARGE)
                    .withColor(Color.fromRGB(random.nextInt(0x1000000)))
                    .withFade(Color.fromRGB(random.nextInt(0x1000000)))
                    .build());
            firework.setFireworkMeta(meta);
            firework.getPersistentDataContainer().set(PinataListener.FIREWORK, org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
            firework.detonate();
        }
    }

    private Rewards.Voter voter(Player player) {
        final VoteStore.Entry entry = store.find(player.getName());
        return new Rewards.Voter(player.getName(), "pinata", entry == null ? 0 : entry.total(),
                isVanished(player), false, player::hasPermission);
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

    private static void run(List<String> commands) {
        commands.forEach(command -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command));
    }

    // ---- ticking ----

    private void tick() {
        tick++;
        final Settings.PinataSettings config = settings.get().pinata();

        for (Pinata pinata : active.isEmpty() ? List.<Pinata>of() : new ArrayList<>(active.values())) {
            if (!pinata.isAlive()) {
                // gone without being broken: its chunk unloaded, or something removed it
                expire(pinata);
                continue;
            }
            pinata.tick(messages);
            if (pinata.checkGrownUp()) {
                new Abilities(config.abilities(), messages).grewUp(pinata);
            }
            if (!config.lifeSpan().isZero() && pinata.age() >= config.lifeSpan().toSeconds() * 20) {
                expire(pinata);
                continue;
            }
            if (config.movement().enabled() && pinata.age() % config.movement().interval() == 0) {
                move(pinata, config.movement());
            }
        }
        tickParties();

        if (tick % 20 == 0) {
            syncBars(config);
            updateNearby();
            visibility.check();
            checkPartyEnd();
        }
    }

    /** Decides once a second which pinatas have someone close enough to see them. */
    private void updateNearby() {
        for (Pinata pinata : active.values()) {
            final Location at = pinata.location();
            pinata.setNearby(!at.getWorld().getNearbyPlayers(at, NEARBY_BLOCKS).isEmpty());
        }
    }

    private void expire(Pinata pinata) {
        active.remove(pinata.entity().getUniqueId());
        hideBar(pinata);
        pinata.cleanUp();
        messages.broadcast("pinata_expire");
    }

    private void move(Pinata pinata, Settings.Movement movement) {
        final Llama llama = pinata.entity();
        final Location from = llama.getLocation();
        final ThreadLocalRandom random = ThreadLocalRandom.current();

        Vector direction = null;
        Player nearest = null;
        double best = movement.fleeRadius() * movement.fleeRadius();
        for (Player player : from.getWorld().getNearbyPlayers(from, movement.fleeRadius())) {
            final double distance = player.getLocation().distanceSquared(from);
            if (distance < best) {
                best = distance;
                nearest = player;
            }
        }
        if (nearest != null) {
            direction = from.toVector().subtract(nearest.getLocation().toVector()).setY(0);
        }
        if (direction == null || direction.lengthSquared() < 0.01) {
            final double angle = random.nextDouble(Math.PI * 2);
            direction = new Vector(Math.cos(angle), 0, Math.sin(angle));
        }

        final double reach = Math.max(3.0, movement.range() * (nearest != null ? 0.6 : random.nextDouble(0.3, 1.0)));
        final double x = from.getX() + direction.normalize().getX() * reach;
        final double z = from.getZ() + direction.getZ() * reach;
        final int y = from.getWorld().getHighestBlockYAt((int) Math.floor(x), (int) Math.floor(z)) + 1;
        if (Math.abs(y - from.getY()) <= 6) {
            llama.getPathfinder().moveTo(new Location(from.getWorld(), x, y, z), movement.speed());
        }
    }

    private void tickParties() {
        for (Party party : new ArrayList<>(parties)) {
            final Settings.Countdown config = settings.get().party().countdown();
            party.left--;
            if (party.left <= 0) {
                parties.remove(party);
                Bukkit.getOnlinePlayers().forEach(player -> player.hideBossBar(party.bar));
                play(config.end());
                spawnAll(party.where, party.amount);
                continue;
            }
            if (party.left % 20 == 0) {
                messages.broadcast("party_tick", Messages.text("seconds", party.left / 20));
            }
            party.bar.progress(Math.max(0f, Math.min(1f, party.left / (float) party.total)));
            party.bar.name(messages.chat("countdown_bar", Messages.text("seconds", (party.left + 19) / 20)));
            if (config.color() == null && tick % 4 == 0) {
                party.bar.color(BAR_COLORS[(int) ((tick / 4) % BAR_COLORS.length)]);
            }
            if (party.left % 20 == 0) {
                play(config.tick());
            }
        }
    }

    private void syncBars(Settings.PinataSettings config) {
        final Settings.Countdown countdown = settings.get().party().countdown();
        for (Pinata pinata : active.values()) {
            if (!config.bar().enabled()) {
                continue;
            }
            final Collection<? extends Player> audience = config.bar().global()
                    ? Bukkit.getOnlinePlayers() : pinata.entity().getWorld().getPlayers();
            syncViewers(pinata.bar(), pinata.barViewers(), audience);
        }
        for (Party party : parties) {
            final Collection<? extends Player> audience = countdown.global()
                    ? Bukkit.getOnlinePlayers() : party.where.getWorld().getPlayers();
            syncViewers(party.bar, party.viewers, audience);
        }
    }

    private static void syncViewers(BossBar bar, Set<UUID> viewers, Collection<? extends Player> audience) {
        final Set<UUID> now = new HashSet<>();
        for (Player player : audience) {
            now.add(player.getUniqueId());
            if (viewers.add(player.getUniqueId())) {
                player.showBossBar(bar);
            }
        }
        for (UUID id : new HashSet<>(viewers)) {
            if (!now.contains(id)) {
                final Player gone = Bukkit.getPlayer(id);
                if (gone != null) {
                    gone.hideBossBar(bar);
                }
                viewers.remove(id);
            }
        }
    }

    private void hideBar(Pinata pinata) {
        for (UUID id : pinata.barViewers()) {
            final Player viewer = Bukkit.getPlayer(id);
            if (viewer != null) {
                viewer.hideBossBar(pinata.bar());
            }
        }
        pinata.barViewers().clear();
    }

    /** Shows the closing title once the last pinata is gone and nothing else is on its way. */
    private void checkPartyEnd() {
        final boolean now = !active.isEmpty();
        if (wasActive && !now && parties.isEmpty()) {
            final String only = settings.get().visibility().world();
            final List<Player> audience = new ArrayList<>();
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (only.isBlank() || player.getWorld().getName().equalsIgnoreCase(only)) {
                    audience.add(player);
                }
            }
            messages.broadcast(audience, "party_over");
        }
        wasActive = now;
    }

    private Set<UUID> activeWorlds() {
        final Set<UUID> worlds = new HashSet<>();
        for (Pinata pinata : active.values()) {
            if (pinata.isAlive()) {
                worlds.add(pinata.entity().getWorld().getUID());
            }
        }
        return worlds;
    }

    private void play(net.kyori.adventure.sound.Sound sound) {
        if (sound != null) {
            Bukkit.getOnlinePlayers().forEach(player -> player.playSound(sound));
        }
    }
}
