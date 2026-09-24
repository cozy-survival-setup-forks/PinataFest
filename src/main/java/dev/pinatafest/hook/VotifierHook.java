package dev.pinatafest.hook;

import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;

/**
 * Listens for the vote event of Votifier, NuVotifier or VotifierPlus. They all use the same event
 * class, so it is looked up by name and no vote plugin has to be present at build time.
 */
public final class VotifierHook {

    private static final String EVENT_CLASS = "com.vexsoftware.votifier.model.VotifierEvent";

    private VotifierHook() {
    }

    /** Gets each vote with the time the vote site stamped on it, or null if the plugin gives none. */
    @FunctionalInterface
    public interface Handler {
        void vote(String username, String service, String stamp);
    }

    /** Returns false if no vote plugin is installed. */
    public static boolean register(Plugin plugin, Handler onVote) {
        final Class<? extends Event> eventType;
        final Method getVote;
        final Method getUsername;
        final Method getService;
        Method stamp = null;
        try {
            eventType = Class.forName(EVENT_CLASS, false, plugin.getClass().getClassLoader()).asSubclass(Event.class);
            getVote = eventType.getMethod("getVote");
            final Class<?> voteType = getVote.getReturnType();
            getUsername = voteType.getMethod("getUsername");
            getService = voteType.getMethod("getServiceName");
            try {
                stamp = voteType.getMethod("getTimeStamp");
            } catch (NoSuchMethodException ignored) {
                // no stamp: votes are then never treated as resends
            }
        } catch (ReflectiveOperationException e) {
            return false;
        }

        final Method getStamp = stamp;
        plugin.getServer().getPluginManager().registerEvent(eventType, new Listener() { },
                EventPriority.NORMAL, (listener, event) -> {
                    if (!eventType.isInstance(event)) {
                        return;
                    }
                    try {
                        final Object vote = getVote.invoke(event);
                        final Object time = getStamp == null ? null : getStamp.invoke(vote);
                        onVote.vote(String.valueOf(getUsername.invoke(vote)), String.valueOf(getService.invoke(vote)),
                                time == null ? null : String.valueOf(time));
                    } catch (ReflectiveOperationException e) {
                        plugin.getLogger().warning("Could not read a vote from " + EVENT_CLASS + ": " + e);
                    }
                }, plugin);
        return true;
    }
}
