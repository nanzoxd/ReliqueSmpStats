package com.relique.onlineapi.dialog;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import com.destroystokyo.paper.profile.ProfileProperty;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.profile.PlayerProfile;

import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

/** Colours, number/date formatting and player-head helpers shared by the dialogs. */
final class Ui {

    // Gold theme. Constant names are historical: GOLD = main gold for Elo/rankings/values,
    // PURPLE = light gold for headings/actions, BLUE = pale gold for dividers/separators.
    // The other palette colours are used to tell things apart (wins, losses, peak, info).
    static final TextColor GOLD = TextColor.color(0xD8B04A);
    static final TextColor PURPLE = TextColor.color(0xE8C670);
    static final TextColor BLUE = TextColor.color(0xF1D58F);
    static final TextColor GREEN = TextColor.color(0x9DE24F);
    static final TextColor RED = TextColor.color(0xFF6666);
    static final TextColor ORANGE = TextColor.color(0xFFBD55);
    static final TextColor YELLOW = TextColor.color(0xFFFF66);
    static final TextColor SKY = TextColor.color(0x87CEFA);
    /** Close buttons. */
    static final TextColor CLOSE_RED = TextColor.color(0xFF6666);
    static final TextColor WHITE = NamedTextColor.WHITE;
    static final TextColor GRAY = NamedTextColor.GRAY;
    static final TextColor DARK_GRAY = NamedTextColor.DARK_GRAY;

    static final String NA = "N/A";

    private Ui() {}

    static Component t(String text, TextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }

    static Component bold(String text, TextColor color) {
        return Component.text(text, color).decoration(TextDecoration.BOLD, true).decoration(TextDecoration.ITALIC, false);
    }

    /** "Label: value" with a gray label and a coloured value. */
    static Component kv(String label, String value, TextColor valueColor) {
        return Component.textOfChildren(t(label + ": ", GRAY), t(value, valueColor));
    }

    /** Thin horizontal rule like the reference: struck-through spaces, ~70% of the given pixel width. */
    static Component divider(int widthPx) {
        int spaces = Math.max(10, widthPx * 7 / 40);
        return Component.text(" ".repeat(spaces), BLUE).decoration(TextDecoration.STRIKETHROUGH, true)
                .decoration(TextDecoration.ITALIC, false);
    }

    /** Several lines in ONE dialog text block (separate blocks get a big gap between them). */
    static Component lines(Component... parts) {
        return Component.join(JoinConfiguration.newlines(), parts);
    }

    // ------------------------------------------------------------ formatting

    static String num(long n) {
        return NumberFormat.getIntegerInstance(Locale.US).format(n);
    }

    /** 115740 seconds -> "32h 09m". */
    static String playtime(long seconds) {
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        return h + "h " + String.format("%02d", m) + "m";
    }

    private static volatile DateTimeFormatter dateOnly;
    private static volatile DateTimeFormatter dateTime;

    static void configureZone(String zoneName) {
        ZoneId zone;
        try {
            zone = (zoneName == null || zoneName.isBlank()) ? ZoneId.systemDefault() : ZoneId.of(zoneName.trim());
        } catch (Exception e) {
            zone = ZoneId.systemDefault();
        }
        dateOnly = DateTimeFormatter.ofPattern("dd/MM/yyyy").withZone(zone);
        dateTime = DateTimeFormatter.ofPattern("dd/MM/yyyy '·' HH:mm").withZone(zone);
    }

    static String date(long millis) {
        if (millis <= 0) return NA;
        if (dateOnly == null) configureZone(null);
        return dateOnly.format(Instant.ofEpochMilli(millis));
    }

    static String dateTime(long millis) {
        if (millis <= 0) return NA;
        if (dateTime == null) configureZone(null);
        return dateTime.format(Instant.ofEpochMilli(millis));
    }

    // ------------------------------------------------------------ heads

