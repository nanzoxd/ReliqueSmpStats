package com.relique.onlineapi.dialog;

import com.relique.onlineapi.OnlineApiPlugin;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Cached "SMP rank" (the rank number from the RankedSMPX plugin). The database is read on an
 * async timer, so opening a dialog never touches the database on the main thread.
 */
public final class RankedLookup {

    private final OnlineApiPlugin plugin;
    private volatile Map<UUID, Integer> ranks = new HashMap<>();

    public RankedLookup(OnlineApiPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        long period = Math.max(5L, plugin.getConfig().getLong("ranked.cache-seconds", 10) * 3L) * 20L;
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::refresh, 40L, period);
    }

    /** SMP rank number, or -1 when RankedSMPX is missing or the player holds no rank. */
    public int rankOf(UUID uuid) {
        Integer r = ranks.get(uuid);
        return r == null ? -1 : r;
    }

    private void refresh() {
        Map<UUID, Integer> out = new HashMap<>();
        try {
            String pluginName = plugin.getConfig().getString("ranked.plugin-name", "RankedSMPX");
            Plugin rs = Bukkit.getPluginManager().getPlugin(pluginName);
            if (rs == null || !rs.isEnabled()) { ranks = out; return; }

            File folder = rs.getDataFolder();
            FileConfiguration cfg = YamlConfiguration.loadConfiguration(new File(folder, "config.yml"));
            int total = Math.max(1, Math.min(500, cfg.getInt("season.total-ranks", 20)));
            String type = cfg.getString("database.type", "sqlite").toLowerCase();

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
                if (!db.exists()) { ranks = out; return; }
                try { Class.forName("org.sqlite.JDBC"); } catch (Throwable ignored) { /* DriverManager finds it */ }
                conn = DriverManager.getConnection("jdbc:sqlite:" + db.getAbsolutePath());
            }
            try (Connection c = conn;
                 PreparedStatement ps = c.prepareStatement("SELECT uuid, rank FROM ranks");
                 ResultSet rs2 = ps.executeQuery()) {
                while (rs2.next()) {
                    int rank = rs2.getInt("rank");
                    if (rank < 1 || rank > total) continue;
                    try { out.put(UUID.fromString(rs2.getString("uuid")), rank); }
                    catch (IllegalArgumentException ignored) { /* bad row */ }
                }
            }
            ranks = out;
        } catch (Throwable t) {
            plugin.getLogger().warning("Could not read SMP ranks from RankedSMPX: " + t);
        }
    }
}
