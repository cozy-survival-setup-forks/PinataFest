package dev.pinatafest.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import dev.pinatafest.PinataFestPlugin;
import dev.pinatafest.config.Settings;
import dev.pinatafest.message.Messages;
import dev.pinatafest.pinata.PartyVisibility;
import dev.pinatafest.pinata.PinataService;
import dev.pinatafest.spawn.SpawnPoint;
import dev.pinatafest.spawn.SpawnStore;
import dev.pinatafest.vote.VoteService;
import dev.pinatafest.vote.VoteStore;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * /pinatafest for votes and admin tools, and /pinatavisibility as a short way to the visibility switch.
 */
public final class PinataFestCommand {

    private final PinataFestPlugin plugin;
    private final Supplier<Settings> settings;
    private final VoteStore store;
    private final VoteService votes;
    private final PinataService pinatas;
    private final SpawnStore spawns;
    private final Messages messages;
    /** The two corners each admin has picked with pos1 and pos2. */
    private final Map<UUID, Location[]> corners = new HashMap<>();

    public PinataFestCommand(PinataFestPlugin plugin, Supplier<Settings> settings, VoteStore store,
                             VoteService votes, PinataService pinatas, SpawnStore spawns, Messages messages) {
        this.plugin = plugin;
        this.settings = settings;
        this.store = store;
        this.votes = votes;
        this.pinatas = pinatas;
        this.spawns = spawns;
        this.messages = messages;
    }

