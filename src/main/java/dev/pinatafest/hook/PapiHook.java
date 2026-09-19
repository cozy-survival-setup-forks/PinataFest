package dev.pinatafest.hook;

import dev.pinatafest.vote.VoteStore;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * %pinatafest_votes% is a player's total, %pinatafest_queued% is how many votes are waiting for them.
 */
public final class PapiHook extends PlaceholderExpansion {

    private final Plugin plugin;
    private final VoteStore store;

    public PapiHook(Plugin plugin, VoteStore store) {
        this.plugin = plugin;
        this.store = store;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "pinatafest";
    }

    @Override
    public @NotNull String getAuthor() {
        return String.join(", ", plugin.getPluginMeta().getAuthors());
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @Nullable String onRequest(OfflinePlayer player, @NotNull String params) {
        if (player == null || player.getName() == null) {
            return "";
        }
        final VoteStore.Entry entry = store.find(player.getName());
        return switch (params.toLowerCase()) {
            case "votes" -> Integer.toString(entry == null ? 0 : entry.total());
            case "queued" -> Integer.toString(entry == null ? 0 : entry.queue().size());
            default -> null;
        };
    }
}
