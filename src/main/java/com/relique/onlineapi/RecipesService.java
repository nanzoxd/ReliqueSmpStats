package com.relique.onlineapi;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Mirrors the recipes created with the NgaRecipes plugin (/addrecipe, /editrecipe, /delrecipe) so the
 * website can show them. NgaRecipes saves every change to plugins/NgaRecipes/recipes.yml; this checks
 * that file every few seconds and, when it changed, rebuilds the JSON that GET /api/recipes serves.
 *
 * The parsing runs on the main server thread (ItemStack deserialisation touches server registries);
 * the HTTP handler only ever reads the finished string.
 */
public final class RecipesService {

    private final JavaPlugin plugin;
    private BukkitTask task;
    private volatile String json = emptyJson(false);
    private long lastModified = -1;
    private long lastLength = -1;

    public RecipesService(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public String json() {
        return json;
    }

    /** Starts watching the file. Safe to call again after a config reload. */
    public void start() {
        stop();
        lastModified = -1;
        lastLength = -1;
        long ticks = Math.max(1L, plugin.getConfig().getLong("recipes.check-interval-seconds", 5)) * 20L;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::refresh, 20L, ticks);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private File file() {
        String rel = plugin.getConfig().getString("recipes.file", "NgaRecipes/recipes.yml");
        File f = new File(rel);
        return f.isAbsolute() ? f : new File(plugin.getDataFolder().getParentFile(), rel);
    }

    private void refresh() {
        try {
            File f = file();
            if (!f.isFile()) {
                if (lastModified != -2) {
                    json = emptyJson(false);
                    lastModified = -2;
                    lastLength = -2;
                }
                return;
            }
            if (f.lastModified() == lastModified && f.length() == lastLength) return;
            lastModified = f.lastModified();
            lastLength = f.length();
            json = build(f);
        } catch (Throwable t) {
            plugin.getLogger().warning("Could not read the recipes file: " + t.getMessage());
        }
    }

    // ------------------------------------------------------------------ building

    private String build(File f) {
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(f);
        ConfigurationSection sec = cfg.getConfigurationSection("recipes");

        // showcase order: the saved order first, then anything left over by id (same as NgaRecipes)
        List<Integer> order = new ArrayList<>();
        TreeSet<Integer> all = new TreeSet<>();
        if (sec != null) {
            for (String k : sec.getKeys(false)) {
                try {
                    all.add(Integer.parseInt(k));
                } catch (NumberFormatException ignored) { }
            }
        }
        for (int id : cfg.getIntegerList("order")) if (all.contains(id) && !order.contains(id)) order.add(id);
        for (int id : all) if (!order.contains(id)) order.add(id);

        StringBuilder sb = new StringBuilder("{\"available\":true,\"updated\":")
                .append(System.currentTimeMillis()).append(",\"recipes\":[");
        int count = 0;
        for (int id : order) {
            ConfigurationSection rs = sec == null ? null : sec.getConfigurationSection(String.valueOf(id));
            if (rs == null) continue;
            ItemStack out = rs.getItemStack("output");
            if (out == null || out.getType().isAir()) continue;

            if (count++ > 0) sb.append(',');
            sb.append("{\"id\":").append(id).append(",\"name\":");
            String saved = rs.getString("name");
            str(sb, saved == null || saved.isBlank() ? null : saved);
            sb.append(",\"input\":{");
            boolean first = true;
            for (int i = 0; i < 9; i++) {
                ItemStack it = rs.getItemStack("ingredients." + (i + 1));
                if (it == null || it.getType().isAir()) continue;
                if (!first) sb.append(',');
                first = false;
                sb.append('"').append(i).append("\":");
                item(sb, it);
            }
            sb.append("},\"output\":");
            item(sb, out);
            sb.append('}');
        }
        sb.append("],\"count\":").append(count).append('}');
        return sb.toString();
    }

    /** One item in the shape the website already uses: id, count and, if present, name/colors/lore/skin/... */
    private void item(StringBuilder sb, ItemStack it) {
        sb.append("{\"id\":");
        str(sb, it.getType().getKey().getKey());
        sb.append(",\"count\":").append(Math.max(1, it.getAmount()));

        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            Component name = null;
            try {
                if (meta.hasDisplayName()) name = meta.displayName();
                else if (meta.hasItemName()) name = meta.itemName();
            } catch (Throwable ignored) { }
            if (name != null) {
                String plain = PlainTextComponentSerializer.plainText().serialize(name);
                if (!plain.isBlank()) {
                    sb.append(",\"name\":");
                    str(sb, plain);
                    List<String> colors = charColors(name, plain);
                    if (colors != null) {
                        sb.append(",\"colors\":[");
                        for (int i = 0; i < colors.size(); i++) {
                            if (i > 0) sb.append(',');
                            str(sb, colors.get(i));
                        }
                        sb.append(']');
                    }
                }
            }
            if (meta.hasLore() && meta.lore() != null) {
                StringBuilder lore = new StringBuilder();
                for (Component l : meta.lore()) {
                    if (lore.length() > 0) lore.append('\n');
                    lore.append(PlainTextComponentSerializer.plainText().serialize(l));
                }
                if (lore.toString().isBlank()) lore.setLength(0);
                if (lore.length() > 0) {
                    sb.append(",\"lore\":");
                    str(sb, lore.toString());
                }
            }
            if (meta instanceof SkullMeta sm) {
                String skin = skinUrl(sm);
                if (skin != null) {
                    sb.append(",\"skin\":");
                    str(sb, skin);
                }
            }
            try {
                if (meta.hasItemModel() && meta.getItemModel() != null) {
                    sb.append(",\"customModel\":true,\"model\":");
                    str(sb, meta.getItemModel().asString());
                }
            } catch (Throwable ignored) { }
            if (!meta.getPersistentDataContainer().getKeys().isEmpty()) sb.append(",\"customData\":true");
        }
        Map<Enchantment, Integer> ench = it.getEnchantments();
        if (!ench.isEmpty()) {
            sb.append(",\"enchants\":{");
            boolean first = true;
            for (Map.Entry<Enchantment, Integer> e : ench.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                str(sb, e.getKey().getKey().getKey());
                sb.append(':').append(e.getValue());
            }
            sb.append('}');
        }
        sb.append('}');
    }

