package dev.pinatafest.spawn;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;

import java.util.random.RandomGenerator;

/**
 * A place a pinata can appear: one exact spot, a random point inside a flat area that follows the
 * ground, or a random point anywhere inside a box.
 */
public sealed interface SpawnPoint {

    String world();

    /** A short word for lists and messages. */
    String kind();

    /** Picks the actual location for this pinata. Null if the world is not loaded. */
    Location pick(RandomGenerator random);

    void write(ConfigurationSection into);

    /** One exact spot. A NaN y means the highest block. */
    record Fixed(String world, double x, double y, double z, float yaw) implements SpawnPoint {
        @Override
        public String kind() {
            return "fixed";
        }

        @Override
        public Location pick(RandomGenerator random) {
            final World w = Bukkit.getWorld(world);
            if (w == null) {
                return null;
            }
            final double height = Double.isNaN(y) ? w.getHighestBlockYAt((int) Math.floor(x), (int) Math.floor(z)) + 1 : y;
            return new Location(w, x, height, z, yaw, 0f);
        }

        @Override
        public void write(ConfigurationSection into) {
            into.set("type", "fixed");
            into.set("world", world);
            into.set("x", x);
            if (!Double.isNaN(y)) {
                into.set("y", y);
            }
            into.set("z", z);
            into.set("yaw", yaw);
        }
    }

    /** A rectangle seen from above; the pinata lands on the highest block of the point picked. */
    record Area(String world, double minX, double maxX, double minZ, double maxZ) implements SpawnPoint {
        @Override
        public String kind() {
            return "area";
        }

        @Override
        public Location pick(RandomGenerator random) {
            final World w = Bukkit.getWorld(world);
            if (w == null) {
                return null;
            }
            final double px = between(random, minX, maxX);
            final double pz = between(random, minZ, maxZ);
            return new Location(w, px, w.getHighestBlockYAt((int) Math.floor(px), (int) Math.floor(pz)) + 1, pz);
        }

        @Override
        public void write(ConfigurationSection into) {
            into.set("type", "area");
            into.set("world", world);
            into.set("min_x", minX);
            into.set("max_x", maxX);
            into.set("min_z", minZ);
            into.set("max_z", maxZ);
        }
    }

    /** A full box; any point inside it can be picked, height included. */
    record Zone(String world, double minX, double maxX, double minY, double maxY, double minZ, double maxZ)
            implements SpawnPoint {
        @Override
        public String kind() {
            return "zone";
        }

        @Override
        public Location pick(RandomGenerator random) {
            final World w = Bukkit.getWorld(world);
            if (w == null) {
                return null;
            }
            return new Location(w, between(random, minX, maxX), between(random, minY, maxY), between(random, minZ, maxZ));
        }

        @Override
        public void write(ConfigurationSection into) {
            into.set("type", "zone");
            into.set("world", world);
            into.set("min_x", minX);
            into.set("max_x", maxX);
            into.set("min_y", minY);
            into.set("max_y", maxY);
            into.set("min_z", minZ);
            into.set("max_z", maxZ);
        }
    }

    /** Reads a spawn point from config or from spawns.yml. Null if it is missing something. */
    static SpawnPoint read(ConfigurationSection section) {
        final String world = section.getString("world");
        if (world == null) {
            return null;
        }
        final String type = section.getString("type", "fixed").toLowerCase();
        switch (type) {
            case "area":
                if (!section.contains("min_x") || !section.contains("max_x") || !section.contains("min_z")
                        || !section.contains("max_z")) {
                    return null;
                }
                return new Area(world, Math.min(section.getDouble("min_x"), section.getDouble("max_x")),
                        Math.max(section.getDouble("min_x"), section.getDouble("max_x")),
                        Math.min(section.getDouble("min_z"), section.getDouble("max_z")),
                        Math.max(section.getDouble("min_z"), section.getDouble("max_z")));
            case "zone":
                if (!section.contains("min_x") || !section.contains("max_x") || !section.contains("min_y")
                        || !section.contains("max_y") || !section.contains("min_z") || !section.contains("max_z")) {
                    return null;
                }
                return new Zone(world, Math.min(section.getDouble("min_x"), section.getDouble("max_x")),
                        Math.max(section.getDouble("min_x"), section.getDouble("max_x")),
                        Math.min(section.getDouble("min_y"), section.getDouble("max_y")),
                        Math.max(section.getDouble("min_y"), section.getDouble("max_y")),
                        Math.min(section.getDouble("min_z"), section.getDouble("max_z")),
                        Math.max(section.getDouble("min_z"), section.getDouble("max_z")));
            default:
                if (!section.contains("x") || !section.contains("z")) {
                    return null;
                }
                return new Fixed(world, section.getDouble("x"),
                        section.get("y") instanceof Number n ? n.doubleValue() : Double.NaN,
                        section.getDouble("z"), (float) section.getDouble("yaw"));
        }
    }

    private static double between(RandomGenerator random, double min, double max) {
        return max <= min ? min : min + random.nextDouble() * (max - min);
    }
}
