package dev.pinatafest.pinata;

import dev.pinatafest.config.Settings;
import dev.pinatafest.message.Messages;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Llama;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * One pinata in the world: its health, who has hit it, and everything players see of it (name,
 * boss bar, glow, carpet and particles). The rules for hits and rewards live in PinataService.
 */
public final class Pinata {

    static final String TEAM_PREFIX = "pf_";

    private static final List<Material> CARPETS = List.of(
            Material.WHITE_CARPET, Material.ORANGE_CARPET, Material.MAGENTA_CARPET, Material.LIGHT_BLUE_CARPET,
            Material.YELLOW_CARPET, Material.LIME_CARPET, Material.PINK_CARPET, Material.GRAY_CARPET,
            Material.LIGHT_GRAY_CARPET, Material.CYAN_CARPET, Material.PURPLE_CARPET, Material.BLUE_CARPET,
            Material.BROWN_CARPET, Material.GREEN_CARPET, Material.RED_CARPET, Material.BLACK_CARPET);

    private static final List<NamedTextColor> GLOWS = List.of(
            NamedTextColor.RED, NamedTextColor.GOLD, NamedTextColor.YELLOW, NamedTextColor.GREEN,
            NamedTextColor.AQUA, NamedTextColor.BLUE, NamedTextColor.LIGHT_PURPLE, NamedTextColor.WHITE);

    private static final BossBar.Color[] BAR_COLORS = BossBar.Color.values();

    private final Llama llama;
    private final Settings.PinataSettings settings;
    private final int maxHealth;
    private final BossBar bar;
    private final Set<UUID> barViewers = new HashSet<>();
    private final Set<String> participants = new LinkedHashSet<>();
    private final Map<UUID, Long> nextHit = new HashMap<>();

    private int health;
    private int age;
    private boolean nearby = true;
    private boolean textsDirty = true;
    private int babyEnds = -1;
    private String lastHitter = "";
    private Team glowTeam;

    Pinata(Llama llama, Settings.PinataSettings settings, int health) {
        this.llama = llama;
        this.settings = settings;
        this.maxHealth = health;
        this.health = health;
        this.bar = BossBar.bossBar(net.kyori.adventure.text.Component.empty(), 1f,
                settings.bar().color() == null ? BossBar.Color.PINK : settings.bar().color(),
                settings.bar().overlay());
        applyLook();
    }

    public Llama entity() {
        return llama;
    }

    public Location location() {
        return llama.getLocation();
    }

    public boolean isAlive() {
        return llama.isValid();
    }

    public int health() {
        return health;
    }

    public int maxHealth() {
        return maxHealth;
    }

    public Set<String> participants() {
        return participants;
    }

    public String lastHitter() {
        return lastHitter;
    }

    BossBar bar() {
        return bar;
    }

    Set<UUID> barViewers() {
        return barViewers;
    }

    int age() {
        return age;
    }

    /** True if this player may hit now, and starts their cooldown. */
    boolean tryHit(UUID player, long nowMillis, double cooldownSeconds) {
        final Long until = nextHit.get(player);
        if (until != null && nowMillis < until) {
            return false;
        }
        nextHit.put(player, nowMillis + (long) (cooldownSeconds * 1000));
        return true;
    }

    /** Counts one hit and returns how many are left. */
    int registerHit(String player) {
        textsDirty = true;
        participants.add(player);
        lastHitter = player;
        return --health;
    }

    void makeBaby(int untilAge) {
        llama.setBaby();
        llama.setAgeLock(true);
        babyEnds = untilAge;
    }

    /** Ends the baby form when its time is up. Returns true on the tick it happens. */
    boolean checkGrownUp() {
        if (babyEnds >= 0 && age >= babyEnds) {
            babyEnds = -1;
            llama.setAgeLock(false);
            llama.setAdult();
            return true;
        }
        return false;
    }

    /** Whether any player is close enough to see it; the service updates this once a second. */
    void setNearby(boolean nearby) {
        this.nearby = nearby;
    }

    /**
     * Called every tick. Nothing that only nearby players can see is done when nobody is near, and
     * the texts are only rebuilt when a hit changed them or the colour flow moves on.
     */
    void tick(Messages messages) {
        age++;
        if (nearby) {
            spin();
            if (age % 5 == 0) {
                cycleLook();
            }
        }
        final int rate = settings.look().animationTicks();
        if (textsDirty || (rate > 0 && age % rate == 0)) {
            textsDirty = false;
            refreshTexts(messages);
        }
    }

    void cleanUp() {
        if (glowTeam != null) {
            glowTeam.removeEntity(llama);
        }
        if (llama.isValid()) {
            llama.remove();
        }
    }

