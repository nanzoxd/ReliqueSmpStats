package com.relique.onlineapi;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * GET /api/health?hours=1|6|24|48&points=240
 * Server health history for the website (TPS, MSPT, RAM, CPU, players, restarts, downtime).
 */
public class HealthHttpHandler implements HttpHandler {

    private final HealthManager health;
    private final String apiKey;

    public HealthHttpHandler(HealthManager health, String apiKey) {
        this.health = health;
        this.apiKey = apiKey;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().add("Cache-Control", "no-store");

        if (apiKey != null && !apiKey.isEmpty()) {
            String provided = exchange.getRequestHeaders().getFirst("X-Api-Key");
            if (provided == null || !provided.equals(apiKey)) {
                write(exchange, 401, "{\"error\":\"unauthorized\"}");
                return;
            }
        }
        if (!health.isEnabled()) {
            write(exchange, 200, "{\"available\":false}");
            return;
        }

        Map<String, String> q = new HashMap<>();
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw != null) {
            for (String pair : raw.split("&")) {
                int eq = pair.indexOf('=');
                try {
                    if (eq > 0) q.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                            URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
                } catch (IllegalArgumentException ignored) { }
            }
        }
        int hours = parse(q.get("hours"), 1);
        int points = parse(q.get("points"), 240);

        String json;
        try {
            json = health.toJson(hours, points);
        } catch (Throwable t) {
            json = "{\"available\":false}";
        }
        write(exchange, 200, json);
    }

    private static int parse(String v, int fallback) {
        try {
            return v == null ? fallback : Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private void write(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
