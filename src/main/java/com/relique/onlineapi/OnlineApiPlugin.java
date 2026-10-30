package com.relique.onlineapi;

import com.relique.onlineapi.stats.StatsManager;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collection;

public class OnlineApiPlugin extends JavaPlugin {

    private HttpServer server;
    private StatsManager statsManager;
    private BukkitTask autosaveTask;
    private HealthManager health;
    private RecipesService recipes;
    private com.relique.onlineapi.dialog.RankedLookup rankedLookup; // null when ranked.enabled is false

    /** RankedSMPX rank cache, or null when ranked.enabled is false. */
    public com.relique.onlineapi.dialog.RankedLookup getRankedLookup() { return rankedLookup; }

    /** Used by other plugins (EventCore) to read/set the Elo gain multiplier. */
    public StatsManager getStatsManager() { return statsManager; }

    @Override
    public void onEnable() {
        saveDefaultConfig();

        statsManager = new StatsManager(this);
        if (getCommand("stats") != null) {
            ResetCommand cmd = new ResetCommand(statsManager);
            getCommand("stats").setExecutor(cmd);
            getCommand("stats").setTabCompleter(cmd);
        }
        getServer().getPluginManager().registerEvents(new StatsListener(statsManager, this), this);
        health = new HealthManager(this);
        getServer().getPluginManager().registerEvents(health, this);
        health.start();
        recipes = new RecipesService(this);

        startRanked();

        ReloadCommand reload = new ReloadCommand(this);
        if (getCommand("reliqueapi") != null) {
            getCommand("reliqueapi").setExecutor(reload);
            getCommand("reliqueapi").setTabCompleter(reload);
        }
        com.relique.onlineapi.dialog.Dialogs.init(this, statsManager);

        // Anyone already online when /reload happened should get a session start too.
        String premiumPerm = getConfig().getString("dialogs.premium-permission", "");
        for (Player p : Bukkit.getOnlinePlayers()) {
            statsManager.onJoin(p.getUniqueId(), p.getName());
            if (!premiumPerm.isBlank()) statsManager.setPremium(p.getUniqueId(), p.getName(), p.hasPermission(premiumPerm));
        }

        startHttpServer();
        recipes.start();
        scheduleAutosave();
    }

    /**
     * Re-reads config.yml and applies it live: Elo settings, dialog settings/placeholders, the
     * API port + key, and the autosave interval. Stats on disk and in memory are not touched.
     * Called by /reliqueapi reload (works from the console too).
     */
    public void reloadAll() {
        reloadConfig();
        statsManager.reloadSettings();
        health.reload();
        startRanked();

        com.relique.onlineapi.dialog.Dialogs.shutdown();
        com.relique.onlineapi.dialog.Dialogs.init(this, statsManager);

        stopHttpServer();
        startHttpServer();
        recipes.start();
        scheduleAutosave();
    }

    /** (Re)starts or stops the RankedSMPX rank cache according to ranked.enabled. */
    private void startRanked() {
        if (rankedLookup != null) {
            rankedLookup.stop();
            rankedLookup = null;
        }
        if (getConfig().getBoolean("ranked.enabled", true)) {
            rankedLookup = new com.relique.onlineapi.dialog.RankedLookup(this);
            rankedLookup.start();
        }
    }

    private void stopHttpServer() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    private void startHttpServer() {
        int port = getConfig().getInt("port", 25551);
        String apiKey = getConfig().getString("api-key", "");

        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);
            server.createContext("/api/online", new OnlineHandler(apiKey));
            server.createContext("/api/stats", new StatsHttpHandler(statsManager, apiKey, rankedLookup));
            server.createContext("/api/events", new EventsHttpHandler(statsManager, apiKey));
            server.createContext("/api/teams", new TeamsHttpHandler(statsManager, apiKey));
            server.createContext("/api/health", new HealthHttpHandler(health, apiKey));
            server.createContext("/api/recipes", new RecipesHttpHandler(recipes, apiKey));
            boolean ranked = getConfig().getBoolean("ranked.enabled", true);
            if (ranked) server.createContext("/api/ranked", new RankedHttpHandler(this, apiKey));
            server.setExecutor(null); // small endpoints, default executor is plenty
            server.start();
            getLogger().info("API listening on port " + port
                    + " (endpoints: /api/online, /api/stats, /api/events, /api/teams, /api/health, /api/recipes"
                    + (ranked ? ", /api/ranked" : "") + ")");
        } catch (IOException e) {
            getLogger().severe("Could not start API HTTP server on port " + port + ": " + e.getMessage());
        }
    }

    private void scheduleAutosave() {
        if (autosaveTask != null) autosaveTask.cancel();
        long autosaveTicks = Math.max(1L, getConfig().getLong("autosave-interval-minutes", 5)) * 60L * 20L;
        autosaveTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            statsManager.flushSessions();
            statsManager.save();
        }, autosaveTicks, autosaveTicks);
    }

    @Override
    public void onDisable() {
        if (rankedLookup != null) rankedLookup.stop();
        com.relique.onlineapi.dialog.Dialogs.shutdown();
        stopHttpServer();
        if (recipes != null) recipes.stop();
        if (health != null) health.stop();
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
