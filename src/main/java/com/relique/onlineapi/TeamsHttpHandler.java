package com.relique.onlineapi;

import com.relique.onlineapi.stats.StatsManager;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * GET /api/teams   (team top)
 *   ?sort=score|kills|deaths|kd|playtime|members   (default: score)
 *   ?order=desc|asc                                (default: desc)
 *   ?limit=N                                       (default: all)
 *
 * Reads live team data straight from the "Teams" plugin (package teams.*) on this server, using
 * reflection instead of a compile-time dependency. If Teams is missing, or its API ever changes
 * shape, this endpoint answers {"available":false,"teams":[]} instead of breaking anything.
 *
 * Every team carries everything Teams' own /team top uses to measure a team: score, kills,
 * deaths, K/D and playtime (plus level and member count).
 */
public class TeamsHttpHandler implements HttpHandler {

    private static final String PLUGIN_NAME = "Teams";
    private static final String EMPTY = "{\"available\":false,\"teams\":[]}";

    private final StatsManager stats;
    private final String apiKey;

    public TeamsHttpHandler(StatsManager stats, String apiKey) {
        this.stats = stats;
        this.apiKey = apiKey;
    }

    /** One team's data, read once so sorting never calls into Teams again. */
    private static final class Row {
        String name;
        String tag;
        int limit;
        int level;
        int score;
        long kills;
        long deaths;
        double kd;
        long playtime;
        List<String> members = new ArrayList<>();
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

        Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
        String sort = query.getOrDefault("sort", "score").toLowerCase(Locale.ROOT);
        boolean asc = "asc".equalsIgnoreCase(query.getOrDefault("order", "desc"));
        int limit = -1;
        try {
            if (query.containsKey("limit")) limit = Integer.parseInt(query.get("limit"));
        } catch (NumberFormatException ignored) { /* fall back to no limit */ }

        String json;
        try {
            json = buildTeamsJson(sort, asc, limit);
        } catch (Throwable t) {
            json = EMPTY;
        }
        writeJson(exchange, 200, json);
    }

    private String buildTeamsJson(String sort, boolean asc, int limit) {
        Plugin teams = Bukkit.getPluginManager().getPlugin(PLUGIN_NAME);
        if (teams == null || !teams.isEnabled()) return EMPTY;

        try {
            ClassLoader cl = teams.getClass().getClassLoader();
            Class<?> teamClass = Class.forName("teams.Team", true, cl);
            Class<?> statsClass = Class.forName("teams.stats.TeamStats", true, cl);

            // Teams exposes its manager as a STATIC on Team, not on the plugin instance.
            Object teamManager = teamClass.getMethod("getTeamManager").invoke(null);
            if (teamManager == null) {
                logOnce("Team.getTeamManager() returned null (Teams not finished loading?)");
                return EMPTY;
            }
            Class<?> managerType = Class.forName("teams.team.TeamManager", true, cl);
            Object rawTeams = managerType.getMethod("getLoadedTeamListClone").invoke(teamManager);
            Iterable<?> teamIterable = toIterable(rawTeams);
            if (teamIterable == null) {
                logOnce("getLoadedTeamListClone returned an unsupported type: "
                        + (rawTeams == null ? "null" : rawTeams.getClass().getName()));
                return EMPTY;
            }

            // Teams unloads a team from memory when none of its members is online, so
            // getLoadedTeamListClone() alone makes fully-offline teams vanish. Collect the loaded
            // teams first, then pull in every other team through its (offline) members.
            Map<String, Object> all = new LinkedHashMap<>();
            for (Object team : teamIterable) addTeam(all, team);
            collectOfflineTeams(all, teamClass);

            List<Row> rows = new ArrayList<>();
            for (Object team : all.values()) {
                Row r = readTeam(team, teamClass, statsClass);
                if (r != null) rows.add(r);
            }

            // Default order matches Teams' own team top: score first, then kills as tie-breaker.
            Comparator<Row> cmp;
            switch (sort) {
                case "kills":    cmp = Comparator.comparingLong((Row r) -> r.kills); break;
                case "deaths":   cmp = Comparator.comparingLong((Row r) -> r.deaths); break;
                case "kd":
                case "kdr":      cmp = Comparator.comparingDouble((Row r) -> r.kd); break;
                case "playtime": cmp = Comparator.comparingLong((Row r) -> r.playtime); break;
                case "members":  cmp = Comparator.comparingInt((Row r) -> r.members.size()); break;
                case "score":
                default:         cmp = Comparator.comparingInt((Row r) -> r.score)
                                         .thenComparingLong(r -> r.kills); break;
            }
            rows.sort((asc ? cmp : cmp.reversed()).thenComparing(r -> r.name, String.CASE_INSENSITIVE_ORDER));
            if (limit >= 0 && limit < rows.size()) rows = rows.subList(0, limit);

            StringBuilder json = new StringBuilder();
            json.append("{\"available\":true,\"sort\":\"").append(escape(sort)).append("\",\"teams\":[");
            int position = 0;
            for (Row r : rows) {
                if (position > 0) json.append(",");
                position++;
                json.append("{")
                        .append("\"position\":").append(position).append(",")
                        .append("\"name\":\"").append(escape(r.name)).append("\",")
                        .append("\"tag\":").append(r.tag == null ? "null" : "\"" + escape(r.tag) + "\"").append(",")
                        .append("\"level\":").append(r.level).append(",")
                        .append("\"limit\":").append(r.limit).append(",")
                        .append("\"score\":").append(r.score).append(",")
                        .append("\"kills\":").append(r.kills).append(",")
                        .append("\"deaths\":").append(r.deaths).append(",")
                        .append("\"kd\":").append(String.format(Locale.ROOT, "%.2f", r.kd)).append(",")
                        .append("\"playtime_seconds\":").append(r.playtime).append(",")
                        .append("\"member_count\":").append(r.members.size()).append(",")
                        .append("\"members\":[");
                for (int i = 0; i < r.members.size(); i++) {
                    if (i > 0) json.append(",");
                    json.append(r.members.get(i));
                }
                json.append("]}");
            }
            json.append("]}");
            return json.toString();
        } catch (Throwable t) {
            logOnce("Could not read Teams: " + t);
            return EMPTY;
        }
    }

