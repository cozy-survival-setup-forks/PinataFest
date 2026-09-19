package dev.pinatafest.pinata;

import dev.pinatafest.config.Settings;
import dev.pinatafest.message.Messages;
import dev.pinatafest.spawn.SpawnStore;
import dev.pinatafest.vote.VoteStore;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Llama;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockito.Mockito;

import java.io.StringReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MockBukkit cannot create a llama, so a Mockito stand-in is used. Only the game rules are under
 * test here: hits, rewards and visibility, not what the llama looks like.
 */
class PinataServiceTest {

    private static final String CONFIG = """
            pinata:
              look:
                carpet: none
                glow: { enabled: false }
                particles: { type: none }
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
        service = new PinataService(plugin, () -> settings, new Messages(plugin), new VoteStore(dir.resolve("v.yml")),
                new SpawnStore(dir.resolve("s.yml")));
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

    private Pinata pinata(int hits) {
        final Llama llama = Mockito.mock(Llama.class, Mockito.RETURNS_DEEP_STUBS);
        Mockito.when(llama.getUniqueId()).thenReturn(UUID.randomUUID());
        Mockito.when(llama.isValid()).thenReturn(true);
        Mockito.when(llama.getWorld()).thenReturn(server.getWorld("world"));
        Mockito.when(llama.getLocation()).thenReturn(new Location(server.getWorld("world"), 0, 80, 0));
        return service.track(llama, hits);
    }

    @Test
    void hitsCountDownAndTheLastOneBreaksIt() {
        final Pinata pinata = pinata(2);

        service.hit(pinata, player, new ItemStack(Material.STICK));
        assertEquals(1, pinata.health());
        assertEquals(List.of("hitreward Steve"), ran);

        service.hit(pinata, player, new ItemStack(Material.STICK));
        assertNull(service.pinataOf(pinata.entity()), "a broken pinata is no longer tracked");
        assertTrue(ran.containsAll(List.of("lastreward Steve", "diereward Steve", "fell ")), ran.toString());
    }

    @Test
    void wrongItemDoesNotCount() {
        final Pinata pinata = pinata(2);
        service.hit(pinata, player, new ItemStack(Material.DIRT));

        assertEquals(2, pinata.health());
        assertFalse(ran.contains("hitreward Steve"));
    }

    @Test
    void visibilityHidesDuringAPartyAndRestoresAfterwards() {
        final PlayerMock other = server.addPlayer("Other");
        service.visibility().set(player, true);
        assertTrue(player.canSee(other), "nothing is hidden before a pinata appears");

        pinata(5);
        service.visibility().refresh();
        assertFalse(player.canSee(other), "the hider loses sight of others during the party");
        assertTrue(other.canSee(player), "people who did not ask to hide still see everyone");

        service.killAll();
        service.visibility().check();
        assertTrue(player.canSee(other), "everyone is visible again once the pinata is gone");
    }

    @Test
    void turningTheChoiceOffMidPartyShowsEveryoneAtOnce() {
        final PlayerMock other = server.addPlayer("Other");
        service.visibility().set(player, true);
        pinata(5);
        service.visibility().refresh();
        assertFalse(player.canSee(other));

        service.visibility().set(player, false);
        assertTrue(player.canSee(other));
    }
}