    // ---- look ----

    private void applyLook() {
        final Settings.Look look = settings.look();
        llama.setPersistent(false);
        llama.setRemoveWhenFarAway(false);
        llama.setCustomNameVisible(true);
        llama.setCollidable(false);
        llama.setAggressive(false);
        llama.setCarryingChest(look.chest());
        llama.setColor(llamaColor(look.llamaColor()));
        if (!look.carpet().equalsIgnoreCase("none")) {
            llama.getInventory().setDecor(new ItemStack(carpet(look.carpet(), 0)));
        }
        if (look.glow()) {
            llama.setGlowing(true);
            setGlow(glow(look.glowColor(), 0));
        }
    }

    private void cycleLook() {
        final Settings.Look look = settings.look();
        final int step = age / 5;
        if (look.carpet().equalsIgnoreCase("cycle") && step % 2 == 0) {
            llama.getInventory().setDecor(new ItemStack(carpet("cycle", step / 2)));
        }
        if (look.glow() && look.glowColor().equalsIgnoreCase("cycle")) {
            setGlow(glow("cycle", step));
        }
        if (settings.bar().color() == null && step % 2 == 0) {
            bar.color(BAR_COLORS[(step / 2) % BAR_COLORS.length]);
        }
    }

    private void refreshTexts(Messages messages) {
        final var resolvers = new net.kyori.adventure.text.minimessage.tag.resolver.TagResolver[]{
                Messages.text("hits", health), Messages.text("max", maxHealth),
                Messages.text("plural", health == 1 ? "" : "S"),
                Placeholder.parsed("phase", String.format(Locale.ROOT, "%.2f", (age % 100) / 50.0 - 1.0))};
        if (nearby) {
            llama.customName(messages.chat("pinata_name", resolvers));
        }
        if (settings.bar().enabled()) {
            bar.name(messages.chat("pinata_bar", resolvers));
            bar.progress(Math.max(0f, Math.min(1f, health / (float) maxHealth)));
        }
    }

    private void spin() {
        final Settings.Look look = settings.look();
        if (look.particle() == null || look.particleCount() == 0) {
            return;
        }
        final Location at = llama.getLocation();
        final double angle = age * 0.35;
        for (int i = 0; i < look.particleCount(); i++) {
            final double a = angle + i * (2 * Math.PI / look.particleCount());
            at.getWorld().spawnParticle(look.particle(), at.getX() + Math.cos(a) * 1.1, at.getY() + 1.4,
                    at.getZ() + Math.sin(a) * 1.1, 1, 0, 0, 0, 0);
        }
    }

    private void setGlow(NamedTextColor color) {
        final Team team = team(color);
        if (glowTeam != team) {
            if (glowTeam != null) {
                glowTeam.removeEntity(llama);
            }
            team.addEntity(llama);
            glowTeam = team;
        }
    }

    private static Team team(NamedTextColor color) {
        final Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
        final String name = TEAM_PREFIX + NamedTextColor.NAMES.key(color);
        Team team = board.getTeam(name);
        if (team == null) {
            team = board.registerNewTeam(name);
            team.color(color);
        }
        return team;
    }

    private static Llama.Color llamaColor(String name) {
        if (name.equalsIgnoreCase("random")) {
            final Llama.Color[] all = Llama.Color.values();
            return all[ThreadLocalRandom.current().nextInt(all.length)];
        }
        try {
            return Llama.Color.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return Llama.Color.WHITE;
        }
    }

    private static Material carpet(String setting, int step) {
        if (setting.equalsIgnoreCase("cycle")) {
            return CARPETS.get(step % CARPETS.size());
        }
        if (setting.equalsIgnoreCase("random")) {
            return CARPETS.get(ThreadLocalRandom.current().nextInt(CARPETS.size()));
        }
        final Material named = Material.matchMaterial(setting.toUpperCase(Locale.ROOT).endsWith("_CARPET")
                ? setting : setting + "_carpet");
        return named != null ? named : Material.WHITE_CARPET;
    }

    private static NamedTextColor glow(String setting, int step) {
        if (setting.equalsIgnoreCase("cycle")) {
            return GLOWS.get(step % GLOWS.size());
        }
        if (setting.equalsIgnoreCase("random")) {
            return GLOWS.get(ThreadLocalRandom.current().nextInt(GLOWS.size()));
        }
        final NamedTextColor named = NamedTextColor.NAMES.value(setting.toLowerCase(Locale.ROOT));
        return named != null ? named : NamedTextColor.WHITE;
    }
}