    private void addTeam(Map<String, Object> map, Object team) {
        if (team == null) return;
        try {
            String name = String.valueOf(team.getClass().getMethod("getName").invoke(team));
            map.putIfAbsent(name.toLowerCase(Locale.ROOT), team);
        } catch (Throwable ignored) { }
    }

    private long lastScan = 0;
    private Map<String, Object> scanCache = new LinkedHashMap<>();

    /** Finds teams that are currently unloaded by asking Teams for each known player's team. */
    private void collectOfflineTeams(Map<String, Object> out, Class<?> teamClass) {
        long now = System.currentTimeMillis();
        if (now - lastScan > 30_000) {
            Map<String, Object> found = new LinkedHashMap<>();
            try {
                Method getTeam = teamClass.getMethod("getTeam", OfflinePlayer.class);
                for (StatsManager.PlayerStats ps : stats.all()) {
                    try {
                        Object t = getTeam.invoke(null, Bukkit.getOfflinePlayer(ps.uuid));
                        addTeam(found, t);
                    } catch (Throwable ignored) { }
                }
            } catch (Throwable t) {
                logOnce("Could not look up offline teams: " + t);
            }
            scanCache = found;
            lastScan = now;
        }
        for (Map.Entry<String, Object> e : scanCache.entrySet()) out.putIfAbsent(e.getKey(), e.getValue());
    }

    private boolean logged = false;

    private void logOnce(String msg) {
        if (logged) return;
        logged = true;
        Bukkit.getLogger().warning("[ReliqueOnlineAPI] /api/teams: " + msg);
    }

    /** Teams returns Maps (loaded teams) and its own set components (members); handle all of them. */
    private Iterable<?> toIterable(Object raw) {
        if (raw == null) return null;
        if (raw instanceof Map) return ((Map<?, ?>) raw).values();
        if (raw instanceof Iterable) return (Iterable<?>) raw;
        for (String m : new String[]{"get", "getClone", "getMembersClone", "getAll"}) {
            try {
                Object r = raw.getClass().getMethod(m).invoke(raw);
                if (r instanceof Map) return ((Map<?, ?>) r).values();
                if (r instanceof Iterable) return (Iterable<?>) r;
            } catch (Throwable ignored) { }
        }
        return null;
    }

