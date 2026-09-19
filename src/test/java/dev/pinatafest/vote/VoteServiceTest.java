package dev.pinatafest.vote;

import com.vexsoftware.votifier.model.Vote;
import com.vexsoftware.votifier.model.VotifierEvent;
import dev.pinatafest.config.Settings;
import dev.pinatafest.hook.VotifierHook;
import dev.pinatafest.message.Messages;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoteServiceTest {

    private static final String CONFIG = """
            votes:
              listen: true
              offline:
                enabled: true
                max_queue: 2
            rewards:
              vote:
                always:
                  chance: 100
                  commands:
                    - "record %player% %service% %votes%"
                live_only:
                  chance: 100
                  ignore_offline: true
                  commands:
                    - "live %player%"
              milestones:
                second:
                  type: total
                  votes: 2
                  commands:
                    - "milestone %player%"
            """;

    private ServerMock server;
    private JavaPlugin plugin;
    private VoteService service;
    private VoteStore store;
    private final List<String> ran = new ArrayList<>();

    @BeforeEach
    void setUp(@TempDir Path dir) throws Exception {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        store = new VoteStore(dir.resolve("votes.yml"));
        final Settings settings = Settings.load(YamlConfiguration.loadConfiguration(new java.io.StringReader(CONFIG)),
                Logger.getAnonymousLogger());
        service = new VoteService(plugin, () -> settings, store, new Messages(plugin));

        for (String name : List.of("record", "live", "milestone")) {
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
    void onlineVoteIsPaidStraightAway() {
        server.addPlayer("Steve");
        service.receive("steve", "SiteA");

        assertEquals(List.of("record Steve SiteA 1", "live Steve"), ran);
        assertEquals(1, store.find("Steve").total());
    }

    @Test
    void milestoneRunsWhenTheTotalIsReached() {
        server.addPlayer("Steve");
        service.receive("Steve", "SiteA");
        service.receive("Steve", "SiteA");

        assertTrue(ran.contains("milestone Steve"));
    }

    @Test
    void offlineVoteWaitsAndIsPaidOnLogin() {
        service.receive("Alex", "SiteB");
        assertTrue(ran.isEmpty());
        assertEquals(1, store.find("Alex").queue().size());

        final var alex = server.addPlayer("Alex");
        service.payQueued(alex);

        // the live_only entry is skipped because the vote came in while Alex was away
        assertEquals(List.of("record Alex SiteB 1"), ran);
        assertTrue(store.find("Alex").queue().isEmpty());
    }

    @Test
    void queueStopsAtTheLimit() {
        service.receive("Alex", "A");
        service.receive("Alex", "B");
        service.receive("Alex", "C");

        assertEquals(2, store.find("Alex").queue().size());
    }

    @Test
    void storeSurvivesARestart(@TempDir Path dir) throws Exception {
        final VoteStore first = new VoteStore(dir.resolve("data.yml"));
        final VoteStore.Entry entry = first.entry("Steve");
        entry.addVote();
        entry.addVote();
        entry.setLastVote(1234L);
        entry.queue().add(new VoteStore.Queued("SiteA", 99L));
        first.write(first.snapshot());

        final VoteStore second = new VoteStore(dir.resolve("data.yml"));
        second.load(Logger.getAnonymousLogger());
        assertEquals(2, second.find("steve").total());
        assertEquals(1234L, second.find("steve").lastVote());
        assertEquals("SiteA", second.find("steve").queue().get(0).service());
    }

    @Test
    void votifierEventReachesTheService() {
        final List<String> seen = new ArrayList<>();
        assertTrue(VotifierHook.register(plugin, (name, site) -> seen.add(name + "@" + site)));

        server.getPluginManager().callEvent(new VotifierEvent(new Vote("Steve", "SiteA")));

        assertEquals(List.of("Steve@SiteA"), seen);
    }
}
