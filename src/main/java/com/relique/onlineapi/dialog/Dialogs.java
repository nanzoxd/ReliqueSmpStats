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
            DialogCommands cmds = new DialogCommands(stats, leaderboard, profile);
            for (String name : new String[]{"leaderboard", "pvpprofile", "besttier"}) {
                PluginCommand c = plugin.getCommand(name);
                if (c != null) {
                    c.setExecutor(cmds);
                    c.setTabCompleter(cmds);
                }
            }
            plugin.getLogger().info("Leaderboard dialogs ready (/leaderboard, /pvpprofile)."
                    + (Ui.INLINE_HEADS ? "" : " Inline heads unavailable on this server version."));
        } catch (Throwable t) {
            plugin.getLogger().warning("Dialog API is not available (needs Paper 1.21.6 or newer): " + t);
        }

        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            try {
                StatsPlaceholders ph = new StatsPlaceholders(stats, plugin.getDescription().getVersion());
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
