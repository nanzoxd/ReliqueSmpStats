package com.relique.onlineapi.dialog;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.profile.PlayerProfile;

import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

/** Colours, number/date formatting and player-head helpers shared by the dialogs. */
final class Ui {

    // Blue theme. Constant names are historical: GOLD = vivid blue for Elo/rankings, PURPLE = sky blue
    // for headings/actions, BLUE = pale blue for dividers/separators. Green and orange-red stay as the
    // contrasting colours for wins/gains and losses.
    static final TextColor GOLD = TextColor.color(0x00AEFF);
    static final TextColor GREEN = TextColor.color(0x2EE869);
    static final TextColor RED = TextColor.color(0xFF4C00);
    /** Plain red for Close buttons (losses use the orange-red RED above). */
    static final TextColor CLOSE_RED = NamedTextColor.RED;
    static final TextColor PURPLE = TextColor.color(0x87CEEB);
    static final TextColor BLUE = TextColor.color(0xA7C7E7);
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

    // Inline (in-text) player heads need Adventure's ObjectContents (Paper 1.21.9+). It is looked
    // up by reflection so the plugin still compiles and loads on anything older; without it the
    // rows simply have no head icon.
    private static final Method PLAYER_HEAD;
    private static final Method B_NAME;
    private static final Method B_ID;
    private static final Method B_BUILD;
    private static final Method COMPONENT_OBJECT;
    static final boolean INLINE_HEADS;

    static {
        Method ph = null, bn = null, bi = null, bb = null, co = null;
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
        } catch (Throwable ignored) { /* not available */ }
        PLAYER_HEAD = ph; B_NAME = bn; B_ID = bi; B_BUILD = bb; COMPONENT_OBJECT = co;
        INLINE_HEADS = ok;
    }

    /** The little head icon used inside leaderboard buttons, or an empty component if unsupported. */
    static Component inlineHead(UUID uuid, String name) {
        if (!INLINE_HEADS) return Component.empty();
        try {
            Object builder = PLAYER_HEAD.invoke(null);
            B_NAME.invoke(builder, name);
            if (uuid != null) B_ID.invoke(builder, uuid);
            Object contents = B_BUILD.invoke(builder);
            return (Component) COMPONENT_OBJECT.invoke(null, contents);
        } catch (Throwable t) {
            return Component.empty();
        }
    }
}