    public LiteralArgumentBuilder<CommandSourceStack> pinatafest() {
        return Commands.literal("pinatafest")
                .then(Commands.literal("votes")
                        .requires(source -> source.getSender().hasPermission(Perms.VOTES))
                        .executes(this::ownVotes)
                        .then(Commands.argument("player", StringArgumentType.word())
                                .requires(source -> source.getSender().hasPermission(Perms.ADMIN))
                                .executes(this::playerVotes)))
                .then(Commands.literal("progress")
                        .requires(source -> source.getSender().hasPermission(Perms.VOTES))
                        .executes(this::progress))
                .then(Commands.literal("fake")
                        .requires(source -> source.getSender().hasPermission(Perms.ADMIN))
                        .then(Commands.argument("player", StringArgumentType.word())
                                .executes(ctx -> fake(ctx, "test"))
                                .then(Commands.argument("service", StringArgumentType.word())
                                        .executes(ctx -> fake(ctx, StringArgumentType.getString(ctx, "service"))))))
                .then(Commands.literal("summon")
                        .requires(source -> source.getSender().hasPermission(Perms.ADMIN))
                        .executes(ctx -> summon(ctx, null))
                        .then(Commands.argument("location", StringArgumentType.word())
                                .suggests((ctx, builder) -> {
                                    pinatas.locationNames().forEach(builder::suggest);
                                    return builder.buildFuture();
                                })
                                .executes(ctx -> summon(ctx, StringArgumentType.getString(ctx, "location")))))
                .then(Commands.literal("kill")
                        .requires(source -> source.getSender().hasPermission(Perms.ADMIN))
                        .executes(this::kill))
                .then(Commands.literal("reload")
                        .requires(source -> source.getSender().hasPermission(Perms.ADMIN))
                        .executes(this::reload))
                .then(setSpawn())
                .then(Commands.literal("delspawn")
                        .requires(source -> source.getSender().hasPermission(Perms.ADMIN))
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests((ctx, builder) -> {
                                    spawns.all().keySet().forEach(builder::suggest);
                                    return builder.buildFuture();
                                })
                                .executes(this::deleteSpawn)))
                .then(Commands.literal("spawns")
                        .requires(source -> source.getSender().hasPermission(Perms.ADMIN))
                        .executes(this::listSpawns))
                .then(Commands.literal("pos1")
                        .requires(source -> source.getSender().hasPermission(Perms.ADMIN))
                        .executes(ctx -> corner(ctx, 0)))
                .then(Commands.literal("pos2")
                        .requires(source -> source.getSender().hasPermission(Perms.ADMIN))
                        .executes(ctx -> corner(ctx, 1)))
                .then(visibilityTree());
    }

    /** setspawn NAME saves where you stand; add 2d or 3d for a random point inside a zone. */
    private LiteralArgumentBuilder<CommandSourceStack> setSpawn() {
        return Commands.literal("setspawn")
                .requires(source -> source.getSender().hasPermission(Perms.ADMIN))
                .then(Commands.argument("name", StringArgumentType.word())
                        .executes(this::saveFixed)
                        .then(Commands.literal("2d")
                                .executes(ctx -> saveZone(ctx, false, 0))
                                .then(Commands.argument("radius", DoubleArgumentType.doubleArg(1, 1000))
                                        .executes(ctx -> saveZone(ctx, false, DoubleArgumentType.getDouble(ctx, "radius")))))
                        .then(Commands.literal("3d")
                                .executes(ctx -> saveZone(ctx, true, 0))
                                .then(Commands.argument("radius", DoubleArgumentType.doubleArg(1, 1000))
                                        .executes(ctx -> saveZone(ctx, true, DoubleArgumentType.getDouble(ctx, "radius"))))));
    }

    public LiteralArgumentBuilder<CommandSourceStack> visibility() {
        return visibilityTree(Commands.literal("pinatavisibility"));
    }

    private LiteralArgumentBuilder<CommandSourceStack> visibilityTree() {
        return visibilityTree(Commands.literal("visibility"));
    }

    private LiteralArgumentBuilder<CommandSourceStack> visibilityTree(LiteralArgumentBuilder<CommandSourceStack> root) {
        return root
                .requires(source -> source.getSender().hasPermission(Perms.VISIBILITY))
                .executes(ctx -> visibility(ctx, Choice.TOGGLE))
                .then(Commands.literal("hide").executes(ctx -> visibility(ctx, Choice.HIDE)))
                .then(Commands.literal("show").executes(ctx -> visibility(ctx, Choice.SHOW)))
                .then(Commands.literal("toggle").executes(ctx -> visibility(ctx, Choice.TOGGLE)))
                .then(Commands.literal("status").executes(ctx -> visibility(ctx, Choice.STATUS)));
    }

    private enum Choice { HIDE, SHOW, TOGGLE, STATUS }

    private int visibility(CommandContext<CommandSourceStack> ctx, Choice choice) {
        if (!(sender(ctx) instanceof Player player)) {
            messages.send(sender(ctx), "players_only");
            return Command.SINGLE_SUCCESS;
        }
        final PartyVisibility visibility = pinatas.visibility();
        final boolean hidden = visibility.hides(player);
        final boolean wanted = switch (choice) {
            case HIDE -> true;
            case SHOW -> false;
            case TOGGLE -> !hidden;
            case STATUS -> hidden;
        };
        if (choice != Choice.STATUS && wanted != hidden) {
            visibility.set(player, wanted);
            messages.send(player, wanted ? "visibility_hidden" : "visibility_shown");
        } else {
            messages.send(player, wanted ? "visibility_status_hidden" : "visibility_status_shown");
        }
        return Command.SINGLE_SUCCESS;
    }

    private int ownVotes(CommandContext<CommandSourceStack> ctx) {
        if (!(sender(ctx) instanceof Player player)) {
            messages.send(sender(ctx), "votes_console");
            return Command.SINGLE_SUCCESS;
        }
        final VoteStore.Entry entry = store.find(player.getName());
        messages.send(player, "votes_self",
                Messages.text("votes", entry == null ? 0 : entry.total()),
                Messages.text("queued", entry == null ? 0 : entry.queue().size()));
        return Command.SINGLE_SUCCESS;
    }

    private int playerVotes(CommandContext<CommandSourceStack> ctx) {
        final String name = StringArgumentType.getString(ctx, "player");
        final VoteStore.Entry entry = store.find(name);
        messages.send(sender(ctx), "votes_other",
                Messages.text("player", name),
                Messages.text("votes", entry == null ? 0 : entry.total()),
                Messages.text("queued", entry == null ? 0 : entry.queue().size()));
        return Command.SINGLE_SUCCESS;
    }

    private int progress(CommandContext<CommandSourceStack> ctx) {
        final int needed = settings.get().party().votesNeeded();
        messages.send(sender(ctx), needed == 0 ? "progress_off" : "progress",
                Messages.text("count", store.pinataVotes()), Messages.text("needed", needed),
                Messages.text("left", pinatas.votesUntilNext()));
        return Command.SINGLE_SUCCESS;
    }

    private int fake(CommandContext<CommandSourceStack> ctx, String service) {
        final String name = StringArgumentType.getString(ctx, "player");
        votes.receive(name, service);
        messages.send(sender(ctx), "fake_sent", Messages.text("player", name), Messages.text("service", service));
        return Command.SINGLE_SUCCESS;
    }

    private int summon(CommandContext<CommandSourceStack> ctx, String location) {
        final List<String> known = List.copyOf(pinatas.locationNames());
        final String where = location != null ? location : (known.isEmpty() ? null : known.get(0));
        final boolean started = where != null && pinatas.summon(where, 1);
        messages.send(sender(ctx), started ? "summon_ok" : "summon_bad", Messages.text("location", String.valueOf(where)));
        return Command.SINGLE_SUCCESS;
    }

    private int kill(CommandContext<CommandSourceStack> ctx) {
        messages.send(sender(ctx), "killed", Messages.text("count", pinatas.killAll()));
        return Command.SINGLE_SUCCESS;
    }

    private int reload(CommandContext<CommandSourceStack> ctx) {
        plugin.reload();
        messages.send(sender(ctx), "reload");
        return Command.SINGLE_SUCCESS;
    }

    private int saveFixed(CommandContext<CommandSourceStack> ctx) {
        if (!(sender(ctx) instanceof Player player)) {
            messages.send(sender(ctx), "players_only");
            return Command.SINGLE_SUCCESS;
        }
        final Location at = player.getLocation();
        return store(ctx, new SpawnPoint.Fixed(at.getWorld().getName(), at.getX(), at.getY(), at.getZ(), at.getYaw()));
    }

    /** A zone around you when a radius is given, otherwise between the corners set with pos1 and pos2. */
    private int saveZone(CommandContext<CommandSourceStack> ctx, boolean threeD, double radius) {
        if (!(sender(ctx) instanceof Player player)) {
            messages.send(sender(ctx), "players_only");
            return Command.SINGLE_SUCCESS;
        }

        final Location here = player.getLocation();
        final String world = here.getWorld().getName();
        final SpawnPoint point;
        if (radius > 0) {
            point = threeD
                    ? new SpawnPoint.Zone(world, here.getX() - radius, here.getX() + radius, here.getY() - radius,
                            here.getY() + radius, here.getZ() - radius, here.getZ() + radius)
                    : new SpawnPoint.Area(world, here.getX() - radius, here.getX() + radius, here.getZ() - radius,
                            here.getZ() + radius);
        } else {
            final Location[] picked = corners.get(player.getUniqueId());
            if (picked == null || picked[0] == null || picked[1] == null
                    || !picked[0].getWorld().equals(picked[1].getWorld())) {
                messages.send(player, "corners_needed");
                return Command.SINGLE_SUCCESS;
            }
            final Location a = picked[0];
            final Location b = picked[1];
            final String cornerWorld = a.getWorld().getName();
            point = threeD
                    ? new SpawnPoint.Zone(cornerWorld, Math.min(a.getX(), b.getX()), Math.max(a.getX(), b.getX()),
                            Math.min(a.getY(), b.getY()), Math.max(a.getY(), b.getY()),
                            Math.min(a.getZ(), b.getZ()), Math.max(a.getZ(), b.getZ()))
                    : new SpawnPoint.Area(cornerWorld, Math.min(a.getX(), b.getX()), Math.max(a.getX(), b.getX()),
                            Math.min(a.getZ(), b.getZ()), Math.max(a.getZ(), b.getZ()));
        }
        return store(ctx, point);
    }

    private int store(CommandContext<CommandSourceStack> ctx, SpawnPoint point) {
        final String name = StringArgumentType.getString(ctx, "name");
        try {
            spawns.put(name, point);
            messages.send(sender(ctx), "spawn_saved", Messages.text("name", name), Messages.text("kind", point.kind()),
                    Messages.text("world", point.world()));
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save spawns.yml: " + e.getMessage());
            messages.send(sender(ctx), "spawn_error");
        }
        return Command.SINGLE_SUCCESS;
    }

    private int deleteSpawn(CommandContext<CommandSourceStack> ctx) {
        final String name = StringArgumentType.getString(ctx, "name");
        try {
            messages.send(sender(ctx), spawns.remove(name) ? "spawn_deleted" : "spawn_missing", Messages.text("name", name));
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save spawns.yml: " + e.getMessage());
            messages.send(sender(ctx), "spawn_error");
        }
        return Command.SINGLE_SUCCESS;
    }

    private int listSpawns(CommandContext<CommandSourceStack> ctx) {
        final var all = pinatas.spawnPoints();
        if (all.isEmpty()) {
            messages.send(sender(ctx), "spawns_none");
            return Command.SINGLE_SUCCESS;
        }
        messages.send(sender(ctx), "spawns_header", Messages.text("count", all.size()));
        all.forEach((name, point) -> messages.send(sender(ctx), "spawns_line", Messages.text("name", name),
                Messages.text("kind", point.kind()), Messages.text("world", point.world())));
        return Command.SINGLE_SUCCESS;
    }

    private int corner(CommandContext<CommandSourceStack> ctx, int index) {
        if (!(sender(ctx) instanceof Player player)) {
            messages.send(sender(ctx), "players_only");
            return Command.SINGLE_SUCCESS;
        }
        final Location at = player.getLocation();
        corners.computeIfAbsent(player.getUniqueId(), id -> new Location[2])[index] = at;
        messages.send(player, "corner_set", Messages.text("number", index + 1), Messages.text("x", at.getBlockX()),
                Messages.text("y", at.getBlockY()), Messages.text("z", at.getBlockZ()));
        return Command.SINGLE_SUCCESS;
    }

    private static CommandSender sender(CommandContext<CommandSourceStack> ctx) {
        return ctx.getSource().getSender();
    }
}
