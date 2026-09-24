package com.relique.onlineapi;

import com.relique.onlineapi.stats.StatsManager;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * GET /api/stats
 *   ?sort=elo|kills|deaths|kd|playtime|blocks (default: elo)
 *   ?order=desc|asc                      (default: desc)
 *   ?limit=N                             (default: all)
 *
 * Returns every known player (online or not) so this can back a leaderboard.
 */
public class StatsHttpHandler implements HttpHandler {

    private final StatsManager stats;
    private final String apiKey;

    public StatsHttpHandler(StatsManager stats, String apiKey) {
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

        Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
        String sort = query.getOrDefault("sort", "elo");
        boolean asc = "asc".equalsIgnoreCase(query.getOrDefault("order", "desc"));
        int limit = -1;
        try {
            if (query.containsKey("limit")) limit = Integer.parseInt(query.get("limit"));
        } catch (NumberFormatException ignored) { /* fall back to no limit */ }

        List<StatsManager.PlayerStats> list = new ArrayList<>(stats.all());

        Comparator<StatsManager.PlayerStats> cmp;
        switch (sort) {
            case "kills":    cmp = Comparator.comparingInt(s -> s.kills); break;
            case "deaths":   cmp = Comparator.comparingInt(s -> s.deaths); break;
            case "kd":       cmp = Comparator.comparingDouble(StatsManager.PlayerStats::kd); break;
            case "playtime": cmp = Comparator.comparingLong(s -> s.playtimeSeconds); break;
            case "blocks":   cmp = Comparator.comparingInt(s -> s.blocksMined); break;
            case "elo":
            default:         cmp = Comparator.comparingDouble(s -> s.elo); break;
        }
        list.sort(asc ? cmp : cmp.reversed());

        if (limit >= 0 && limit < list.size()) {
            list = list.subList(0, limit);
        }

        StringBuilder json = new StringBuilder();
        json.append("{\"players\":[");
        boolean first = true;
        for (StatsManager.PlayerStats s : list) {
            if (!first) json.append(",");
            first = false;
            json.append("{")
                    .append("\"name\":\"").append(escape(s.name)).append("\",")
                    .append("\"uuid\":\"").append(s.uuid).append("\",")
                    .append("\"elo\":").append(Math.round(s.elo)).append(",")
                    .append("\"rank\":\"").append(escape(stats.rankOf(s))).append("\",")
                    .append("\"kills\":").append(s.kills).append(",")
                    .append("\"deaths\":").append(s.deaths).append(",")
                    .append("\"kd\":").append(String.format("%.2f", s.kd())).append(",")
                    .append("\"blocks_mined\":").append(s.blocksMined).append(",")
                    .append("\"playtime_seconds\":").append(currentPlaytime(s))
                    .append("}");
        }
        json.append("]}");

        writeJson(exchange, 200, json.toString());
    }

    private long currentPlaytime(StatsManager.PlayerStats s) {
        long extra = s.sessionStart == 0 ? 0 : (System.currentTimeMillis() - s.sessionStart) / 1000L;
        return s.playtimeSeconds + extra;
    }

    private Map<String, String> parseQuery(String raw) {
        Map<String, String> map = new HashMap<>();
        if (raw == null || raw.isEmpty()) return map;
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            try {
                if (eq >= 0) {
                    String k = URLDecoder.decode(pair.substring(0, eq), "UTF-8");
                    String v = URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
                    map.put(k, v);
                } else if (!pair.isEmpty()) {
                    map.put(URLDecoder.decode(pair, "UTF-8"), "");
                }
            } catch (Exception ignored) { /* skip malformed pair */ }
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
