package com.relique.onlineapi;

import com.relique.onlineapi.stats.StatsManager;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * GET /api/teams
 *
 * Reads live team data straight from the BetterTeams plugin (com.booksaw.betterTeams)
 * on this server, using reflection instead of a compile-time dependency. That way this
 * plugin builds and runs the same whether or not BetterTeams is installed - if it's
 * missing, or its API ever changes shape, this endpoint just answers
 * {"available":false,"teams":[]} instead of breaking anything.
 */
public class TeamsHttpHandler implements HttpHandler {

    private final StatsManager stats;
    private final String apiKey;

    public TeamsHttpHandler(StatsManager stats, String apiKey) {
        this.stats = stats;
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

        String json;
        try {
            json = buildTeamsJson();
        } catch (Throwable t) {
            json = "{\"available\":false,\"teams\":[]}";
        }
        writeJson(exchange, 200, json);
    }

    private String buildTeamsJson() {
        Plugin bt = Bukkit.getPluginManager().getPlugin("BetterTeams");
        if (bt == null || !bt.isEnabled()) return "{\"available\":false,\"teams\":[]}";

        try {
            ClassLoader cl = bt.getClass().getClassLoader();
            // BetterTeams exposes its manager as a STATIC on Team, not on the plugin instance.
            Class<?> teamClass = Class.forName("com.booksaw.betterTeams.Team", true, cl);
            Object teamManager = teamClass.getMethod("getTeamManager").invoke(null);
            if (teamManager == null) {
                logOnce("Team.getTeamManager() returned null (BetterTeams not finished loading?)");
                return "{\"available\":false,\"teams\":[]}";
            }
            // Look the method up on the public TeamManager type, not the (maybe non-public) implementation.
            Class<?> managerType = Class.forName("com.booksaw.betterTeams.team.TeamManager", true, cl);
            Object rawTeams = managerType.getMethod("getLoadedTeamListClone").invoke(teamManager);
            Iterable<?> teamIterable = toIterable(rawTeams);
            if (teamIterable == null) {
                logOnce("getLoadedTeamListClone returned an unsupported type: "
                        + (rawTeams == null ? "null" : rawTeams.getClass().getName()));
                return "{\"available\":false,\"teams\":[]}";
            }

            // BetterTeams unloads a team from memory when none of its members is online, so
            // getLoadedTeamListClone() alone makes fully-offline teams vanish. Collect the loaded
            // teams first, then pull in every other team through its (offline) members.
            java.util.Map<String, Object> all = new java.util.LinkedHashMap<>();
            for (Object team : teamIterable) addTeam(all, team);
            collectOfflineTeams(all, teamClass, cl);

            int defaultLimit = configuredLimit(bt);
            StringBuilder json = new StringBuilder();
            json.append("{\"available\":true,\"teams\":[");
            boolean firstTeam = true;
            for (Object team : all.values()) {
                String teamJson = teamToJson(team, defaultLimit);
                if (teamJson == null) continue;
                if (!firstTeam) json.append(",");
                firstTeam = false;
                json.append(teamJson);
            }
            json.append("]}");
            return json.toString();
        } catch (Throwable t) {
            logOnce("Could not read BetterTeams: " + t);
            return "{\"available\":false,\"teams\":[]}";
        }
    }

    private void addTeam(java.util.Map<String, Object> map, Object team) {
        if (team == null) return;
        try {
            String name = String.valueOf(team.getClass().getMethod("getName").invoke(team));
            map.putIfAbsent(name.toLowerCase(java.util.Locale.ROOT), team);
        } catch (Throwable ignored) { }
    }

    private long lastScan = 0;
    private java.util.Map<String, Object> scanCache = new java.util.LinkedHashMap<>();

    /** Finds teams that are currently unloaded by asking BetterTeams for each known player's team. */
    private void collectOfflineTeams(java.util.Map<String, Object> out, Class<?> teamClass, ClassLoader cl) {
        long now = System.currentTimeMillis();
        if (now - lastScan > 30_000) {
            java.util.Map<String, Object> found = new java.util.LinkedHashMap<>();
            try {
                java.lang.reflect.Method getTeam = teamClass.getMethod("getTeam", OfflinePlayer.class);
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
        for (java.util.Map.Entry<String, Object> e : scanCache.entrySet()) out.putIfAbsent(e.getKey(), e.getValue());
    }

    /** The team size limit from BetterTeams' config.yml (0 = unknown / unlimited). */
    private int configuredLimit(Plugin bt) {
        try {
            for (String key : new String[]{"maxTeamSize", "teamLimit", "max-team-size"}) {
                int v = bt.getConfig().getInt(key, 0);
                if (v > 0) return v;
            }
        } catch (Throwable ignored) { }
        return 0;
    }

    private boolean logged = false;

    private void logOnce(String msg) {
        if (logged) return;
        logged = true;
        Bukkit.getLogger().warning("[ReliqueOnlineAPI] /api/teams: " + msg);
    }

    /**
     * BetterTeams returns different container types across versions (Map<UUID, Team> in
     * current releases, Collections in older ones, custom Iterable sets for members).
     */
    private Iterable<?> toIterable(Object raw) {
        if (raw == null) return null;
        if (raw instanceof java.util.Map) return ((java.util.Map<?, ?>) raw).values();
        if (raw instanceof Iterable) return (Iterable<?>) raw;
        for (String m : new String[]{"getClone", "getMembersClone", "getAll"}) {
            try {
                Object r = raw.getClass().getMethod(m).invoke(raw);
                if (r instanceof java.util.Map) return ((java.util.Map<?, ?>) r).values();
                if (r instanceof Iterable) return (Iterable<?>) r;
            } catch (Throwable ignored) { }
        }
        return null;
    }

    /** Builds one team's JSON, or null if this particular team couldn't be read. */
    private String teamToJson(Object team, int defaultLimit) {
        try {
            Class<?> teamClass = team.getClass();
            String name = String.valueOf(teamClass.getMethod("getName").invoke(team));
            String tag = safeString(teamClass, team, "getOriginalTag");
            if (tag != null && tag.isEmpty()) tag = null;

            int limit = defaultLimit;
            for (String m : new String[]{"getTeamLimit", "getMaxTeamSize", "getLimit"}) {
                try {
                    Object v = teamClass.getMethod(m).invoke(team);
                    if (v instanceof Number && ((Number) v).intValue() > 0) { limit = ((Number) v).intValue(); break; }
                } catch (Throwable ignored) { }
            }

            StringBuilder json = new StringBuilder();
            json.append("{")
                    .append("\"name\":\"").append(escape(name)).append("\",")
                    .append("\"limit\":").append(limit).append(",")
                    .append("\"tag\":").append(tag == null ? "null" : "\"" + escape(tag) + "\"").append(",")
                    .append("\"members\":[");

            Object rawMembers = teamClass.getMethod("getMembers").invoke(team);
            boolean firstMember = true;
            Iterable<?> memberIterable = toIterable(rawMembers);
            if (memberIterable != null) {
                for (Object tp : memberIterable) {
                    String memberJson = memberToJson(tp);
                    if (memberJson == null) continue;
                    if (!firstMember) json.append(",");
                    firstMember = false;
                    json.append(memberJson);
                }
            }
            json.append("]}");
            return json.toString();
        } catch (Throwable t) {
            logOnce("Could not read a team: " + t);
            return null;
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
