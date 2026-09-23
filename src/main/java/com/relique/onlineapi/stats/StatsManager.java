package com.relique.onlineapi.stats;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Tracks per-player Elo, kills, deaths and playtime, and persists them to
 * stats.yml so they survive restarts.
 */
public class StatsManager {

    /** Elo rank tiers, lowest threshold first. Elo >= a tier's threshold (and below the next one) gets that rank. */
    private static final Object[][] TIERS = {
            {0.0,    "Rookie"},
            {800.0,  "Combat Novice"},
            {950.0,  "Combat Cadet"},
            {1100.0, "Combat Specialist"},
            {1250.0, "Combat Ace"},
            {1400.0, "Combat Master"},
            {1600.0, "Combat Grandmaster"},
    };

    public static String rankFor(double elo) {
        String rank = (String) TIERS[0][1];
        for (Object[] tier : TIERS) {
            double threshold = (double) tier[0];
            if (elo >= threshold) {
                rank = (String) tier[1];
            } else {
                break;
            }
        }
        return rank;
    }

    public static class PlayerStats {
        public UUID uuid;
        public String name;
        public double elo;
        public int kills;
        public int deaths;
        public long playtimeSeconds;
        public long sessionStart;     // epoch millis this session began, 0 if offline

        public double kd() {
            return deaths == 0 ? kills : (double) kills / deaths;
        }

        public String rank() {
            return rankFor(elo);
        }
    }

    private final JavaPlugin plugin;
    private final File file;
    private final Map<UUID, PlayerStats> stats = new HashMap<>();

    // Anti-farm: last time (millis) this killer/victim pair produced an Elo change, keyed by
    // the two UUIDs in a fixed order so it doesn't matter who killed whom.
    private final Map<String, Long> recentPairCredit = new HashMap<>();

    private final double startingElo;
    private final double k;
    private final long pairCooldownMillis;

    public StatsManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "stats.yml");
        this.startingElo = plugin.getConfig().getDouble("elo.starting", 0);
        this.k = plugin.getConfig().getDouble("elo.k", 24);
        this.pairCooldownMillis = plugin.getConfig().getLong("elo.same-pair-cooldown-seconds", 300) * 1000L;
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
    }

    /** Rolls currently-open sessions into playtimeSeconds without ending them. Call this on autosave. */
    public synchronized void flushSessions() {
        long now = System.currentTimeMillis();
        for (PlayerStats s : stats.values()) {
            if (s.sessionStart != 0) {
                s.playtimeSeconds += (now - s.sessionStart) / 1000L;
                s.sessionStart = now;
            }
        }
    }

    public synchronized void recordEnvironmentalDeath(UUID victim, String victimName) {
        // Counts toward deaths/K-D, but never touches Elo — Elo is PvP-only.
        get(victim, victimName).deaths++;
    }

    /** Records a PvP kill and applies a competitive Elo update between the two players. */
    public synchronized void recordKill(UUID killerUuid, String killerName, UUID victimUuid, String victimName) {
        PlayerStats killer = get(killerUuid, killerName);
        PlayerStats victim = get(victimUuid, victimName);

        killer.kills++;
        victim.deaths++;

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
            if (victim.elo < 0) victim.elo = 0;
        }
    }

    public synchronized Collection<PlayerStats> all() {
        return stats.values();
    }

    public synchronized void load() {
        if (!file.exists()) return;
        FileConfiguration yml = YamlConfiguration.loadConfiguration(file);
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
                s.playtimeSeconds = yml.getLong("players." + key + ".playtime-seconds", 0);
                s.sessionStart = 0;
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
            yml.set(base + "playtime-seconds", s.playtimeSeconds);
        }
        try {
            if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
            yml.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not save stats.yml", e);
        }
    }
}
