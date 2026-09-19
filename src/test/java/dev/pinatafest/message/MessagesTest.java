package dev.pinatafest.message;

import dev.pinatafest.PinataFestPlugin;
import net.kyori.adventure.title.Title;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessagesTest {

    private PinataFestPlugin plugin;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        plugin = MockBukkit.load(PinataFestPlugin.class);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private static String plain(net.kyori.adventure.text.Component text) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(text);
    }

    @Test
    void partyStartShowsATitleAndChat() {
        final Messages messages = new Messages(plugin);
        messages.load();
        final Player player = Mockito.mock(Player.class);

        messages.broadcast(List.of(player), "party_start", Messages.text("seconds", 5));

        final ArgumentCaptor<Title> title = ArgumentCaptor.forClass(Title.class);
        Mockito.verify(player).showTitle(title.capture());
        assertEquals("PINATA PARTY", plain(title.getValue().title()));
        assertEquals("Starting in 5 seconds", plain(title.getValue().subtitle()));
        Mockito.verify(player).sendMessage(Mockito.any(net.kyori.adventure.text.Component.class));
    }

    @Test
    void theEndOfTheParty_showsTheDestroyedTitle() {
        final Messages messages = new Messages(plugin);
        messages.load();
        final Player player = Mockito.mock(Player.class);

        messages.broadcast(List.of(player), "party_over");

        final ArgumentCaptor<Title> title = ArgumentCaptor.forClass(Title.class);
        Mockito.verify(player).showTitle(title.capture());
        assertEquals("PINATA DESTROYED", plain(title.getValue().title()));
    }

    @Test
    void teleportGoesToChatAndTheActionBar() {
        final Messages messages = new Messages(plugin);
        messages.load();
        final Player player = Mockito.mock(Player.class);

        messages.broadcast(List.of(player), "pinata_teleport", Messages.text("x", 1), Messages.text("y", 2),
                Messages.text("z", 3));

        Mockito.verify(player).sendMessage(Mockito.any(net.kyori.adventure.text.Component.class));
        Mockito.verify(player).sendActionBar(Mockito.any(net.kyori.adventure.text.Component.class));
    }

    @Test
    void anOlderLangFileIsSavedAndReplaced() throws Exception {
        plugin.getDataFolder().mkdirs();
        final File file = new File(plugin.getDataFolder(), "lang.yml");
        Files.writeString(file.toPath(), "party_start:\n  enabled: true\n  type: [chat]\n  chat: \"old text\"\n");

        final Messages messages = new Messages(plugin);
        messages.load();

        assertTrue(new File(plugin.getDataFolder(), "lang.yml.old").exists(), "the old file should be kept");
        final Player player = Mockito.mock(Player.class);
        messages.broadcast(List.of(player), "party_start", Messages.text("seconds", 5));
        Mockito.verify(player).showTitle(Mockito.any(Title.class));
    }
}
