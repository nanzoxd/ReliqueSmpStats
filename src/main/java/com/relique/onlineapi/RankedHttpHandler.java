package com.relique.onlineapi;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GET /api/ranked
 *
 * Exposes everything the RankedSMPX plugin knows, for the website's "Ranked" tab:
 *
 *  - the live rank config, read straight from plugins/RankedSMPX/config.yml (and custom.yml)
 *    every few seconds, so total-ranks (e.g. 30), hearts, XP / potion multipliers, extra
 *    inventory slots, TAB prefixes and any other "one value per rank" table you add later
 *    show up automatically after a /rsmp reload - nothing is hard-coded to 20 ranks;
 *  - who currently holds each rank, read from RankedSMPX's own database (SQLite or MySQL);
 *  - for each holder: online flag + last-seen time, so the site can show an inactivity timer.
 *
 * Like /api/teams this never answers 500: if RankedSMPX is missing or anything fails it
 * answers {"available":false,...} and the site simply hides the data.
 */
public class RankedHttpHandler implements HttpHandler {

    private static final Pattern HEX = Pattern.compile("(?i)[&\u00a7<]?#([0-9a-f]{6})");
    private static final Pattern LEGACY = Pattern.compile("(?i)[&\u00a7]([0-9a-f])");
    private static final Pattern STRIP_HEX = Pattern.compile("(?i)[&\u00a7]#[0-9a-f]{6}");
    private static final Pattern STRIP_LEGACY = Pattern.compile("(?i)[&\u00a7][0-9a-fk-or]");
    private static final Pattern STRIP_TAGS = Pattern.compile("<[^>]*>");
    private static final String[] LEGACY_COLORS = {
            "#000000", "#0000AA", "#00AA00", "#00AAAA", "#AA0000", "#AA00AA", "#FFAA00", "#AAAAAA",
            "#555555", "#5555FF", "#55FF55", "#55FFFF", "#FF5555", "#FF55FF", "#FFFF55", "#FFFFFF"
    };

    // Per-rank tables that get first-class fields. Every OTHER table whose keys are rank
    // numbers is still exported, under "perks", keyed by its config path.
    private static final String T_HEARTS = "health.custom-hearts";
    private static final String T_POTION = "potions.custom-multipliers";
    private static final String T_XP = "xp.custom-multipliers";
    private static final String T_SLOTS = "inventory.slots";
    private static final String T_PREFIX = "tab-integration.rank-prefixes";

    private final OnlineApiPlugin plugin;
    private final String apiKey;

    private String cachedBody = null;
    private long cachedAt = 0;

    public RankedHttpHandler(OnlineApiPlugin plugin, String apiKey) {
        this.plugin = plugin;
        this.apiKey = apiKey;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");

        if (apiKey != null && !apiKey.isEmpty()) {
            String provided = exchange.getRequestHeaders().getFirst("X-Api-Key");
            if (provided == null || !provided.equals(apiKey)) {
                writeJson(exchange, 401, "{\"error\":\"unauthorized\"}");
                return;
            }
        }

        String body;
        try {
            body = body();
        } catch (Throwable t) {
            plugin.getLogger().warning("/api/ranked failed: " + t);
            body = "\"available\":false,\"reason\":\"error\"}";
        }
        writeJson(exchange, 200, "{\"server_time\":" + System.currentTimeMillis() + "," + body);
    }

    private synchronized String body() {
        long ttl = Math.max(1, plugin.getConfig().getLong("ranked.cache-seconds", 10)) * 1000L;
        long now = System.currentTimeMillis();
        if (cachedBody != null && now - cachedAt < ttl) return cachedBody;
        String fresh = build();
        cachedBody = fresh;
        cachedAt = now;
        return fresh;
    }

    // ------------------------------------------------------------------ build

