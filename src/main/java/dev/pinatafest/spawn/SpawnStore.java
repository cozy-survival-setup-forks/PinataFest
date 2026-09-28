package dev.pinatafest.spawn;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/**
 * The spawn points saved in game with /pinatafest setspawn. They live in spawns.yml so they can
 * be added and removed without touching config.yml, and there is no limit on how many there are.
 */
public final class SpawnStore {

    private final Path file;
    private final Map<String, SpawnPoint> points = new LinkedHashMap<>();

    public SpawnStore(Path file) {
        this.file = file;
    }

    public void load(Logger log) {
        if (!Files.exists(file)) {
            points.clear();
            return;
        }
        final YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().pathSeparator('/');
        try {
            yaml.load(file.toFile());
        } catch (IOException | InvalidConfigurationException e) {
            // keep the points already loaded, and move the file away so the next save does not replace it
            final Path aside = file.resolveSibling(file.getFileName() + ".broken-" + System.currentTimeMillis() / 1000);
            try {
                Files.move(file, aside);
            } catch (IOException moveFailed) {
                log.severe("spawns.yml is broken and could not be moved aside: " + moveFailed.getMessage());
            }
            log.severe("spawns.yml could not be read (" + e.getMessage() + "). It was kept as " + aside.getFileName() + ".");
            return;
        }
        points.clear();
        for (String name : yaml.getKeys(false)) {
            final ConfigurationSection section = yaml.getConfigurationSection(name);
            final SpawnPoint point = section == null ? null : SpawnPoint.read(section);
            if (point == null) {
                log.warning("spawns.yml: '" + name + "' is incomplete, skipping it");
            } else {
                points.put(name.toLowerCase(Locale.ROOT), point);
            }
        }
    }

    public Map<String, SpawnPoint> all() {
        return Collections.unmodifiableMap(points);
    }

    public SpawnPoint get(String name) {
        return points.get(name.toLowerCase(Locale.ROOT));
    }

    /** Saves a point, replacing one with the same name. */
    public void put(String name, SpawnPoint point) throws IOException {
        points.put(name.toLowerCase(Locale.ROOT), point);
        save();
    }

    /** Returns false if there was no point with that name. */
    public boolean remove(String name) throws IOException {
        if (points.remove(name.toLowerCase(Locale.ROOT)) == null) {
            return false;
        }
        save();
        return true;
    }

    private void save() throws IOException {
        final YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().pathSeparator('/');
        points.forEach((name, point) -> point.write(yaml.createSection(name)));

        Files.createDirectories(file.getParent());
        final Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, yaml.saveToString(), StandardCharsets.UTF_8);
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
