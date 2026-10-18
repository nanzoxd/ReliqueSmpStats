package com.relique.onlineapi.dialog;

import com.relique.onlineapi.stats.StatsManager;
import com.relique.onlineapi.stats.StatsManager.PlayerStats;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * /leaderboard (alias /lb, /elotop)       - open the Elo leaderboard dialog
 * /pvpprofile [player] (alias /pvpstats)  - open a player's stats dialog (default: yourself)
 * /besttier <player> <platform> <tier> <gamemode> [rank] | /besttier <player> clear
 *      - set the "Best PvP Tier" shown on a profile (use _ for spaces, e.g. Sword_Fight)
 */
public final class DialogCommands implements CommandExecutor, TabCompleter {

    private final StatsManager stats;
    private final LeaderboardDialog leaderboard;
    private final ProfileDialog profile;

    public DialogCommands(StatsManager stats, LeaderboardDialog leaderboard, ProfileDialog profile) {
        this.stats = stats;
        this.leaderboard = leaderboard;
        this.profile = profile;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "leaderboard": {
                if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return true; }
                leaderboard.open(p, args.length > 0
                        ? new LeaderboardDialog.State(0, String.join(" ", args))
                        : LeaderboardDialog.State.first());
                return true;
            }
            case "pvpprofile": {
                if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return true; }
                PlayerStats target;
                if (args.length == 0) {
                    target = stats.find(p.getUniqueId());
                } else {
                    target = stats.findByName(args[0]);
                }
                if (target == null) {
                    sender.sendMessage("§cNo stats found for " + (args.length == 0 ? "you yet." : args[0] + "."));
                    return true;
                }
                profile.open(p, target.uuid, LeaderboardDialog.State.first());
                return true;
            }
            case "besttier":
                return bestTier(sender, args);
            default:
                return false;
        }
    }

    private boolean bestTier(CommandSender sender, String[] args) {
        if (!sender.hasPermission("reliqueapi.tier")) {
            sender.sendMessage("§cYou don't have permission to do that.");
            return true;
        }
        if (args.length == 2 && args[1].equalsIgnoreCase("clear")) {
            PlayerStats t = stats.findByName(args[0]);
            if (t == null) { sender.sendMessage("§cNo stats found for " + args[0] + "."); return true; }
            stats.clearTier(t.uuid);
            sender.sendMessage("§aCleared the best tier for §f" + t.name + "§a.");
            return true;
        }
        if (args.length < 4) {
            sender.sendMessage("§7Usage: §f/besttier <player> <platform> <tier> <gamemode> [rank]  §7or §f/besttier <player> clear");
            return true;
        }
        PlayerStats t = stats.findByName(args[0]);
        if (t == null) { sender.sendMessage("§cNo stats found for " + args[0] + "."); return true; }
        String platform = args[1].replace('_', ' ');
        String tier = args[2].replace('_', ' ');
        String mode = args[3].replace('_', ' ');
        String rank = args.length >= 5 ? args[4].replace('_', ' ') : null;
        stats.setTier(t.uuid, platform, tier, mode, rank);
        sender.sendMessage("§aSet §f" + t.name + "§a's best tier: §6" + platform + " " + tier + " §7(" + mode + ")");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        boolean playerArg = (name.equals("pvpprofile") && args.length == 1)
                || (name.equals("besttier") && args.length == 1);
        if (!playerArg) return List.of();
        String prefix = args[0].toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (PlayerStats s : new ArrayList<>(stats.all())) {
            if (s.name != null && s.name.toLowerCase(Locale.ROOT).startsWith(prefix)) out.add(s.name);
            if (out.size() >= 30) break;
        }
        return out;
    }
}
