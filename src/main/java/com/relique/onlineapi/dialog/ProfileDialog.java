package com.relique.onlineapi.dialog;

import com.relique.onlineapi.OnlineApiPlugin;
import com.relique.onlineapi.stats.StatsManager;
import com.relique.onlineapi.stats.StatsManager.Fight;
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
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.relique.onlineapi.dialog.Ui.*;

/** One single-page player profile: header, stats, best tier and exactly one recent fight. */
public final class ProfileDialog {

    private static final ClickCallback.Options CLICK = ClickCallback.Options.builder()
            .uses(ClickCallback.UNLIMITED_USES)
            .lifetime(ClickCallback.DEFAULT_LIFETIME)
            .build();

    private final OnlineApiPlugin plugin;
    private final StatsManager stats;
    private final RankedLookup ranked;
    private LeaderboardDialog leaderboard; // set after construction (the two reference each other)
    private final int bodyWidth;

    public ProfileDialog(OnlineApiPlugin plugin, StatsManager stats, RankedLookup ranked) {
        this.plugin = plugin;
        this.stats = stats;
        this.ranked = ranked;
        this.bodyWidth = Math.max(200, Math.min(400, plugin.getConfig().getInt("dialogs.profile-width", 320)));
    }

    void setLeaderboard(LeaderboardDialog leaderboard) {
        this.leaderboard = leaderboard;
    }

    public void open(Player viewer, UUID target, LeaderboardDialog.State back) {
        PlayerStats s = stats.find(target);
        if (s == null) {
            viewer.sendMessage(Component.text("No stats found for that player.", RED));
            return;
        }

        // ------------------------------------------------------------ derived values
        boolean online = Bukkit.getPlayer(target) != null;
        int smpRank = ranked.rankOf(target);
        int position = stats.positionOf(target);

        int wins = s.kills;
        int losses = s.pvpDeaths;
        String winRate = (wins + losses) == 0 ? NA : Math.round(100.0 * wins / (wins + losses)) + "%";

        long playtime = s.playtimeSeconds + (s.sessionStart == 0 ? 0 : (System.currentTimeMillis() - s.sessionStart) / 1000L);

        long firstJoined = Bukkit.getOfflinePlayer(target).getFirstPlayed();
        if (firstJoined <= 0) firstJoined = s.firstJoined;

        String lastSeen;
        if (online) {
            lastSeen = "Online now";
        } else {
            long ls = s.lastSeen > 0 ? s.lastSeen : Bukkit.getOfflinePlayer(target).getLastPlayed();
            lastSeen = dateTime(ls);
        }

        String name = s.name == null ? "Unknown" : s.name;
        List<DialogBody> body = new ArrayList<>();

        // ------------------------------------------------------------ header
        List<Component> sub = new ArrayList<>();
        sub.add(t(online ? "Online" : "Offline", online ? GREEN : GRAY));
        if (smpRank > 0) sub.add(t("SMP Rank #" + smpRank, GOLD));
        Component subLine = Component.empty();
        for (int i = 0; i < sub.size(); i++) {
            if (i > 0) subLine = subLine.append(t(" · ", BLUE));
            subLine = subLine.append(sub.get(i));
        }
        Component nameLine = bold(name, WHITE);
        if (s.premium) nameLine = nameLine.append(t("  [PREMIUM]", GOLD));
        Component headerText = Component.textOfChildren(nameLine, Component.newline(), subLine);
        body.add(DialogBody.item(headItem(s.uuid, name, s.skinUrl),
                DialogBody.plainMessage(headerText, bodyWidth - 40), false, false, 32, 32));

        // ------------------------------------------------------------ summary block
        body.add(line(kvLine("Rating", num(Math.round(s.elo)), GOLD)
                .append(t("  (Peak: " + num(Math.round(Math.max(s.peakElo, s.elo))) + ")", GRAY))));
        body.add(line(Component.textOfChildren(
                t("Record: ", GRAY), t(wins + "W", GREEN), t(" / ", GRAY), t(losses + "L", RED),
                t("  (" + winRate + " WR)", GRAY))));
        body.add(line(kvLine("Position", position > 0 ? "#" + position : NA, GOLD)));
        body.add(line(streakLine(s)));

        // ------------------------------------------------------------ statistics
        body.add(line(divider()));
        body.add(line(bold("PLAYER STATISTICS", PURPLE)));
        body.add(line(Component.textOfChildren(
                kvLine("Kills", num(s.kills), WHITE), sep(),
                kvLine("Deaths", num(s.deaths), WHITE), sep(),
                kvLine("K/D", String.format("%.2f", s.kd()), WHITE))));
        body.add(line(Component.textOfChildren(
                kvLine("Playtime", playtime(playtime), WHITE), sep(),
                kvLine("Blocks Mined", num(s.blocksMined), WHITE))));
        body.add(line(Component.textOfChildren(
                kvLine("SMP Rank", smpRank > 0 ? "#" + smpRank : NA, smpRank > 0 ? GOLD : GRAY), sep(),
                kvLine("First Joined", date(firstJoined), WHITE))));
        body.add(line(kvLine("Last Seen", lastSeen, online ? GREEN : WHITE)));

        // ------------------------------------------------------------ best tier
        body.add(line(divider()));
        body.add(line(bold("BEST TIER", PURPLE)));
        if (s.tierBest == null || s.tierBest.isBlank()) {
            body.add(line(t("Not ranked", GRAY)));
        } else {
            body.add(line(t(orNa(s.tierPlatform), WHITE)));
            body.add(line(Component.textOfChildren(
                    kvLine("Best Tier", s.tierBest, GOLD), sep(),
                    kvLine("Gamemode", orNa(s.tierMode), WHITE))));
            if (s.tierRank != null && !s.tierRank.isBlank()) {
                body.add(line(kvLine("Tier Rank", s.tierRank, WHITE)));
            }
        }

        // ------------------------------------------------------------ recent fight (exactly one)
        body.add(line(divider()));
        body.add(line(bold("RECENT FIGHT", PURPLE)));
        if (s.fights.isEmpty()) {
            body.add(line(t("No fights recorded", GRAY)));
        } else {
            Fight f = s.fights.get(0); // newest first
            PlayerStats opp = stats.findByName(f.opponent);
            ItemHead head = new ItemHead(opp == null ? null : opp.uuid, f.opponent, opp == null ? null : opp.skinUrl);

            TextColor resultColor = f.win ? GREEN : RED;
            String change = f.eloChange > 0 ? "+" + f.eloChange : String.valueOf(f.eloChange);
            TextColor changeColor = f.eloChange > 0 ? GREEN : (f.eloChange < 0 ? RED : GRAY);
            Component text = Component.textOfChildren(
                    t(f.opponent, WHITE), t("   " + (f.win ? "WIN" : "LOSS"), resultColor), Component.newline(),
                    t(f.opponentElo >= 0 ? num(f.opponentElo) + " Elo" : NA + " Elo", GOLD),
                    t("   " + change + " Elo", changeColor), Component.newline(),
                    t(dateTime(f.time), GRAY));
            body.add(DialogBody.item(headItem(head.uuid, head.name, head.skin),
                    DialogBody.plainMessage(text, bodyWidth - 40), false, false, 32, 32));
        }

        // ------------------------------------------------------------ bottom buttons (only these two)
        ActionButton backBtn = ActionButton.create(t("← Back to Leaderboard", WHITE), null, 150,
                click((view, p) -> leaderboard.open(p, back == null ? LeaderboardDialog.State.first() : back)));
        ActionButton closeBtn = ActionButton.create(t("✖ Close", CLOSE_RED), null, 150, null);

        Dialog dialog = Dialog.create(b -> b.empty()
                .base(DialogBase.builder(bold(name.toUpperCase(), PURPLE))
                        .canCloseWithEscape(true)
                        .body(body)
                        .build())
                .type(DialogType.confirmation(backBtn, closeBtn)));
        viewer.showDialog(dialog);
    }

