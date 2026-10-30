package com.relique.onlineapi.dialog;

import com.relique.onlineapi.OnlineApiPlugin;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Cached RankedSMPX ranks: who holds which rank, for every ranked player on the server.
 * The database is read on an async timer, so opening a dialog never touches it on the main thread.
 * Only created when ranked.enabled is true in config.yml.
 */
public final class RankedLookup {

    /** One ranked player. {@code since} is the epoch-millis time RankedSMPX last changed the row. */
    public record Entry(UUID uuid, String name, int rank, long since) {}

    private final OnlineApiPlugin plugin;
    private volatile Map<UUID, Entry> entries = new HashMap<>();
    private volatile List<Entry> sorted = List.of();
    private BukkitTask task;

    public RankedLookup(OnlineApiPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        stop();
        long seconds = Math.max(5L, plugin.getConfig().getLong("ranked.cache-seconds", 10));
        task = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::refresh, 40L, seconds * 20L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    /** SMP rank number, or -1 when RankedSMPX is missing or the player holds no rank. */
    public int rankOf(UUID uuid) {
        Entry e = entries.get(uuid);
        return e == null ? -1 : e.rank();
    }

    /** Every ranked player, best rank (#1) first. Never null. */
    public List<Entry> all() {
        return sorted;
    }

    /**
     * Number of ranks RankedSMPX has. 3.0.x: the highest row in ranks.yml. Older versions: season.total-ranks.
     * Returns Integer.MAX_VALUE when neither is found, so no valid rank is ever dropped.
     */
    public static int totalRanks(File folder, FileConfiguration cfg) {
        int total = 0;
        try {
            File ranksFile = new File(folder, "ranks.yml");
            if (ranksFile.exists()) {
                ConfigurationSection sec = YamlConfiguration.loadConfiguration(ranksFile).getConfigurationSection("ranks");
                if (sec != null) {
                    for (String key : sec.getKeys(false)) {
                        try { total = Math.max(total, Integer.parseInt(key.trim())); }
                        catch (NumberFormatException ignored) { /* not a rank row */ }
                    }
                }
            }
        } catch (Throwable ignored) { /* fall back below */ }
        if (total < 1) total = cfg.getInt("season.total-ranks", 0);
        return total < 1 ? Integer.MAX_VALUE : total;
    }

    private void refresh() {
        Map<UUID, Entry> out = new HashMap<>();
        try {
            String pluginName = plugin.getConfig().getString("ranked.plugin-name", "RankedSMPX");
            Plugin rs = Bukkit.getPluginManager().getPlugin(pluginName);
            if (rs == null || !rs.isEnabled()) { publish(out); return; }

            File folder = rs.getDataFolder();
            FileConfiguration cfg = YamlConfiguration.loadConfiguration(new File(folder, "config.yml"));
            int total = totalRanks(folder, cfg);
            String type = cfg.getString("database.type", "sqlite").toLowerCase(Locale.ROOT);

            Connection conn;
            if (type.equals("mysql")) {
                String url = "jdbc:mysql://" + cfg.getString("database.mysql.host", "localhost") + ":"
                        + cfg.getInt("database.mysql.port", 3306) + "/"
                        + cfg.getString("database.mysql.database", "rankedsmp")
                        + "?useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=3000&socketTimeout=5000";
                try { Class.forName("com.mysql.cj.jdbc.Driver"); } catch (Throwable ignored) { /* DriverManager finds it */ }
                conn = DriverManager.getConnection(url,
                        cfg.getString("database.mysql.username", "root"),
                        cfg.getString("database.mysql.password", ""));
            } else {
                File db = new File(folder, cfg.getString("database.sqlite-file", "rankedsmp.db"));
                if (!db.exists()) { publish(out); return; }
                try { Class.forName("org.sqlite.JDBC"); } catch (Throwable ignored) { /* DriverManager finds it */ }
                conn = DriverManager.getConnection("jdbc:sqlite:" + db.getAbsolutePath());
            }
            try (Connection c = conn;
                 PreparedStatement ps = c.prepareStatement("SELECT uuid, player_name, rank, last_updated FROM ranks");
                 ResultSet rs2 = ps.executeQuery()) {
                while (rs2.next()) {
                    int rank = rs2.getInt("rank");
                    if (rank < 1 || rank > total) continue; // unranked / stale rows
                    try {
                        UUID id = UUID.fromString(rs2.getString("uuid"));
                        String name = rs2.getString("player_name");
                        try {
                            String live = Bukkit.getOfflinePlayer(id).getName();
                            if (live != null) name = live;
                        } catch (Throwable ignored) { /* keep the database name */ }
                        out.put(id, new Entry(id, name == null ? "Unknown" : name, rank, rs2.getLong("last_updated")));
                    } catch (IllegalArgumentException ignored) { /* bad row */ }
                }
            }
            publish(out);
        } catch (Throwable t) {
            plugin.getLogger().warning("Could not read SMP ranks from RankedSMPX: " + t);
        }
    }

    private void publish(Map<UUID, Entry> out) {
        List<Entry> list = new ArrayList<>(out.values());
        list.sort(Comparator.comparingInt(Entry::rank).thenComparing(Entry::name, String.CASE_INSENSITIVE_ORDER));
        entries = out;
        sorted = List.copyOf(list);
    }
}
