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
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.event.ClickCallback;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

import static com.relique.onlineapi.dialog.Ui.*;

/**
 * Paged list of EVERY player who holds a RankedSMPX rank (online or offline), #1 first.
 * Opened with /rankedplayers. Only created when ranked.enabled and ranked.show-all-players are true.
 * Clicking a player opens their Elo profile (when they have stats).
 */
public final class RankedPlayersDialog {

    private static final ClickCallback.Options CLICK = ClickCallback.Options.builder()
            .uses(ClickCallback.UNLIMITED_USES)
            .lifetime(ClickCallback.DEFAULT_LIFETIME)
            .build();

    private final OnlineApiPlugin plugin;
    private final StatsManager stats;
    private final RankedLookup ranked;
    private final ProfileDialog profile;
    private final int perPage;
    private final int buttonWidth;

    public RankedPlayersDialog(OnlineApiPlugin plugin, StatsManager stats, RankedLookup ranked, ProfileDialog profile) {
        this.plugin = plugin;
        this.stats = stats;
        this.ranked = ranked;
        this.profile = profile;
        // Two columns, so rows are always even (same setting as the Elo leaderboard).
        int pp = Math.max(2, plugin.getConfig().getInt("dialogs.players-per-page", 12));
        this.perPage = pp % 2 == 0 ? pp : pp + 1;
        this.buttonWidth = Math.max(120, Math.min(300, plugin.getConfig().getInt("dialogs.button-width", 180)));
    }

    public void open(Player viewer, int requestedPage) {
        List<RankedLookup.Entry> all = ranked.all();
        int pages = Math.max(1, (all.size() + perPage - 1) / perPage);
        int page = Math.max(0, Math.min(requestedPage, pages - 1));
        int from = page * perPage;
        int to = Math.min(all.size(), from + perPage);

        // ---- header
        int fullWidth = buttonWidth * 2 + 10;
        long online = all.stream().filter(e -> Bukkit.getPlayer(e.uuid()) != null).count();
        List<Component> head = new ArrayList<>();
        head.add(bold("RANKED PLAYERS", LB_TITLE));
        if (all.isEmpty()) {
            head.add(t("No ranked players yet", RED));
        } else {
            head.add(t("#" + all.get(from).rank() + " - #" + all.get(to - 1).rank(), LB_SUB));
            head.add(Component.textOfChildren(
                    t(all.size() + " ranked", WHITE), t("  ·  ", GRAY), t(online + " online", GREEN)));
        }
        head.add(t("Page " + (page + 1) + " / " + pages, GRAY));
        head.add(divider(fullWidth, LB_RULE));
        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.plainMessage(Component.join(JoinConfiguration.newlines(), head), fullWidth));

        // ---- one button per ranked player
        List<ActionButton> buttons = new ArrayList<>();
        for (int i = from; i < to; i++) {
            RankedLookup.Entry e = all.get(i);
            PlayerStats ps = stats.find(e.uuid());
            boolean isOnline = Bukkit.getPlayer(e.uuid()) != null;
            Component label = Component.textOfChildren(
                    bold("#" + e.rank() + " ", rankColor(e.rank())),
                    inlineHead(e.uuid(), e.name(), ps == null ? null : ps.skinUrl),
                    t(" " + e.name(), WHITE),
                    t(isOnline ? "  ●" : "  ○", isOnline ? GREEN : DARK_GRAY));
            String last = isOnline ? "Online now" : dateTime(Bukkit.getOfflinePlayer(e.uuid()).getLastPlayed());
            Component tip = t("Rank #" + e.rank() + " · " + (isOnline ? last : "Last seen " + last), GRAY);
            DialogAction action = ps == null ? null : click((view, p) -> profile.open(p, e.uuid(), null));
            buttons.add(ActionButton.create(label, tip, buttonWidth, action));
        }
        // Empty slots so the dialog is always full height (same trick as the Elo leaderboard).
        for (int i = to; i < from + perPage; i++) {
            buttons.add(ActionButton.create(Component.textOfChildren(
                    bold("#" + (i + 1) + " ", DARK_GRAY), t("-", DARK_GRAY)), null, buttonWidth, null));
        }

        boolean hasPrev = page > 0;
        boolean hasNext = page < pages - 1;
        buttons.add(ActionButton.create(
                hasPrev ? t("← Previous Page", WHITE) : t("← Previous Page", DARK_GRAY), null, buttonWidth,
                hasPrev ? click((view, p) -> open(p, page - 1)) : null));
        buttons.add(ActionButton.create(
                hasNext ? t("Next Page →", WHITE) : t("Next Page →", DARK_GRAY), null, buttonWidth,
                hasNext ? click((view, p) -> open(p, page + 1)) : null));

        ActionButton close = ActionButton.create(t("✖ Close", CLOSE_RED), null, buttonWidth, null);

        Dialog dialog = Dialog.create(b -> b.empty()
                .base(DialogBase.builder(Component.empty())
                        .canCloseWithEscape(true)
                        .body(body)
                        .build())
                .type(DialogType.multiAction(buttons, close, 2)));
        viewer.showDialog(dialog);
    }

    private static net.kyori.adventure.text.format.TextColor rankColor(int rank) {
        return switch (rank) {
            case 1 -> RANK_1;
            case 2 -> RANK_2;
            case 3 -> RANK_3;
            default -> LB_TITLE;
        };
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
