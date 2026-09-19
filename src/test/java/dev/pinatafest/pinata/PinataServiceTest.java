package dev.pinatafest.pinata;

import dev.pinatafest.config.Settings;
import dev.pinatafest.message.Messages;
import dev.pinatafest.vote.VoteStore;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.io.StringReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PinataServiceTest {

    private static final String CONFIG = """
            pinata:
              health:
                base: 2
                per_player: 0
              hit:
                cooldown: 0
                items: [STICK]
              abilities:
                teleport: { enabled: false }
                knockback: { enabled: false }
                shoot_up: { enabled: false }
                baby: { enabled: false }
                speed_up: { enabled: false }
              fireworks_on_death: 0
            rewards:
              hit:
                each:
                  chance: 100
                  commands: ["hitreward %player%"]
              last_hit:
                final:
                  chance: 100
                  commands: ["lastreward %player%"]
              die:
                everyone:
                  chance: 100
                  commands: ["diereward %player%"]
                once:
                  chance: 100
                  once: true
                  commands: ["fell"]
            """;

    private ServerMock server;
    private PinataService service;
    private PlayerMock player;
    private final List<String> ran = new ArrayList<>();

    @BeforeEach
    void setUp(@TempDir Path dir) {
        server = MockBukkit.mock();
        server.addSimpleWorld("world");
        final var plugin = MockBukkit.createMockPlugin();
        final Settings settings = Settings.load(YamlConfiguration.loadConfiguration(new StringReader(CONFIG)),
                Logger.getAnonymousLogger());
        service = new PinataService(plugin, () -> settings, new Messages(plugin), new VoteStore(dir.resolve("v.yml")));
        player = server.addPlayer("Steve");

        for (String name : List.of("hitreward", "lastreward", "diereward", "fell")) {
            server.getCommandMap().register("test", new Command(name) {
                @Override
                public boolean execute(@NotNull CommandSender sender, @NotNull String label, String @NotNull [] args) {
                    ran.add(label + " " + String.join(" ", args));
                    return true;
                }
            });
        }
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void hitsCountDownAndTheLastOneBreaksIt() {
        final Pinata pinata = service.spawn(new Location(server.getWorld("world"), 0, 80, 0));
        assertEquals(2, pinata.health());

        service.hit(pinata, player, new ItemStack(Material.STICK));
        assertEquals(1, pinata.health());
        assertEquals(List.of("hitreward Steve"), ran);

        service.hit(pinata, player, new ItemStack(Material.STICK));
        assertNull(service.pinataOf(pinata.entity()));
        assertTrue(ran.containsAll(List.of("lastreward Steve", "diereward Steve", "fell ")));
    }

    @Test
    void wrongItemDoesNotCount() {
        final Pinata pinata = service.spawn(new Location(server.getWorld("world"), 0, 80, 0));
        service.hit(pinata, player, new ItemStack(Material.DIRT));

        assertEquals(2, pinata.health());
        assertFalse(ran.contains("hitreward Steve"));
    }
}