    private String build() {
        String pluginName = plugin.getConfig().getString("ranked.plugin-name", "RankedSMPX");
        Plugin rs = Bukkit.getPluginManager().getPlugin(pluginName);
        if (rs == null || !rs.isEnabled()) {
            return "\"available\":false,\"reason\":\"" + esc(pluginName) + " is not installed or not enabled\"}";
        }

        File folder = rs.getDataFolder();
        FileConfiguration cfg = YamlConfiguration.loadConfiguration(new File(folder, "config.yml"));
        File customFile = new File(folder, "custom.yml");
        FileConfiguration custom = customFile.exists()
                ? YamlConfiguration.loadConfiguration(customFile) : new YamlConfiguration();

        int total = Math.max(1, Math.min(500, cfg.getInt("season.total-ranks", 20)));
        long inactiveMs = Math.max(1L, plugin.getConfig().getLong("ranked.inactive-after-hours", 48)) * 3600_000L;

        // every "rank number -> value" table in the whole config
        Map<String, Map<Integer, Object>> tables = new LinkedHashMap<>();
        collectTables(cfg, "", tables);

        Map<Integer, Object> hearts = tables.getOrDefault(T_HEARTS, new TreeMap<>());
        Map<Integer, Object> potion = tables.getOrDefault(T_POTION, new TreeMap<>());
        Map<Integer, Object> xp = tables.getOrDefault(T_XP, new TreeMap<>());
        Map<Integer, Object> slots = tables.getOrDefault(T_SLOTS, new TreeMap<>());
        Map<Integer, Object> prefixes = tables.getOrDefault(T_PREFIX, new TreeMap<>());

        double maxHearts = cfg.getDouble("health.max-hearts", 20.0);
        double minHearts = cfg.getDouble("health.min-hearts", 10.0);
        double potFirst = cfg.getDouble("potions.rank-1-multiplier", 1.0);
        double potLast = cfg.getDouble("potions.rank-20-multiplier", 1.0);
        double xpFirst = cfg.getDouble("xp.rank-1-multiplier", 1.0);
        double xpLast = cfg.getDouble("xp.rank-20-multiplier", 1.0);

        String defaultSuffix = cfg.getString("tab-integration.default-suffix", "");
        ConfigurationSection suffixSec = cfg.getConfigurationSection("tab-integration.rank-suffixes");

        StringBuilder j = new StringBuilder(8192);
        j.append("\"available\":true");
        j.append(",\"total_ranks\":").append(total);
        j.append(",\"inactive_after_ms\":").append(inactiveMs);

        // season-level switches
        j.append(",\"season\":{")
                .append("\"rank_stealing\":").append(cfg.getBoolean("season.rank-stealing.enabled", false))
                .append(",\"max_rank_difference\":").append(cfg.getInt("season.rank-stealing.max-rank-difference", 0))
                .append(",\"auto_assign\":").append(cfg.getBoolean("auto-assign-ranks.enabled", false))
                .append(",\"rank_transfer\":").append(cfg.getBoolean("rank-transfer.enabled", false))
                .append(",\"rank_drop\":").append(custom.getBoolean("rank-drop.enabled", false))
                .append("}");

        // unranked baseline (key 0 in the tables, or the dedicated unranked-* options)
        j.append(",\"unranked\":{")
                .append("\"hearts\":").append(num(asDouble(hearts.get(0), cfg.getDouble("health.unranked-hearts", 10.0))))
                .append(",\"xp\":").append(num(asDouble(xp.get(0), cfg.getDouble("xp.unranked-multiplier", 1.0))))
                .append(",\"potion\":").append(num(asDouble(potion.get(0), cfg.getDouble("potions.unranked-multiplier", 1.0))))
                .append(",\"prefix\":\"").append(esc(cfg.getString("tab-integration.unranked-prefix", ""))).append("\"")
                .append("}");

        // one entry per rank, 1..total
        j.append(",\"ranks\":[");
        for (int r = 1; r <= total; r++) {
            if (r > 1) j.append(",");
            double frac = total > 1 ? (r - 1) / (double) (total - 1) : 0.0;

            boolean heartsSet = hearts.get(r) != null;
            boolean xpSet = xp.get(r) != null;
            boolean potSet = potion.get(r) != null;
            double h = heartsSet ? asDouble(hearts.get(r), 0) : maxHearts + (minHearts - maxHearts) * frac;
            double x = xpSet ? asDouble(xp.get(r), 1) : xpFirst + (xpLast - xpFirst) * frac;
            double p = potSet ? asDouble(potion.get(r), 1) : potFirst + (potLast - potFirst) * frac;

            String prefix = prefixes.get(r) == null ? "" : String.valueOf(prefixes.get(r));
            String suffix = defaultSuffix == null ? "" : defaultSuffix;
            if (suffixSec != null && suffixSec.getString(String.valueOf(r)) != null) {
                suffix = suffixSec.getString(String.valueOf(r));
            }

            j.append("{\"rank\":").append(r)
                    .append(",\"hearts\":").append(num(h)).append(",\"hearts_est\":").append(!heartsSet)
                    .append(",\"xp\":").append(num(x)).append(",\"xp_est\":").append(!xpSet)
                    .append(",\"potion\":").append(num(p)).append(",\"potion_est\":").append(!potSet);
            if (slots.get(r) != null) j.append(",\"slots\":").append(num(asDouble(slots.get(r), 0)));
            j.append(",\"prefix\":\"").append(esc(prefix)).append("\"")
                    .append(",\"label\":\"").append(esc(plain(prefix))).append("\"")
                    .append(",\"suffix\":\"").append(esc(plain(suffix))).append("\"");
            String color = colorOf(prefix);
            if (color != null) j.append(",\"color\":\"").append(color).append("\"");

            // every other per-rank table in the config
            j.append(",\"perks\":{");
            boolean firstPerk = true;
            for (Map.Entry<String, Map<Integer, Object>> t : tables.entrySet()) {
                String path = t.getKey();
                if (path.equals(T_HEARTS) || path.equals(T_POTION) || path.equals(T_XP)
                        || path.equals(T_SLOTS) || path.equals(T_PREFIX)) continue;
                Object v = t.getValue().get(r);
                if (v == null) continue;
                if (!firstPerk) j.append(",");
                firstPerk = false;
                j.append("\"").append(esc(path)).append("\":").append(jsonValue(v));
            }
            j.append("}}");
        }
        j.append("]");

        // who holds which rank
        j.append(",\"players\":[");
        List<String> rows = readHolders(cfg, folder, total, inactiveMs);
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) j.append(",");
            j.append(rows.get(i));
        }
        j.append("]}");
        return j.toString();
    }

    // ------------------------------------------------------------------ database

    private List<String> readHolders(FileConfiguration cfg, File folder, int total, long inactiveMs) {
        List<String> out = new ArrayList<>();
        String type = cfg.getString("database.type", "sqlite").toLowerCase();
        String sql = "SELECT uuid, player_name, rank, last_updated FROM ranks ORDER BY rank ASC";
        try {
            Connection conn;
            if (type.equals("mysql")) {
                String url = "jdbc:mysql://" + cfg.getString("database.mysql.host", "localhost") + ":"
                        + cfg.getInt("database.mysql.port", 3306) + "/"
                        + cfg.getString("database.mysql.database", "rankedsmp")
                        + "?useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=3000&socketTimeout=5000";
                try { Class.forName("com.mysql.cj.jdbc.Driver"); } catch (Throwable ignored) { /* DriverManager will find it */ }
                conn = DriverManager.getConnection(url,
                        cfg.getString("database.mysql.username", "root"),
                        cfg.getString("database.mysql.password", ""));
            } else {
                File db = new File(folder, cfg.getString("database.sqlite-file", "rankedsmp.db"));
                if (!db.exists()) return out;
                try { Class.forName("org.sqlite.JDBC"); } catch (Throwable ignored) { /* DriverManager will find it */ }
                conn = DriverManager.getConnection("jdbc:sqlite:" + db.getAbsolutePath());
            }
            try (Connection c = conn;
                 PreparedStatement ps = c.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                long now = System.currentTimeMillis();
                while (rs.next()) {
                    int rank = rs.getInt("rank");
                    if (rank < 1 || rank > total) continue; // unranked / stale rows
                    String uuidStr = rs.getString("uuid");
                    String name = rs.getString("player_name");
                    long updated = rs.getLong("last_updated");

                    boolean online = false;
                    long lastSeen = 0;
                    try {
                        OfflinePlayer op = Bukkit.getOfflinePlayer(UUID.fromString(uuidStr));
                        online = op.isOnline();
                        lastSeen = online ? now : op.getLastPlayed();
                        if (op.getName() != null) name = op.getName();
                    } catch (Throwable ignored) { /* keep DB name, unknown last seen */ }

                    out.add("{\"rank\":" + rank
                            + ",\"name\":\"" + esc(name) + "\""
                            + ",\"uuid\":\"" + esc(uuidStr) + "\""
                            + ",\"online\":" + online
                            + ",\"last_seen\":" + lastSeen
                            + ",\"rank_since\":" + updated + "}");
                }
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("/api/ranked could not read the RankedSMPX database: " + t);
        }
        return out;
    }

    // ------------------------------------------------------------------ config scanning

    /** Finds every config section whose keys are all whole numbers (a "per-rank" table). */
    private void collectTables(ConfigurationSection sec, String path, Map<String, Map<Integer, Object>> out) {
        for (String key : sec.getKeys(false)) {
            Object child = sec.get(key);
            if (!(child instanceof ConfigurationSection)) continue;
            ConfigurationSection cs = (ConfigurationSection) child;
            String full = path.isEmpty() ? key : path + "." + key;
            Map<Integer, Object> table = asRankTable(cs);
            if (table != null) out.put(full, table);
            else collectTables(cs, full, out);
        }
    }

    private Map<Integer, Object> asRankTable(ConfigurationSection cs) {
        java.util.Set<String> keys = cs.getKeys(false);
        if (keys.isEmpty()) return null;
        Map<Integer, Object> t = new TreeMap<>();
        for (String k : keys) {
            int n;
            try { n = Integer.parseInt(k.trim()); } catch (NumberFormatException e) { return null; }
            Object v = cs.get(k);
            if (v instanceof ConfigurationSection || v instanceof List) return null;
            if (v != null) t.put(n, v);
        }
        return t;
    }

    // ------------------------------------------------------------------ small helpers

    private static double asDouble(Object o, double def) {
        if (o instanceof Number) return ((Number) o).doubleValue();
        if (o != null) {
            try { return Double.parseDouble(String.valueOf(o).trim()); } catch (NumberFormatException ignored) { /* fall through */ }
        }
        return def;
    }

    private static String num(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) return "0";
        return String.valueOf(Math.round(d * 1000.0) / 1000.0);
    }

    private static String jsonValue(Object v) {
        if (v instanceof Number) return num(((Number) v).doubleValue());
        if (v instanceof Boolean) return v.toString();
        return "\"" + esc(String.valueOf(v)) + "\"";
    }

    /** First colour found in a legacy / hex / MiniMessage prefix, as #RRGGBB, or null. */
    private static String colorOf(String s) {
        if (s == null) return null;
        Matcher h = HEX.matcher(s);
        Matcher l = LEGACY.matcher(s);
        int hi = h.find() ? h.start() : Integer.MAX_VALUE;
        int li = l.find() ? l.start() : Integer.MAX_VALUE;
        if (hi == Integer.MAX_VALUE && li == Integer.MAX_VALUE) return null;
        if (hi <= li) return "#" + h.group(1).toUpperCase();
        return LEGACY_COLORS[Character.digit(l.group(1).charAt(0), 16)];
    }

    /** Strips colour codes / MiniMessage tags so only the readable text is left, e.g. "[#1]". */
    private static String plain(String s) {
        if (s == null) return "";
        String r = STRIP_HEX.matcher(s).replaceAll("");
        r = STRIP_LEGACY.matcher(r).replaceAll("");
        r = STRIP_TAGS.matcher(r).replaceAll("");
        return r.trim();
    }

    private static String esc(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': b.append("\\\\"); break;
                case '"': b.append("\\\""); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        return b.toString();
    }

    private void writeJson(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
