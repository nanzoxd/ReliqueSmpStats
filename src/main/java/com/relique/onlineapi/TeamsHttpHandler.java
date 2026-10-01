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
import java.util.Collection;
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
            Class<?> mainClass = Class.forName("com.booksaw.betterTeams.Main");
            Object teamManager = mainClass.getMethod("getTeamManager").invoke(bt);
            Object rawTeams = teamManager.getClass().getMethod("getLoadedTeamListClone").invoke(teamManager);
            if (!(rawTeams instanceof Collection)) return "{\"available\":false,\"teams\":[]}";

            StringBuilder json = new StringBuilder();
            json.append("{\"available\":true,\"teams\":[");
            boolean firstTeam = true;
            for (Object team : (Collection<?>) rawTeams) {
                String teamJson = teamToJson(team);
                if (teamJson == null) continue;
                if (!firstTeam) json.append(",");
                firstTeam = false;
                json.append(teamJson);
            }
            json.append("]}");
            return json.toString();
        } catch (Throwable t) {
            return "{\"available\":false,\"teams\":[]}";
        }
    }

    /** Builds one team's JSON, or null if this particular team couldn't be read. */
    private String teamToJson(Object team) {
        try {
            Class<?> teamClass = team.getClass();
            String name = String.valueOf(teamClass.getMethod("getName").invoke(team));
            String tag = safeString(teamClass, team, "getTag");

            StringBuilder json = new StringBuilder();
            json.append("{")
                    .append("\"name\":\"").append(escape(name)).append("\",")
                    .append("\"tag\":").append(tag == null ? "null" : "\"" + escape(tag) + "\"").append(",")
                    .append("\"members\":[");

            Object rawMembers = teamClass.getMethod("getMembers").invoke(team);
            boolean firstMember = true;
            if (rawMembers instanceof Collection) {
                for (Object tp : (Collection<?>) rawMembers) {
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
            return null;
        }
    }

    private String memberToJson(Object teamPlayer) {
        try {
            Class<?> tpClass = teamPlayer.getClass();
            UUID uuid = (UUID) tpClass.getMethod("getPlayerUUID").invoke(teamPlayer);
            Object rankObj = tpClass.getMethod("getRank").invoke(teamPlayer);
            boolean online;
            try {
                online = (boolean) tpClass.getMethod("isOnline").invoke(teamPlayer);
            } catch (Throwable t) {
                online = false;
            }
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
