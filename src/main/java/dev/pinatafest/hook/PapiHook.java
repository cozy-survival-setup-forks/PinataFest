package dev.pinatafest.hook;

import dev.pinatafest.pinata.PinataService;
import dev.pinatafest.vote.VoteStore;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * %pinatafest_votes% and %pinatafest_queued% for a player, %pinatafest_counter% and
 * %pinatafest_left% for the pinata vote goal, %pinatafest_visibility% for the player's choice.
 */
public final class PapiHook extends PlaceholderExpansion {

    private final Plugin plugin;
    private final VoteStore store;
    private final PinataService pinatas;

    public PapiHook(Plugin plugin, VoteStore store, PinataService pinatas) {
        this.plugin = plugin;
        this.store = store;
        this.pinatas = pinatas;
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
        switch (params.toLowerCase()) {
            case "counter":
                return Integer.toString(store.pinataVotes());
            case "left":
                return Integer.toString(pinatas.votesUntilNext());
            default:
                break;
        }
        if (player == null || player.getName() == null) {
            return "";
        }
        final VoteStore.Entry entry = store.find(player.getName());
        return switch (params.toLowerCase()) {
            case "votes" -> Integer.toString(entry == null ? 0 : entry.total());
            case "queued" -> Integer.toString(entry == null ? 0 : entry.queue().size());
            case "visibility" -> player.getPlayer() != null && pinatas.visibility().hides(player.getPlayer())
                    ? "hidden" : "visible";
            default -> null;
        };
    }
}
