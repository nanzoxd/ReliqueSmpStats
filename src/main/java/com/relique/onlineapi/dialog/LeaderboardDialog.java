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

    private record Row(int position, UUID uuid, String name, long elo) {}

    public void open(Player viewer, State st) {
        List<PlayerStats> all = stats.leaderboard();
        String q = st.query().toLowerCase(Locale.ROOT);

        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            PlayerStats s = all.get(i);
            String n = s.name == null ? "Unknown" : s.name;
            if (q.isEmpty() || n.toLowerCase(Locale.ROOT).contains(q)) {
                rows.add(new Row(i + 1, s.uuid, n, Math.round(s.elo)));
            }
        }

        int pages = Math.max(1, (rows.size() + perPage - 1) / perPage);
        int page = Math.min(st.page(), pages - 1);
        int from = page * perPage;
        int to = Math.min(rows.size(), from + perPage);
        final String query = st.query();

        // ---- header text
        List<DialogBody> body = new ArrayList<>();
        if (rows.isEmpty()) {
            body.add(DialogBody.plainMessage(t("No players found", RED)));
        } else {
            body.add(DialogBody.plainMessage(t("#" + rows.get(from).position() + " - #" + rows.get(to - 1).position(), WHITE)));
        }
        body.add(DialogBody.plainMessage(t("Players ranked by Elo", GRAY)));
        body.add(DialogBody.plainMessage(Component.textOfChildren(
                t("Page ", GRAY), t(String.valueOf(page + 1), WHITE), t(" / ", GRAY), t(String.valueOf(pages), WHITE))));
        if (!query.isEmpty()) {
            body.add(DialogBody.plainMessage(Component.textOfChildren(
                    t("Search: ", GRAY), t(query, GOLD), t("  (" + rows.size() + " found)", GRAY))));
        }

        // ---- search box
        List<DialogInput> inputs = List.of(
                DialogInput.text("query", t("Find a player", PURPLE))
                        .width(buttonWidth * 2 + 10)
                        .initial(query)
                        .maxLength(16)
                        .build());

        // ---- buttons
        List<ActionButton> buttons = new ArrayList<>();
        for (int i = from; i < to; i++) {
            Row r = rows.get(i);
            Component label = Component.textOfChildren(
                    t("#" + r.position() + " ", GOLD),
                    inlineHead(r.uuid(), r.name()),
                    t(" " + r.name(), WHITE),
                    t("  " + num(r.elo()), GOLD));
            State back = new State(page, query);
            buttons.add(ActionButton.create(label, t("View " + r.name() + "'s stats", GRAY), buttonWidth,
                    click((view, p) -> profile.open(p, r.uuid(), back))));
        }
        // an odd last row would leave a hole in the 2-column grid, so keep controls on a fresh row
        if (buttons.size() % 2 == 1) {
            buttons.add(ActionButton.create(Component.empty(), null, buttonWidth, null));
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
        Component myLabel = Component.textOfChildren(
                inlineHead(viewer.getUniqueId(), viewer.getName()),
                t(" My Position", PURPLE),
                myPos > 0 ? t(" #" + myPos, GOLD) : Component.empty());
        buttons.add(ActionButton.create(myLabel, t("Open your own profile", GRAY), buttonWidth,
                click((view, p) -> myPosition(p))));
        buttons.add(ActionButton.create(t("⚔ Find Player", PURPLE), t("Search for the name typed above", GRAY), buttonWidth,
                click((view, p) -> find(p, view.getText("query")))));

        ActionButton close = ActionButton.create(t("✖ Close", CLOSE_RED), null, buttonWidth, null);

        Dialog dialog = Dialog.create(b -> b.empty()
                .base(DialogBase.builder(bold("ELO LEADERBOARD", PURPLE))
                        .canCloseWithEscape(true)
                        .body(body)
                        .inputs(inputs)
                        .build())
                .type(DialogType.multiAction(buttons, close, 2)));
        viewer.showDialog(dialog);
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
