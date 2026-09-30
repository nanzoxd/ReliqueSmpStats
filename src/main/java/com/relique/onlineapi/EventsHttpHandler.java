package com.relique.onlineapi;

import com.relique.onlineapi.stats.StatsManager;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * GET /api/events?limit=N   (default 50, max 100)
 *
 * The server-wide recent events feed: PvP kills and other deaths (with the reason).
 * Newest first.
 */
public class EventsHttpHandler implements HttpHandler {

    private final StatsManager stats;
    private final String apiKey;

    public EventsHttpHandler(StatsManager stats, String apiKey) {
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

        int limit = 50;
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw != null) {
            for (String pair : raw.split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0 && "limit".equals(pair.substring(0, eq))) {
                    try {
                        limit = Integer.parseInt(URLDecoder.decode(pair.substring(eq + 1), "UTF-8"));
                    } catch (Exception ignored) { /* keep default */ }
                }
            }
        }
        limit = Math.max(1, Math.min(limit, 100));

        List<StatsManager.Event> list = stats.recentEvents(limit);
        StringBuilder json = new StringBuilder("{\"events\":[");
        boolean first = true;
        for (StatsManager.Event ev : list) {
            if (!first) json.append(",");
            first = false;
            json.append("{")
                    .append("\"time\":").append(ev.time).append(",")
                    .append("\"type\":\"").append(escape(ev.type)).append("\",")
                    .append("\"victim\":\"").append(escape(ev.victim)).append("\",")
                    .append("\"victim_uuid\":").append(str(ev.victimUuid == null ? null : ev.victimUuid.toString())).append(",")
                    .append("\"killer\":").append(str(ev.killer)).append(",")
                    .append("\"killer_uuid\":").append(str(ev.killerUuid == null ? null : ev.killerUuid.toString())).append(",")
                    .append("\"cause\":\"").append(escape(ev.cause)).append("\",")
                    .append("\"killer_elo\":").append(ev.killerElo).append(",")
                    .append("\"victim_elo\":").append(ev.victimElo)
                    .append("}");
        }
        json.append("]}");
        writeJson(exchange, 200, json.toString());
    }

    private String str(String s) {
        return s == null ? "null" : "\"" + escape(s) + "\"";
    }

    private void writeJson(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
