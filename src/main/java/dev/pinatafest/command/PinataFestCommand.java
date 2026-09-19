package dev.pinatafest.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import dev.pinatafest.PinataFestPlugin;
import dev.pinatafest.config.Settings;
import dev.pinatafest.message.Messages;
import dev.pinatafest.pinata.PartyVisibility;
import dev.pinatafest.pinata.PinataService;
import dev.pinatafest.vote.VoteService;
import dev.pinatafest.vote.VoteStore;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
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
    private final Messages messages;

    public PinataFestCommand(PinataFestPlugin plugin, Supplier<Settings> settings, VoteStore store,
                             VoteService votes, PinataService pinatas, Messages messages) {
        this.plugin = plugin;
        this.settings = settings;
        this.store = store;
        this.votes = votes;
        this.pinatas = pinatas;
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
                .then(visibilityTree());
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

    private static CommandSender sender(CommandContext<CommandSourceStack> ctx) {
        return ctx.getSource().getSender();
    }
}
