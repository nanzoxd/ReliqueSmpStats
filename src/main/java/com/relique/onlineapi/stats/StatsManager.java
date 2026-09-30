package com.relique.onlineapi.stats;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Tracks per-player Elo, kills, deaths, playtime and blocks mined, and
 * persists them to stats.yml so they survive restarts.
 */
public class StatsManager {

    /**
     * Static Elo tiers, lowest first. These cover everyone up through
     * "Combat Master". "Combat Grandmaster" is NOT on this list — it's not
     * a fixed Elo threshold, it's reserved for only the top players (see
     * grandmasterSlots), so it can't be reached just by grinding Elo up.
     */
    private static final Object[][] TIERS = {
            {700.0,  "Rookie"},
            {950.0,  "Combat Novice"},
            {1200.0, "Combat Cadet"},
            {1450.0, "Combat Specialist"},
            {1700.0, "Combat Ace"},
            {1950.0, "Combat Master"},
    };
    private static final double MASTER_THRESHOLD = 1950.0;
    private static final String GRANDMASTER = "Combat Grandmaster";

    /** Elo tier by number alone, ignoring the Grandmaster leaderboard slots. */
    public static String tierForElo(double elo) {
        String tier = (String) TIERS[0][1];
        for (Object[] t : TIERS) {
            double threshold = (double) t[0];
            if (elo >= threshold) {
                tier = (String) t[1];
            } else {
                break;
            }
        }
        return tier;
    }

    /** One PvP fight from a player's point of view. Newest first, capped at MAX_FIGHTS. */
    public static class Fight {
        public long time;        // epoch millis
        public String opponent;
        public boolean win;
        public int eloChange;    // 0 when the same-pair cooldown blocked the Elo change
    }

    private static final int MAX_FIGHTS = 10;

    /** One entry in the server-wide "recent events" feed (kills and deaths). */
    public static class Event {
        public long time;
        public String type;        // "pvp" (killed by a player) or "env" (any other death)
        public String victim;
        public UUID victimUuid;
        public String killer;      // null for env deaths
        public UUID killerUuid;    // null for env deaths
        public String cause;       // e.g. "smashed", "fell from a high place"
        public int killerElo;      // Elo the killer gained (0 if none)
        public int victimElo;      // Elo the victim lost (negative, 0 if none)
    }

    private static final int MAX_EVENTS = 100;
    private final List<Event> events = new ArrayList<>();

    public static class PlayerStats {
        public UUID uuid;
        public String name;
        public double elo;
        public int kills;
        public int deaths;
        public int blocksMined;
        public int pvpMatches;          // Elo-eligible kills/deaths this player has been part of
        public long playtimeSeconds;
        public long playtimeHoursPaid;  // whole hours of playtime already converted to Elo
        public long sessionStart;       // epoch millis this session began, 0 if offline
        public final List<Fight> fights = new ArrayList<>();

        public double kd() {
            return deaths == 0 ? kills : (double) kills / deaths;
        }
    }

    public static class KillResult {
        public boolean applied;
        public double killerEloBefore, killerEloAfter;
        public double victimEloBefore, victimEloAfter;
        public String killerRankAfter, victimRankAfter;
        public int kGain() { return (int) Math.round(killerEloAfter - killerEloBefore); }
        public int vChange() { return (int) Math.round(victimEloAfter - victimEloBefore); }
    }

    private final JavaPlugin plugin;
    private final File file;
    private final Map<UUID, PlayerStats> stats = new HashMap<>();

    // Anti-farm: last time (millis) this killer/victim pair produced an Elo change, keyed by
    // the two UUIDs in a fixed order so it doesn't matter who killed whom.
    private final Map<String, Long> recentPairCredit = new HashMap<>();

    private final double startingElo;
    private final double floorElo;
    private final double k;
    private final long pairCooldownMillis;
    private final int grandmasterSlots;
    private final double playtimeEloPerHour;

