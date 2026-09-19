package dev.pinatafest.command;

import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.PluginManager;

/**
 * Permission nodes. Looking up links and your own votes is open to everyone, the rest is for ops.
 */
public final class Perms {

    public static final String ADMIN = "pinatafest.admin";
    public static final String VOTE = "pinatafest.vote";
    public static final String VOTES = "pinatafest.votes";
    public static final String NO_REMINDER = "pinatafest.noreminder";

    private Perms() {
    }

    public static void register(PluginManager manager) {
        add(manager, ADMIN, "Reload PinataFest, send test votes and look up other players", PermissionDefault.OP);
        add(manager, VOTE, "Use /vote", PermissionDefault.TRUE);
        add(manager, VOTES, "See your own vote count", PermissionDefault.TRUE);
        add(manager, NO_REMINDER, "Never get vote reminders", PermissionDefault.FALSE);
    }

    private static void add(PluginManager manager, String node, String description, PermissionDefault value) {
        if (manager.getPermission(node) == null) {
            manager.addPermission(new Permission(node, description, value));
        }
    }
}