    // ------------------------------------------------------------ helpers

    private record ItemHead(UUID uuid, String name, String skin) {}

    private DialogBody line(Component c) {
        return DialogBody.plainMessage(c, bodyWidth);
    }

    private static Component kvLine(String label, String value, TextColor valueColor) {
        return kv(label, value, valueColor);
    }

    private static Component sep() {
        return t("   |   ", BLUE);
    }

    private static String orNa(String s) {
        return (s == null || s.isBlank()) ? NA : s;
    }

    private static Component streakLine(PlayerStats s) {
        String cur;
        TextColor color;
        if (s.winStreak > 0) {
            cur = s.winStreak + (s.winStreak == 1 ? " Win in a Row" : " Wins in a Row");
            color = GREEN;
        } else {
            cur = "None";
            color = GRAY;
        }
        return Component.textOfChildren(
                t("Streak: ", GRAY), t(cur, color),
                t("   (Best: " + s.bestWinStreak + ")", GRAY));
    }

    private DialogAction click(LeaderboardHandler handler) {
        DialogActionCallback cb = (DialogResponseView view, Audience audience) -> {
            if (!(audience instanceof Player p)) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (p.isOnline()) handler.run(view, p);
            });
        };
        return DialogAction.customClick(cb, CLICK);
    }

    @FunctionalInterface
    private interface LeaderboardHandler {
        void run(DialogResponseView view, Player player);
    }
}
