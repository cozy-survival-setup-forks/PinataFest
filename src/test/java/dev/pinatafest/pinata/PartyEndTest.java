package dev.pinatafest.pinata;

import dev.pinatafest.PinataFestPlugin;
import dev.pinatafest.config.Settings;
import dev.pinatafest.message.Messages;
import dev.pinatafest.spawn.SpawnStore;
import dev.pinatafest.vote.VoteStore;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Llama;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockito.Mockito;

import java.io.File;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The closing message has to go out once the last pinata is gone, however it went. The message is
 * set to plain chat here so the test can read it; the real one is a title.
 */
class PartyEndTest {

    private ServerMock server;
    private PinataFestPlugin plugin;
    private PinataService service;
    private PlayerMock player;

    @BeforeEach
    void setUp(@TempDir Path dir) throws Exception {
        server = MockBukkit.mock();
        server.addSimpleWorld("world");
        plugin = MockBukkit.load(PinataFestPlugin.class);

        Files.writeString(new File(plugin.getDataFolder(), "lang.yml").toPath(),
                "lang_version: 99\nparty_over:\n  enabled: true\n  type: [chat]\n  chat: \"party finished\"\n");
        final Messages messages = new Messages(plugin);
        messages.load();

        final Settings settings = Settings.load(YamlConfiguration.loadConfiguration(new StringReader("""
                pinata:
                  movement: { enabled: false }
                  look:
                    carpet: none
                    glow: { enabled: false }
                    particles: { type: none }
                  life_span: 0
                """)), Logger.getAnonymousLogger());
        service = new PinataService(plugin, () -> settings, messages, new VoteStore(dir.resolve("v.yml")),
                new SpawnStore(dir.resolve("s.yml")));
        service.start();
        player = server.addPlayer("Steve");
        player.nextComponentMessage();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private Pinata pinata() {
        final Llama llama = Mockito.mock(Llama.class, Mockito.RETURNS_DEEP_STUBS);
        Mockito.when(llama.getUniqueId()).thenReturn(UUID.randomUUID());
        Mockito.when(llama.isValid()).thenReturn(true);
        Mockito.when(llama.getWorld()).thenReturn(server.getWorld("world"));
        Mockito.when(llama.getLocation()).thenReturn(new Location(server.getWorld("world"), 0, 80, 0));
        return service.track(llama, 5);
    }

    @Test
    void closingMessageComesOnceWhenThePinataIsRemoved() {
        pinata();
        server.getScheduler().performTicks(45);
        assertNull(player.nextComponentMessage(), "nothing is said while the pinata is still out");

        service.killAll();
        server.getScheduler().performTicks(45);

        assertEquals("party finished", PlainTextComponentSerializer.plainText().serialize(player.nextComponentMessage()));
        assertNull(player.nextComponentMessage(), "and it is only said once");
    }
}
