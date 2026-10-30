package com.relique.onlineapi.dialog;

import com.relique.onlineapi.OnlineApiPlugin;
import com.relique.onlineapi.stats.StatsManager;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;

/** Wires up the leaderboard/profile dialogs, their commands and the PlaceholderAPI expansion. */
public final class Dialogs {

    private static Object expansion; // StatsPlaceholders, kept as Object so PlaceholderAPI stays optional

    private Dialogs() {}

    public static void init(OnlineApiPlugin plugin, StatsManager stats) {
        Ui.configureZone(plugin.getConfig().getString("dialogs.timezone", ""));

        try {
            ProfileDialog profile = new ProfileDialog(plugin, stats);
            LeaderboardDialog leaderboard = new LeaderboardDialog(plugin, stats, profile);
            profile.setLeaderboard(leaderboard);
            RankedLookup ranked = plugin.getRankedLookup(); // null when ranked.enabled is false
            RankedPlayersDialog rankedPlayers = (ranked != null
                    && plugin.getConfig().getBoolean("ranked.show-all-players", true))
                    ? new RankedPlayersDialog(plugin, stats, ranked, profile) : null;
            DialogCommands cmds = new DialogCommands(stats, leaderboard, profile, rankedPlayers);
            for (String name : new String[]{"leaderboard", "pvpprofile", "besttier", "rankedplayers"}) {
                PluginCommand c = plugin.getCommand(name);
                if (c != null) {
                    c.setExecutor(cmds);
                    c.setTabCompleter(cmds);
                }
            }
            plugin.getLogger().info("Leaderboard dialogs ready (/leaderboard, /pvpprofile"
                    + (rankedPlayers != null ? ", /rankedplayers" : "") + ")."
                    + (Ui.INLINE_HEADS ? "" : " Inline heads unavailable on this server version."));
        } catch (Throwable t) {
            plugin.getLogger().warning("Dialog API is not available (needs Paper 1.21.6 or newer): " + t);
        }

        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            try {
                StatsPlaceholders ph = new StatsPlaceholders(stats, plugin.getDescription().getVersion(), plugin.getRankedLookup());
                ph.register();
                expansion = ph;
                plugin.getLogger().info("PlaceholderAPI expansion registered (%relique_...%).");
            } catch (Throwable t) {
                plugin.getLogger().warning("Could not register PlaceholderAPI expansion: " + t);
            }
        }
    }

    public static void shutdown() {
        try {
            if (expansion instanceof StatsPlaceholders ph) ph.unregister();
        } catch (Throwable ignored) { /* shutting down anyway */ }
        expansion = null;
    }
}
