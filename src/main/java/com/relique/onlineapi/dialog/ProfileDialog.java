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
import net.kyori.adventure.text.JoinConfiguration;
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

        // title: [head] Name, like the reference's icon + bold name
        Component title = Component.textOfChildren(inlineHead(s.uuid, name, s.skinUrl), t(" ", WHITE), bold(name, GOLD));

        // status line under the title
        List<Component> sub = new ArrayList<>();
        sub.add(t(online ? "Online" : "Offline", online ? GREEN : GRAY));
        if (smpRank > 0) sub.add(t("SMP Rank #" + smpRank, SKY));
        if (s.premium) sub.add(t("[PREMIUM]", GOLD));
        Component subLine = Component.empty();
        for (int i = 0; i < sub.size(); i++) {
            if (i > 0) subLine = subLine.append(t(" · ", BLUE));
            subLine = subLine.append(sub.get(i));
        }
        body.add(line(subLine));
        body.add(rule());

        // rating / record / position / streak: one block
        body.add(line(lines(
                kvLine("Rating", num(Math.round(s.elo)), GOLD)
                        .append(t(" (Peak: " + num(Math.round(Math.max(s.peakElo, s.elo))) + ")", ORANGE)),
                Component.textOfChildren(t("Record: ", GRAY), t(wins + "W", GREEN), t(" / ", GRAY),
                        t(losses + "L", RED), t(" (" + winRate + " WR)", GRAY)),
                kvLine("Position", position > 0 ? "#" + position : NA, GOLD),
                streakLine(s))));
        body.add(rule());

        // statistics: heading + rows in one block
        body.add(line(lines(
                bold("PLAYER STATISTICS", PURPLE),
                Component.textOfChildren(kvLine("Kills", num(s.kills), WHITE), sep(),
                        kvLine("Deaths", num(s.deaths), WHITE), sep(),
                        kvLine("K/D", String.format("%.2f", s.kd()), WHITE)),
                Component.textOfChildren(kvLine("Playtime", playtime(playtime), WHITE), sep(),
                        kvLine("Blocks Mined", num(s.blocksMined), WHITE)),
                Component.textOfChildren(
                        kvLine("SMP Rank", smpRank > 0 ? "#" + smpRank : NA, smpRank > 0 ? SKY : GRAY), sep(),
                        kvLine("First Joined", date(firstJoined), WHITE)),
                kvLine("Last Seen", lastSeen, online ? GREEN : WHITE))));
        body.add(rule());

        // best tier
        List<Component> tier = new ArrayList<>();
        tier.add(bold("BEST TIER", PURPLE));
        if (s.tierBest == null || s.tierBest.isBlank()) {
            tier.add(t("Not ranked", GRAY));
        } else {
            tier.add(t(orNa(s.tierPlatform), WHITE));
            tier.add(Component.textOfChildren(kvLine("Best Tier", s.tierBest, GOLD), sep(),
                    kvLine("Gamemode", orNa(s.tierMode), YELLOW)));
            if (s.tierRank != null && !s.tierRank.isBlank()) tier.add(kvLine("Tier Rank", s.tierRank, WHITE));
        }
        body.add(line(Component.join(JoinConfiguration.newlines(), tier)));
        body.add(rule());

        // recent fight (exactly one)
        List<Component> fight = new ArrayList<>();
        fight.add(bold("RECENT FIGHT", PURPLE));
        if (s.fights.isEmpty()) {
            fight.add(t("No fights recorded", GRAY));
        } else {
            Fight f = s.fights.get(0); // newest first
            PlayerStats opp = stats.findByName(f.opponent);
            TextColor resultColor = f.win ? GREEN : RED;
            String change = f.eloChange > 0 ? "+" + f.eloChange : String.valueOf(f.eloChange);
            TextColor changeColor = f.eloChange > 0 ? GREEN : (f.eloChange < 0 ? RED : GRAY);
            fight.add(Component.textOfChildren(
                    inlineHead(opp == null ? null : opp.uuid, f.opponent, opp == null ? null : opp.skinUrl),
                    t(" " + f.opponent, WHITE), t("   " + (f.win ? "WIN" : "LOSS"), resultColor)));
            fight.add(Component.textOfChildren(
                    t(f.opponentElo >= 0 ? num(f.opponentElo) + " Elo" : NA + " Elo", GOLD),
                    t("   " + change + " Elo", changeColor)));
            fight.add(t(dateTime(f.time), GRAY));
        }
        body.add(line(Component.join(JoinConfiguration.newlines(), fight)));

        // ------------------------------------------------------------ bottom buttons (only these two)
        ActionButton backBtn = ActionButton.create(t("← Back to Leaderboard", WHITE), null, 150,
                click((view, p) -> leaderboard.open(p, back == null ? LeaderboardDialog.State.first() : back)));
        ActionButton closeBtn = ActionButton.create(t("✖ Close", CLOSE_RED), null, 150, null);

        Dialog dialog = Dialog.create(b -> b.empty()
                .base(DialogBase.builder(title)
                        .canCloseWithEscape(true)
                        .body(body)
                        .build())
                .type(DialogType.confirmation(backBtn, closeBtn)));
        viewer.showDialog(dialog);
    }

    // ------------------------------------------------------------ helpers

    private DialogBody rule() {
        return DialogBody.plainMessage(divider(bodyWidth), bodyWidth);
    }

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
