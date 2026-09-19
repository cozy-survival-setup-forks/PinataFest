package dev.pinatafest.hook;

import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.function.BiConsumer;

/**
 * Listens for the vote event of Votifier, NuVotifier or VotifierPlus. They all use the same event
 * class, so it is looked up by name and no vote plugin has to be present at build time.
 */
public final class VotifierHook {

    private static final String EVENT_CLASS = "com.vexsoftware.votifier.model.VotifierEvent";

    private VotifierHook() {
    }

    /** Returns false if no vote plugin is installed. */
    public static boolean register(Plugin plugin, BiConsumer<String, String> onVote) {
        final Class<? extends Event> eventType;
        final Method getVote;
        final Method getUsername;
        final Method getService;
        try {
            eventType = Class.forName(EVENT_CLASS, false, plugin.getClass().getClassLoader()).asSubclass(Event.class);
            getVote = eventType.getMethod("getVote");
            final Class<?> voteType = getVote.getReturnType();
            getUsername = voteType.getMethod("getUsername");
            getService = voteType.getMethod("getServiceName");
        } catch (ReflectiveOperationException e) {
            return false;
        }

        plugin.getServer().getPluginManager().registerEvent(eventType, new Listener() { },
                EventPriority.NORMAL, (listener, event) -> {
                    if (!eventType.isInstance(event)) {
                        return;
                    }
                    try {
                        final Object vote = getVote.invoke(event);
                        onVote.accept(String.valueOf(getUsername.invoke(vote)), String.valueOf(getService.invoke(vote)));
                    } catch (ReflectiveOperationException e) {
                        plugin.getLogger().warning("Could not read a vote from " + EVENT_CLASS + ": " + e);
                    }
                }, plugin);
        return true;
    }
}
