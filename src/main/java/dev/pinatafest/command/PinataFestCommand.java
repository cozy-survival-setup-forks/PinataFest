package dev.pinatafest.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import dev.pinatafest.PinataFestPlugin;
import dev.pinatafest.config.Settings;
import dev.pinatafest.message.Messages;
import dev.pinatafest.vote.VoteService;
import dev.pinatafest.vote.VoteStore;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.function.Supplier;

/**
 * /vote lists the vote links; /pinatafest has votes, fake and reload.
 */
public final class PinataFestCommand {

    private final PinataFestPlugin plugin;
    private final Supplier<Settings> settings;
    private final VoteStore store;
    private final VoteService votes;
    private final Messages messages;

    public PinataFestCommand(PinataFestPlugin plugin, Supplier<Settings> settings, VoteStore store, VoteService votes,
                         Messages messages) {
        this.plugin = plugin;
        this.settings = settings;
        this.store = store;
        this.votes = votes;
        this.messages = messages;
    }

    public LiteralArgumentBuilder<CommandSourceStack> vote() {
        return Commands.literal("vote")
                .requires(source -> source.getSender().hasPermission(Perms.VOTE))
                .executes(this::links);
    }

    public LiteralArgumentBuilder<CommandSourceStack> pinatafest() {
        return Commands.literal("pinatafest")
                .then(Commands.literal("votes")
                        .requires(source -> source.getSender().hasPermission(Perms.VOTES))
                        .executes(this::ownVotes)
                        .then(Commands.argument("player", StringArgumentType.word())
                                .requires(source -> source.getSender().hasPermission(Perms.ADMIN))
                                .executes(this::playerVotes)))
                .then(Commands.literal("fake")
                        .requires(source -> source.getSender().hasPermission(Perms.ADMIN))
                        .then(Commands.argument("player", StringArgumentType.word())
                                .executes(ctx -> fake(ctx, "test"))
                                .then(Commands.argument("service", StringArgumentType.word())
                                        .executes(ctx -> fake(ctx, StringArgumentType.getString(ctx, "service"))))))
                .then(Commands.literal("reload")
                        .requires(source -> source.getSender().hasPermission(Perms.ADMIN))
                        .executes(this::reload));
    }

    private int links(CommandContext<CommandSourceStack> ctx) {
        final CommandSender sender = sender(ctx);
        final var links = settings.get().votes().links();
        if (links.isEmpty()) {
            messages.send(sender, "vote_none");
            return Command.SINGLE_SUCCESS;
        }
        messages.send(sender, "vote_header");
        for (Settings.Link link : links) {
            messages.send(sender, "vote_link", Messages.text("name", link.name()), Messages.parsed("url", link.url()));
        }
        return Command.SINGLE_SUCCESS;
    }

    private int ownVotes(CommandContext<CommandSourceStack> ctx) {
        if (!(sender(ctx) instanceof Player player)) {
            messages.send(sender(ctx), "votes_none_console");
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

    private int fake(CommandContext<CommandSourceStack> ctx, String service) {
        final String name = StringArgumentType.getString(ctx, "player");
        votes.receive(name, service);
        messages.send(sender(ctx), "fake_sent", Messages.text("player", name), Messages.text("service", service));
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