    public StatsManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "stats.yml");
        // New players start at the base of the ladder.
        this.startingElo = plugin.getConfig().getDouble("elo.starting", 950);
        this.floorElo = plugin.getConfig().getDouble("elo.floor", 700);
        this.k = plugin.getConfig().getDouble("elo.k", 24);
        this.pairCooldownMillis = plugin.getConfig().getLong("elo.same-pair-cooldown-seconds", 300) * 1000L;
        this.grandmasterSlots = plugin.getConfig().getInt("elo.grandmaster-slots", 2);
        this.playtimeEloPerHour = plugin.getConfig().getDouble("elo.playtime-reward-per-hour", 5);
        load();
    }

    public synchronized PlayerStats get(UUID uuid, String name) {
        PlayerStats s = stats.get(uuid);
        if (s == null) {
            s = new PlayerStats();
            s.uuid = uuid;
            s.name = name;
            s.elo = startingElo;
            stats.put(uuid, s);
        } else if (name != null) {
            s.name = name; // keep the display name fresh across name changes
        }
        return s;
    }

    public synchronized void onJoin(UUID uuid, String name) {
        get(uuid, name).sessionStart = System.currentTimeMillis();
    }

    public synchronized void onQuit(UUID uuid) {
        PlayerStats s = stats.get(uuid);
        if (s == null || s.sessionStart == 0) return;
        s.playtimeSeconds += (System.currentTimeMillis() - s.sessionStart) / 1000L;
        s.sessionStart = 0;
        payPlaytimeElo(s, null); // player is leaving, no point messaging them
    }

    /** Rolls currently-open sessions into playtimeSeconds without ending them. Call this on autosave. */
    public synchronized void flushSessions() {
        long now = System.currentTimeMillis();
        for (PlayerStats s : stats.values()) {
            if (s.sessionStart != 0) {
                s.playtimeSeconds += (now - s.sessionStart) / 1000L;
                s.sessionStart = now;
                payPlaytimeElo(s, Bukkit.getPlayer(s.uuid));
            }
        }
    }

    /**
     * Converts newly-completed whole hours of playtime into Elo. A player who has
     * banked, say, 3 new whole hours since the last payout gets 3x the per-hour
     * reward in one go. Safe to call often — it only ever pays for hours not yet paid.
     */
    private void payPlaytimeElo(PlayerStats s, Player online) {
        if (playtimeEloPerHour <= 0) return;
        long wholeHoursNow = s.playtimeSeconds / 3600;
        if (wholeHoursNow <= s.playtimeHoursPaid) return;
        long newHours = wholeHoursNow - s.playtimeHoursPaid;
        double gain = newHours * playtimeEloPerHour;
        s.elo += gain;
        s.playtimeHoursPaid = wholeHoursNow;
        if (online != null) {
            online.sendMessage("§b+" + Math.round(gain) + " Elo §7for " + newHours
                    + (newHours == 1 ? " hour" : " hours") + " played §7(now §f"
                    + Math.round(s.elo) + " Elo §7— §e" + rankOf(s) + "§7)");
        }
    }

    private void addFight(PlayerStats s, String opponent, boolean win, int eloChange) {
        Fight f = new Fight();
        f.time = System.currentTimeMillis();
        f.opponent = opponent;
        f.win = win;
        f.eloChange = eloChange;
        s.fights.add(0, f);
        while (s.fights.size() > MAX_FIGHTS) s.fights.remove(s.fights.size() - 1);
    }

    private void addEvent(String type, String victim, UUID victimUuid, String killer, UUID killerUuid,
                          String cause, int killerElo, int victimElo) {
        Event ev = new Event();
        ev.time = System.currentTimeMillis();
        ev.type = type;
        ev.victim = victim;
        ev.victimUuid = victimUuid;
        ev.killer = killer;
        ev.killerUuid = killerUuid;
        ev.cause = cause == null ? "" : cause.replace(';', ',');
        ev.killerElo = killerElo;
        ev.victimElo = victimElo;
        events.add(0, ev);
        while (events.size() > MAX_EVENTS) events.remove(events.size() - 1);
    }

    /** Newest-first copy of the recent events feed. */
    public synchronized List<Event> recentEvents(int limit) {
        int n = Math.min(Math.max(limit, 0), events.size());
        return new ArrayList<>(events.subList(0, n));
    }

    public synchronized void recordEnvironmentalDeath(UUID victim, String victimName, String cause) {
        // Counts toward deaths/K-D, but never touches Elo — Elo is PvP-only (plus playtime rewards).
        PlayerStats v = get(victim, victimName);
        v.deaths++;
        addEvent("env", v.name, victim, null, null, cause, 0, 0);
    }

    public synchronized void recordEnvironmentalDeath(UUID victim, String victimName) {
        recordEnvironmentalDeath(victim, victimName, "died");
    }

    public synchronized void recordBlockMined(UUID uuid, String name) {
        get(uuid, name).blocksMined++;
    }

    /** Records a PvP kill and applies a competitive Elo update between the two players. */
    public synchronized KillResult recordKill(UUID killerUuid, String killerName, UUID victimUuid, String victimName) {
        return recordKill(killerUuid, killerName, victimUuid, victimName, "killed");
    }

    public synchronized KillResult recordKill(UUID killerUuid, String killerName, UUID victimUuid, String victimName,
                                              String cause) {
        PlayerStats killer = get(killerUuid, killerName);
        PlayerStats victim = get(victimUuid, victimName);

        killer.kills++;
        victim.deaths++;

        KillResult result = new KillResult();
        result.killerEloBefore = killer.elo;
        result.victimEloBefore = victim.elo;

        String pairKey = killerUuid.compareTo(victimUuid) < 0
                ? killerUuid + ":" + victimUuid
                : victimUuid + ":" + killerUuid;
        long now = System.currentTimeMillis();
        Long last = recentPairCredit.get(pairKey);
        boolean eloEligible = (last == null) || (now - last >= pairCooldownMillis);

        if (eloEligible) {
            recentPairCredit.put(pairKey, now);

            double expectedKiller = 1.0 / (1.0 + Math.pow(10, (victim.elo - killer.elo) / 400.0));
            double expectedVictim = 1.0 - expectedKiller;

            killer.elo += k * (1.0 - expectedKiller);
            victim.elo += k * (0.0 - expectedVictim);
            // Elo can never drop below the base floor — 700 is rock bottom, not a punishment pit.
            if (victim.elo < floorElo) victim.elo = floorElo;

            killer.pvpMatches++;
            victim.pvpMatches++;
        }

        result.applied = eloEligible;
        result.killerEloAfter = killer.elo;
        result.victimEloAfter = victim.elo;
        result.killerRankAfter = rankOf(killer);
        result.victimRankAfter = rankOf(victim);
        addFight(killer, victim.name, true, result.kGain());
        addFight(victim, killer.name, false, result.vChange());
        addEvent("pvp", victim.name, victimUuid, killer.name, killerUuid, cause, result.kGain(), result.vChange());
        return result;
    }

    /**
     * The players currently holding "Combat Grandmaster" — the top
     * `grandmaster-slots` (default 2) players who are also above the
     * Combat Master Elo threshold. Everyone else, no matter how high their
     * Elo, is capped at "Combat Master" until one of these seats opens up.
     */
    public synchronized List<PlayerStats> currentGrandmasters() {
        List<PlayerStats> eligible = new ArrayList<>();
        for (PlayerStats s : stats.values()) {
            if (s.pvpMatches > 0 && s.elo >= MASTER_THRESHOLD) eligible.add(s);
        }
        eligible.sort(Comparator.comparingDouble((PlayerStats s) -> s.elo).reversed());
        if (eligible.size() > grandmasterSlots) {
            return eligible.subList(0, grandmasterSlots);
        }
        return eligible;
    }

    /** Full rank for one player, accounting for the Grandmaster leaderboard slots. */
    public synchronized String rankOf(PlayerStats s) {
        String base = tierForElo(s.elo);
        if (base.equals("Combat Master") && currentGrandmasters().contains(s)) {
            return GRANDMASTER;
        }
        return base;
    }

    public synchronized Collection<PlayerStats> all() {
        return stats.values();
    }

    public synchronized void load() {
        if (!file.exists()) return;
        FileConfiguration yml = YamlConfiguration.loadConfiguration(file);
        for (String line : yml.getStringList("events")) {
            String[] p = line.split(";", -1); // time;type;victim;victimUuid;killer;killerUuid;cause;kElo;vElo
            if (p.length != 9) continue;
            try {
                Event ev = new Event();
                ev.time = Long.parseLong(p[0]);
                ev.type = p[1];
                ev.victim = p[2];
                ev.victimUuid = p[3].isEmpty() ? null : UUID.fromString(p[3]);
                ev.killer = p[4].isEmpty() ? null : p[4];
                ev.killerUuid = p[5].isEmpty() ? null : UUID.fromString(p[5]);
                ev.cause = p[6];
                ev.killerElo = Integer.parseInt(p[7]);
                ev.victimElo = Integer.parseInt(p[8]);
                events.add(ev);
            } catch (IllegalArgumentException ignored) { /* skip bad line */ }
        }
        if (!yml.isConfigurationSection("players")) return;
        for (String key : yml.getConfigurationSection("players").getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                PlayerStats s = new PlayerStats();
                s.uuid = uuid;
                s.name = yml.getString("players." + key + ".name", "Unknown");
                s.elo = yml.getDouble("players." + key + ".elo", startingElo);
                s.kills = yml.getInt("players." + key + ".kills", 0);
                s.deaths = yml.getInt("players." + key + ".deaths", 0);
                s.blocksMined = yml.getInt("players." + key + ".blocks-mined", 0);
                s.pvpMatches = yml.getInt("players." + key + ".pvp-matches", 0);
                s.playtimeSeconds = yml.getLong("players." + key + ".playtime-seconds", 0);
                s.playtimeHoursPaid = yml.getLong("players." + key + ".playtime-hours-paid", 0);
                s.sessionStart = 0;
                for (String line : yml.getStringList("players." + key + ".fights")) {
                    String[] parts = line.split(";", 4); // time;win;change;opponent
                    if (parts.length < 4) continue;
                    try {
                        Fight f = new Fight();
                        f.time = Long.parseLong(parts[0]);
                        f.win = "1".equals(parts[1]);
                        f.eloChange = Integer.parseInt(parts[2]);
                        f.opponent = parts[3];
                        s.fights.add(f);
                    } catch (NumberFormatException ignored) { /* skip bad line */ }
                }
                stats.put(uuid, s);
            } catch (IllegalArgumentException ignored) {
                plugin.getLogger().log(Level.WARNING, "Skipping malformed stats entry: " + key);
            }
        }
    }

    public synchronized void save() {
        YamlConfiguration yml = new YamlConfiguration();
        for (PlayerStats s : stats.values()) {
            String base = "players." + s.uuid + ".";
            yml.set(base + "name", s.name);
            yml.set(base + "elo", s.elo);
            yml.set(base + "kills", s.kills);
            yml.set(base + "deaths", s.deaths);
            yml.set(base + "blocks-mined", s.blocksMined);
            yml.set(base + "pvp-matches", s.pvpMatches);
            yml.set(base + "playtime-seconds", s.playtimeSeconds);
            yml.set(base + "playtime-hours-paid", s.playtimeHoursPaid);
            List<String> fl = new ArrayList<>();
            for (Fight f : s.fights) fl.add(f.time + ";" + (f.win ? "1" : "0") + ";" + f.eloChange + ";" + f.opponent);
            yml.set(base + "fights", fl);
        }
        List<String> evLines = new ArrayList<>();
        for (Event ev : events) {
            evLines.add(ev.time + ";" + ev.type + ";" + ev.victim + ";" + (ev.victimUuid == null ? "" : ev.victimUuid)
                    + ";" + (ev.killer == null ? "" : ev.killer) + ";" + (ev.killerUuid == null ? "" : ev.killerUuid)
                    + ";" + ev.cause + ";" + ev.killerElo + ";" + ev.victimElo);
        }
        yml.set("events", evLines);
        try {
            if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
            yml.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not save stats.yml", e);
        }
    }
}
