package dev.pinatafest.vote;

import com.vexsoftware.votifier.model.Vote;
import com.vexsoftware.votifier.model.VotifierEvent;
import dev.pinatafest.config.Settings;
import dev.pinatafest.hook.VotifierHook;
import dev.pinatafest.message.Messages;
import dev.pinatafest.spawn.SpawnStore;
import dev.pinatafest.pinata.PinataService;
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

import java.io.StringReader;
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
                queue_rewards: true
                max_queue: 2
            pinata:
              locations:
                spawn:
                  world: world
                  x: 0
                  y: 80
                  z: 0
              auto_summon:
                votes_needed: 3
                locations: [spawn]
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
            """;

    private ServerMock server;
    private JavaPlugin plugin;
    private VoteService service;
    private VoteStore store;
    private final List<String> ran = new ArrayList<>();

    @BeforeEach
    void setUp(@TempDir Path dir) {
        server = MockBukkit.mock();
        server.addSimpleWorld("world");
        plugin = MockBukkit.createMockPlugin();
        store = new VoteStore(dir.resolve("votes.yml"));
        final Settings settings = Settings.load(YamlConfiguration.loadConfiguration(new StringReader(CONFIG)),
                Logger.getAnonymousLogger());
        final Messages messages = new Messages(plugin);
        final PinataService pinatas = new PinataService(plugin, () -> settings, messages, store, new SpawnStore(dir.resolve("s.yml")));
        service = new VoteService(plugin, () -> settings, store, messages, pinatas);

        for (String name : List.of("record", "live")) {
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
    void offlineVoteCountsRightAwayButPaysOnLogin() {
        service.receive("Alex", "SiteB");

        // nothing is paid yet, but the vote already counts for the player and for the pinata
        assertTrue(ran.isEmpty());
        assertEquals(1, store.find("Alex").total());
        assertEquals(1, store.pinataVotes());
        assertEquals(1, store.find("Alex").queue().size());

        final var alex = server.addPlayer("Alex");
        service.payQueued(alex);

        // the live_only entry is skipped because the vote came in while Alex was away
        assertEquals(List.of("record Alex SiteB 1"), ran);
        assertTrue(store.find("Alex").queue().isEmpty());
        assertEquals(1, store.find("Alex").total());
    }

    @Test
    void queuedRewardsArePaidOnceAndTheQueueStaysEmptyAfterARestart() throws Exception {
        final Logger log = Logger.getAnonymousLogger();
        store.load(log, 3);
        service.receive("Alex", "SiteB");
        service.payQueued(server.addPlayer("Alex"));
        assertEquals(List.of("record Alex SiteB 1"), ran);
        store.close(log);

        final VoteStore again = new VoteStore(store.databaseFile().resolveSibling("votes.yml"));
        again.load(log, 3);
        assertTrue(again.find("Alex").queue().isEmpty());
        assertEquals(1, again.find("Alex").total());
        assertTrue(again.journal().unknown().isEmpty());
        again.close(log);
    }

    @Test
    void queueStopsAtTheLimitButVotesKeepCounting() {
        service.receive("Alex", "A");
        service.receive("Alex", "B");

        assertEquals(2, store.find("Alex").queue().size());
        assertEquals(2, store.find("Alex").total());
    }

    @Test
    void reachingTheGoalRestartsTheCounter() {
        service.receive("Alex", "A");
        service.receive("Steve", "A");
        assertEquals(2, store.pinataVotes());

        service.receive("Alex", "B");
        assertEquals(0, store.pinataVotes());
    }

    @Test
    void storeSurvivesARestart(@TempDir Path dir) throws Exception {
        final VoteStore first = new VoteStore(dir.resolve("data.yml"));
        first.load(Logger.getAnonymousLogger(), 3);
        final VoteStore.Entry entry = first.entry("Steve");
        entry.addVote(1234L);
        entry.addVote(1235L);
        entry.queue().add(new VoteStore.Queued("SiteA", 99L));
        first.setPinataVotes(7);
        assertTrue(first.flush(Logger.getAnonymousLogger()));
        first.close(Logger.getAnonymousLogger());

        final VoteStore second = new VoteStore(dir.resolve("data.yml"));
        second.load(Logger.getAnonymousLogger(), 3);
        assertEquals(2, second.find("steve").total());
        assertEquals(1235L, second.find("steve").lastVote());
        assertEquals("SiteA", second.find("steve").queue().get(0).service());
        assertEquals(7, second.pinataVotes());
        second.close(Logger.getAnonymousLogger());
    }

    @Test
    void votesArrivingTogetherAreAnnouncedOnce() {
        final List<String> said = new ArrayList<>();
        service.announcer((name, count) -> said.add(name + " x" + count));

        service.receive("Steve", "SiteA");
        service.receive("Steve", "SiteB");
        service.receive("Steve", "SiteC");
        service.receive("Alex", "SiteA");
        assertTrue(said.isEmpty(), "nothing is said before the window is over");

        server.getScheduler().performTicks(40);

        assertEquals(List.of("Steve x3", "Alex x1"), said);
    }

    @Test
    void aLaterVoteStartsANewAnnouncement() {
        final List<String> said = new ArrayList<>();
        service.announcer((name, count) -> said.add(name + " x" + count));

        service.receive("Steve", "SiteA");
        server.getScheduler().performTicks(40);
        service.receive("Steve", "SiteB");
        server.getScheduler().performTicks(40);

        assertEquals(List.of("Steve x1", "Steve x1"), said);
    }

    @Test
    void aResentVoteIsNotPaidTwice() {
        // a vote site that resends because it never saw our ack should not double the reward
        service.receive("Alex", "SiteA", "1790186040130");
        service.receive("Alex", "SiteA", "1790186040130");

        assertEquals(1, store.find("Alex").total());
        assertEquals(1, store.find("Alex").queue().size());
    }

    @Test
    void fastSeparateVotesAreAllCounted() {
        // same player and site voting again and again is not a resend: every vote has its own stamp
        service.receive("Alex", "SiteA", "1790186040130");
        service.receive("Alex", "SiteA", "1790186075589");
        service.receive("Alex", "SiteA", "1790186075968");
        service.receive("Alex", "SiteA");
        service.receive("Alex", "SiteA");

        assertEquals(5, store.find("Alex").total());
    }

    @Test
    void votifierEventReachesTheService() {
        final List<String> seen = new ArrayList<>();
        assertTrue(VotifierHook.register(plugin, (name, site, stamp) -> seen.add(name + "@" + site + "@" + stamp)));

        server.getPluginManager().callEvent(new VotifierEvent(new Vote("Steve", "SiteA")));

        assertEquals(List.of("Steve@SiteA@1"), seen);
    }
}
