package com.relique.onlineapi;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * GET /api/recipes
 *
 * Every recipe made with the NgaRecipes plugin, in showcase order, for the website's recipes page:
 * { "available": true, "updated": <ms>, "count": 2, "recipes": [
 *     { "id": 1, "name": "...", "input": { "<slot 0-8>": item, ... }, "output": item } ] }
 * where item = { id, count, name?, colors?, lore?, skin?, customModel?, model?, customData?, enchants? }.
 * "available" is false when NgaRecipes' recipes.yml does not exist (plugin not installed / no recipes yet).
 */
public class RecipesHttpHandler implements HttpHandler {

    private final RecipesService recipes;
    private final String apiKey;

    public RecipesHttpHandler(RecipesService recipes, String apiKey) {
        this.recipes = recipes;
        this.apiKey = apiKey;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");

        if (apiKey != null && !apiKey.isEmpty()) {
            String provided = exchange.getRequestHeaders().getFirst("X-Api-Key");
            if (provided == null || !provided.equals(apiKey)) {
                write(exchange, 401, "{\"error\":\"unauthorized\"}");
                return;
            }
        }
        write(exchange, 200, recipes.json());
    }

    private void write(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