    /** Per-character colours of a styled name (handles gradients); null when the name has no colour at all. */
    private static List<String> charColors(Component name, String plain) {
        List<String> out = new ArrayList<>();
        boolean[] any = {false};
        walk(name, null, out, any);
        if (!any[0] || out.size() != plain.length()) return null;
        for (int i = 0; i < out.size(); i++) if (out.get(i) == null) out.set(i, "#ffffff");
        return out;
    }

    private static void walk(Component c, TextColor inherited, List<String> out, boolean[] any) {
        TextColor own = c.color() != null ? c.color() : inherited;
        if (c instanceof TextComponent tc) {
            for (int i = 0; i < tc.content().length(); i++) {
                out.add(own == null ? null : own.asHexString());
                if (own != null) any[0] = true;
            }
        }
        for (Component child : c.children()) walk(child, own, out, any);
    }

    private static String skinUrl(SkullMeta sm) {
        try {
            org.bukkit.profile.PlayerProfile profile = sm.getOwnerProfile();
            if (profile == null) return null;
            java.net.URL url = profile.getTextures().getSkin();
            return url == null ? null : url.toString().replaceFirst("^http:", "https:");
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ------------------------------------------------------------------ json helpers

    static String emptyJson(boolean available) {
        return "{\"available\":" + available + ",\"updated\":" + System.currentTimeMillis() + ",\"recipes\":[],\"count\":0}";
    }

    private static void str(StringBuilder sb, String s) {
        if (s == null) {
            sb.append("null");
            return;
        }
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }
}
