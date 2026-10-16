package com.relique.onlineapi.dialog;

import com.relique.onlineapi.OnlineApiPlugin;
import com.relique.onlineapi.stats.StatsManager;
import com.relique.onlineapi.stats.StatsManager.PlayerStats;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.action.DialogActionCallback;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static com.relique.onlineapi.dialog.Ui.*;

/** Paged, searchable Elo leaderboard built with Paper's Dialog API. */
public final class LeaderboardDialog {

    /** Where the viewer is in the list; carried into the profile so "Back" restores it. */
    public record State(int page, String query) {
        public State {
            query = query == null ? "" : query.trim();
            page = Math.max(0, page);
        }
        public static State first() { return new State(0, ""); }
    }

    private static final ClickCallback.Options CLICK = ClickCallback.Options.builder()
            .uses(ClickCallback.UNLIMITED_USES)
            .lifetime(ClickCallback.DEFAULT_LIFETIME)
            .build();

    private final OnlineApiPlugin plugin;
    private final StatsManager stats;
    private final ProfileDialog profile;
    private final int perPage;
    private final int buttonWidth;

    public LeaderboardDialog(OnlineApiPlugin plugin, StatsManager stats, ProfileDialog profile) {
        this.plugin = plugin;
        this.stats = stats;
        this.profile = profile;
        // Two columns, so rows are always even. Default 12 players (6 rows) per page.
        int pp = Math.max(2, plugin.getConfig().getInt("dialogs.players-per-page", 12));
        this.perPage = pp % 2 == 0 ? pp : pp + 1;
        this.buttonWidth = Math.max(120, Math.min(300, plugin.getConfig().getInt("dialogs.button-width", 180)));
    }

    private record Row(int position, UUID uuid, String name, long elo, String skin) {}

    public void open(Player viewer, State st) {
        List<PlayerStats> all = stats.leaderboard();
        String q = st.query().toLowerCase(Locale.ROOT);

        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            PlayerStats s = all.get(i);
            String n = s.name == null ? "Unknown" : s.name;
            if (q.isEmpty() || n.toLowerCase(Locale.ROOT).contains(q)) {
                rows.add(new Row(i + 1, s.uuid, n, Math.round(s.elo), s.skinUrl));
            }
        }

        int pages = Math.max(1, (rows.size() + perPage - 1) / perPage);
        int page = Math.min(st.page(), pages - 1);
        int from = page * perPage;
        int to = Math.min(rows.size(), from + perPage);
        final String query = st.query();

