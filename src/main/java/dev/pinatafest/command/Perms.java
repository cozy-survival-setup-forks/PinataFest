package dev.pinatafest.command;

import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.PluginManager;

/**
 * Permission nodes. Looking up your own votes and choosing whether to see other players is open
 * to everyone, the rest is for ops.
 */
public final class Perms {

    public static final String ADMIN = "pinatafest.admin";
    public static final String VOTES = "pinatafest.votes";
    public static final String VISIBILITY = "pinatafest.visibility";

    private Perms() {
    }

    public static void register(PluginManager manager) {
        add(manager, ADMIN, "Summon and remove pinatas, send test votes, reload and look up players", PermissionDefault.OP);
        add(manager, VOTES, "See your own vote count", PermissionDefault.TRUE);
        add(manager, VISIBILITY, "Hide other players while a pinata is out", PermissionDefault.TRUE);
    }

    private static void add(PluginManager manager, String node, String description, PermissionDefault value) {
        if (manager.getPermission(node) == null) {
            manager.addPermission(new Permission(node, description, value));
        }
    }
}
