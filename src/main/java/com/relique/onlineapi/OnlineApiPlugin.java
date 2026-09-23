package com.relique.onlineapi;

import com.relique.onlineapi.stats.StatsManager;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collection;

public class OnlineApiPlugin extends JavaPlugin {

    private HttpServer server;
    private StatsManager statsManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        statsManager = new StatsManager(this);
        getServer().getPluginManager().registerEvents(new StatsListener(statsManager), this);

        // Anyone already online when /reload happened should get a session start too.
        for (Player p : Bukkit.getOnlinePlayers()) {
            statsManager.onJoin(p.getUniqueId(), p.getName());
        }

        int port = getConfig().getInt("port", 8123);
        String apiKey = getConfig().getString("api-key", "");

        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);
            server.createContext("/api/online", new OnlineHandler(apiKey));
            server.createContext("/api/stats", new StatsHttpHandler(statsManager, apiKey));
            server.setExecutor(null); // small endpoints, default executor is plenty
            server.start();
            getLogger().info("API listening on port " + port
                    + " (endpoints: /api/online, /api/stats)");
        } catch (IOException e) {
            getLogger().severe("Could not start API HTTP server: " + e.getMessage());
        }

        long autosaveTicks = getConfig().getLong("autosave-interval-minutes", 5) * 60L * 20L;
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            statsManager.flushSessions();
            statsManager.save();
        }, autosaveTicks, autosaveTicks);
    }

    @Override
    public void onDisable() {
        if (server != null) {
            server.stop(0);
        }
        if (statsManager != null) {
            statsManager.flushSessions();
            statsManager.save();
        }
    }

    private class OnlineHandler implements HttpHandler {
        private final String apiKey;

        OnlineHandler(String apiKey) {
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

            Collection<? extends Player> online = Bukkit.getOnlinePlayers();

            StringBuilder json = new StringBuilder();
            json.append("{\"online\":").append(online.size())
                    .append(",\"max\":").append(Bukkit.getMaxPlayers())
                    .append(",\"players\":[");
            boolean first = true;
            for (Player p : online) {
                if (!first) json.append(",");
                first = false;
                json.append("{\"name\":\"").append(escape(p.getName()))
                        .append("\",\"uuid\":\"").append(p.getUniqueId())
                        .append("\"}");
            }
            json.append("]}");

            writeJson(exchange, 200, json.toString());
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
}