        // ---- header: ONE text block (title is the dialog title above it); the divider is the last
        // line of the same block so there is no empty gap before it.
        int fullWidth = buttonWidth * 2 + 10;
        List<Component> head = new ArrayList<>();
        head.add(bold("ELO LEADERBOARD", LB_TITLE));
        if (rows.isEmpty()) head.add(t("No players found", RED));
        else head.add(t("#" + rows.get(from).position() + " - #" + rows.get(to - 1).position(), LB_SUB));
        head.add(t("Players ranked by Elo", WHITE));
        head.add(t("See where you stand on the server", WHITE));
        head.add(t("Page " + (page + 1) + " / " + pages, GRAY));
        if (!query.isEmpty()) {
            head.add(Component.textOfChildren(
                    t("Search: ", GRAY), t(query, LB_TITLE), t("  (" + rows.size() + " found)", GRAY)));
        }
        head.add(divider(fullWidth, LB_RULE));
        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.plainMessage(Component.join(net.kyori.adventure.text.JoinConfiguration.newlines(), head), fullWidth));

        // ---- search box
        List<DialogInput> inputs = List.of(
                DialogInput.text("query", t("Find a player", LB_SUB))
                        .width(buttonWidth * 2 + 10)
                        .initial(query)
                        .maxLength(16)
                        .build());

        // ---- buttons
        List<ActionButton> buttons = new ArrayList<>();
        for (int i = from; i < to; i++) {
            Row r = rows.get(i);
            Component label = Component.textOfChildren(
                    t("#", rankColor(r.position())),
                    bold(r.position() + " ", rankColor(r.position())),
                    inlineHead(r.uuid(), r.name(), r.skin()),
                    t(" " + r.name(), WHITE),
                    t("  " + num(r.elo()), LB_TITLE));
            State back = new State(page, query);
            buttons.add(ActionButton.create(label, t("View " + r.name() + "'s stats", GRAY), buttonWidth,
                    click((view, p) -> profile.open(p, r.uuid(), back))));
        }
        // Fill the page with empty slots so the dialog is always full height. A short dialog is
        // centred by the client, which leaves a big gap under the icon; a full one sits tight.
        for (int i = to; i < from + perPage; i++) {
            buttons.add(ActionButton.create(Component.textOfChildren(
                    t("#", DARK_GRAY), bold((i + 1) + " ", DARK_GRAY), t("-", DARK_GRAY)), null, buttonWidth, null));
        }

        boolean hasPrev = page > 0;
        boolean hasNext = page < pages - 1;
        buttons.add(ActionButton.create(
                hasPrev ? t("← Previous Page", WHITE) : t("← Previous Page", DARK_GRAY), null, buttonWidth,
                hasPrev ? click((view, p) -> open(p, new State(page - 1, query))) : null));
        buttons.add(ActionButton.create(
                hasNext ? t("Next Page →", WHITE) : t("Next Page →", DARK_GRAY), null, buttonWidth,
                hasNext ? click((view, p) -> open(p, new State(page + 1, query))) : null));
        int myPos = stats.positionOf(viewer.getUniqueId());
        PlayerStats mine = stats.find(viewer.getUniqueId());
        Component myLabel = Component.textOfChildren(
                inlineHead(viewer.getUniqueId(), viewer.getName(), mine == null ? null : mine.skinUrl),
                t(" My Position", LB_TITLE),
                myPos > 0 ? Component.textOfChildren(t(" #", LB_TITLE), bold(String.valueOf(myPos), LB_TITLE)) : Component.empty());
        buttons.add(ActionButton.create(myLabel, t("Open your own profile", GRAY), buttonWidth,
                click((view, p) -> myPosition(p))));
        buttons.add(ActionButton.create(t("⚔ Find Player", LB_TITLE), t("Search for the name typed above", GRAY), buttonWidth,
                click((view, p) -> find(p, view.getText("query")))));

        ActionButton close = ActionButton.create(t("✖ Close", CLOSE_RED), null, buttonWidth, null);

        Dialog dialog = Dialog.create(b -> b.empty()
                .base(DialogBase.builder(Component.empty())
                        .canCloseWithEscape(true)
                        .body(body)
                        .inputs(inputs)
                        .build())
                .type(DialogType.multiAction(buttons, close, 2)));
        viewer.showDialog(dialog);
    }

    private static net.kyori.adventure.text.format.TextColor rankColor(int position) {
        return switch (position) {
            case 1 -> RANK_1;
            case 2 -> RANK_2;
            case 3 -> RANK_3;
            default -> LB_TITLE;
        };
    }

    // ------------------------------------------------------------ actions

    private void myPosition(Player p) {
        int pos = stats.positionOf(p.getUniqueId());
        if (pos < 1) {
            p.sendMessage(Component.text("You are not on the leaderboard yet.", RED));
            open(p, State.first());
            return;
        }
        // your own profile; Back returns to the leaderboard page you are on
        profile.open(p, p.getUniqueId(), new State((pos - 1) / perPage, ""));
    }

    private void find(Player p, String typed) {
        String q = typed == null ? "" : typed.trim();
        if (q.isEmpty()) {
            open(p, State.first());
            return;
        }
        // an exact username goes straight to that player's profile
        for (PlayerStats s : stats.leaderboard()) {
            if (s.name != null && s.name.equalsIgnoreCase(q)) {
                profile.open(p, s.uuid, new State(0, q));
                return;
            }
        }
        open(p, new State(0, q));
    }

    /** Wraps a click handler so it runs on the main thread and only for real players. */
    private DialogAction click(Handler handler) {
        DialogActionCallback cb = (DialogResponseView view, Audience audience) -> {
            if (!(audience instanceof Player p)) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (p.isOnline()) handler.run(view, p);
            });
        };
        return DialogAction.customClick(cb, CLICK);
    }

    @FunctionalInterface
    private interface Handler {
        void run(DialogResponseView view, Player player);
    }
}