    /** Reads one team's name, score and stats. Returns null if this particular team couldn't be read. */
    private Row readTeam(Object team, Class<?> teamClass, Class<?> statsClass) {
        try {
            Row r = new Row();
            r.name = String.valueOf(teamClass.getMethod("getName").invoke(team));

            String tag = safeString(teamClass, team, "getOriginalTag");
            r.tag = (tag == null || tag.isEmpty()) ? null : tag;

            r.score = (int) numberOr(teamClass, team, "getScore", 0);
            r.level = (int) numberOr(teamClass, team, "getLevel", 0);
            r.limit = (int) numberOr(teamClass, team, "getTeamLimit", 0);

            // The same five things Teams' team top measures a team by (score is above).
            r.kills = statLong(statsClass, teamClass, "getKills", team);
            r.deaths = statLong(statsClass, teamClass, "getDeaths", team);
            r.playtime = statLong(statsClass, teamClass, "getPlaytimeSeconds", team);
            r.kd = statDouble(statsClass, teamClass, "getKdr", team, r.kills, r.deaths);

            Iterable<?> memberIterable = toIterable(teamClass.getMethod("getMembers").invoke(team));
            if (memberIterable != null) {
                for (Object tp : memberIterable) {
                    String memberJson = memberToJson(tp);
                    if (memberJson != null) r.members.add(memberJson);
                }
            }
            return r;
        } catch (Throwable t) {
            logOnce("Could not read a team: " + t);
            return null;
        }
    }

    private long statLong(Class<?> statsClass, Class<?> teamClass, String method, Object team) {
        try {
            Method m = statsClass.getMethod(method, teamClass);
            Object v = Modifier.isStatic(m.getModifiers()) ? m.invoke(null, team) : null;
            return v instanceof Number ? ((Number) v).longValue() : 0L;
        } catch (Throwable t) {
            return 0L;
        }
    }

    private double statDouble(Class<?> statsClass, Class<?> teamClass, String method, Object team,
                              long kills, long deaths) {
        try {
            Method m = statsClass.getMethod(method, teamClass);
            if (Modifier.isStatic(m.getModifiers())) {
                Object v = m.invoke(null, team);
                if (v instanceof Number) return ((Number) v).doubleValue();
            }
        } catch (Throwable ignored) { }
        return deaths == 0 ? (double) kills : (double) kills / deaths; // same rule as player K/D
    }

    private long numberOr(Class<?> cls, Object obj, String method, long fallback) {
        try {
            Object v = cls.getMethod(method).invoke(obj);
            return v instanceof Number ? ((Number) v).longValue() : fallback;
        } catch (Throwable t) {
            return fallback;
        }
    }

    private String memberToJson(Object teamPlayer) {
        try {
            Class<?> tpClass = teamPlayer.getClass();
            UUID uuid = (UUID) tpClass.getMethod("getPlayerUUID").invoke(teamPlayer);
            Object rankObj = tpClass.getMethod("getRank").invoke(teamPlayer);
            boolean online = Bukkit.getPlayer(uuid) != null;
            String rank = rankObj == null ? "DEFAULT" : rankObj.toString();
            String pname = playerName(uuid);

            return "{"
                    + "\"name\":\"" + escape(pname) + "\","
                    + "\"uuid\":\"" + uuid + "\","
                    + "\"rank\":\"" + escape(rank) + "\","
                    + "\"online\":" + online
                    + "}";
        } catch (Throwable t) {
            return null;
        }
    }

    /** Prefers the name our own stats already track (kept fresh across name changes). */
    private String playerName(UUID uuid) {
        StatsManager.PlayerStats s = stats.find(uuid);
        if (s != null && s.name != null) return s.name;
        OfflinePlayer op = Bukkit.getOfflinePlayer(uuid);
        return op.getName() != null ? op.getName() : uuid.toString().substring(0, 8);
    }

    private String safeString(Class<?> cls, Object obj, String method) {
        try {
            Object v = cls.getMethod(method).invoke(obj);
            return v == null ? null : v.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private Map<String, String> parseQuery(String raw) {
        Map<String, String> map = new HashMap<>();
        if (raw == null || raw.isEmpty()) return map;
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            try {
                if (eq < 0) map.put(URLDecoder.decode(pair, StandardCharsets.UTF_8), "");
                else map.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            } catch (IllegalArgumentException ignored) { }
        }
        return map;
    }

    private void writeJson(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