    /** A player-head item for the dialog's item body. Uses the stored skin URL when we have one. */
    static ItemStack headItem(UUID uuid, String name, String skinUrl) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        try {
            SkullMeta meta = (SkullMeta) item.getItemMeta();
            PlayerProfile profile = uuid != null
                    ? Bukkit.createPlayerProfile(uuid, name)
                    : Bukkit.createPlayerProfile(name);
            if (skinUrl != null) {
                try {
                    profile.getTextures().setSkin(new URL(skinUrl));
                } catch (MalformedURLException ignored) { /* name/uuid lookup instead */ }
            }
            meta.setOwnerProfile(profile);
            item.setItemMeta(meta);
        } catch (Throwable ignored) { /* plain head */ }
        return item;
    }

    // Inline (in-text) player heads need Adventure's ObjectContents (Paper 1.21.9+), looked up by
    // reflection so the plugin still loads on older versions (rows then have no head icon).
    // The skin is sent as a "textures" profile property, so cracked players with SkinsRestorer
    // (whose UUID/name Mojang doesn't know) still show their real skin.
    private static final Method PLAYER_HEAD;
    private static final Method B_NAME;
    private static final Method B_ID;
    private static final Method B_PROP;
    private static final Method B_BUILD;
    private static final Method PROP_NEW;
    private static final Method COMPONENT_OBJECT;
    static final boolean INLINE_HEADS;

    static {
        Method ph = null, bn = null, bi = null, bp = null, bb = null, pn = null, co = null;
        boolean ok = false;
        try {
            Class<?> oc = Class.forName("net.kyori.adventure.text.object.ObjectContents");
            Class<?> phc = Class.forName("net.kyori.adventure.text.object.PlayerHeadObjectContents");
            Class<?> builder = Class.forName("net.kyori.adventure.text.object.PlayerHeadObjectContents$Builder");
            ph = oc.getMethod("playerHead");
            bn = builder.getMethod("name", String.class);
            bi = builder.getMethod("id", UUID.class);
            bb = builder.getMethod("build");
            co = Component.class.getMethod("object", oc);
            ok = phc != null;
            try {
                Class<?> prop = Class.forName("net.kyori.adventure.text.object.PlayerHeadObjectContents$ProfileProperty");
                pn = prop.getMethod("profileProperty", String.class, String.class, String.class);
                bp = builder.getMethod("profileProperty", prop);
            } catch (Throwable ignored) { /* name/uuid only */ }
        } catch (Throwable ignored) { /* not available */ }
        PLAYER_HEAD = ph; B_NAME = bn; B_ID = bi; B_PROP = bp; B_BUILD = bb; PROP_NEW = pn; COMPONENT_OBJECT = co;
        INLINE_HEADS = ok;
    }

    /** Head icon with no stored skin (falls back to the live online skin, else name/uuid). */
    static Component inlineHead(UUID uuid, String name) {
        return inlineHead(uuid, name, null);
    }

    /** The little head icon used inside buttons, or an empty component if unsupported. */
    static Component inlineHead(UUID uuid, String name, String skinUrl) {
        if (!INLINE_HEADS) return Component.empty();
        try {
            Object builder = PLAYER_HEAD.invoke(null);
            B_NAME.invoke(builder, name);
            if (uuid != null) B_ID.invoke(builder, uuid);
            if (B_PROP != null) {
                String value = null, signature = null;
                // 1) live skin of an online player (what SkinsRestorer applied, signed)
                Player online = uuid != null ? Bukkit.getPlayer(uuid) : (name != null ? Bukkit.getPlayerExact(name) : null);
                if (online != null) {
                    for (ProfileProperty pp : online.getPlayerProfile().getProperties()) {
                        if ("textures".equals(pp.getName())) { value = pp.getValue(); signature = pp.getSignature(); break; }
                    }
                }
                // 2) stored skin URL (offline players)
                if (value == null && skinUrl != null && !skinUrl.isBlank()) {
                    String json = "{\"textures\":{\"SKIN\":{\"url\":\"" + skinUrl + "\"}}}";
                    value = Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
                }
                if (value != null) B_PROP.invoke(builder, PROP_NEW.invoke(null, "textures", value, signature));
            }
            Object contents = B_BUILD.invoke(builder);
            return (Component) COMPONENT_OBJECT.invoke(null, contents);
        } catch (Throwable t) {
            return Component.empty();
        }
    }
}
