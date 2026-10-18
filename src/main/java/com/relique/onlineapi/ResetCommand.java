package com.relique.onlineapi;

import com.relique.onlineapi.stats.StatsManager;
import com.relique.onlineapi.stats.StatsManager.BackupInfo;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * /stats reset all         - wipe every player's stats and the events feed (asks for confirmation)
 * /stats reset <player>    - reset one player back to a fresh start (asks for confirmation)
 * /stats confirm           - confirm the action you were just asked about
 * /stats revert [number]   - restore stats from a backup (default: the newest), asks for confirmation
 * /stats backups           - list saved backups, newest first
 *
 * Every reset writes a backup of stats.yml first (plugins/ReliqueOnlineAPI/backups/),
 * so nothing is ever lost. The old /statsreset command still works as an alias for /stats reset.
 */
public class ResetCommand implements CommandExecutor, TabCompleter {

    private static final long CONFIRM_WINDOW_MS = 30_000L;
    private static final int LIST_LIMIT = 10;

    private static class Pending {
        final String action;      // "reset-all", "reset-player", "revert"
        final String player;      // for reset-player
        final BackupInfo backup;  // for revert
        final long expires;

        Pending(String action, String player, BackupInfo backup) {
            this.action = action;
            this.player = player;
            this.backup = backup;
            this.expires = System.currentTimeMillis() + CONFIRM_WINDOW_MS;
        }
    }

    private final StatsManager stats;
    private final Map<String, Pending> pending = new HashMap<>();

    public ResetCommand(StatsManager stats) {
        this.stats = stats;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("reliqueapi.reset")) {
            sender.sendMessage("§cYou don't have permission to do that.");
            return true;
        }

        // Legacy alias: /statsreset all  ==  /stats reset all
        if (label.equalsIgnoreCase("statsreset")) {
            String[] shifted = new String[args.length + 1];
            shifted[0] = "reset";
            System.arraycopy(args, 0, shifted, 1, args.length);
            args = shifted;
        }

        if (args.length == 0) {
            usage(sender);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reset":   return handleReset(sender, args);
            case "revert":  return handleRevert(sender, args);
            case "confirm": return handleConfirm(sender);
            case "backups": return handleBackups(sender);
            default:
                usage(sender);
                return true;
        }
    }

    private void usage(CommandSender sender) {
        sender.sendMessage("§eUsage:");
        sender.sendMessage("§f/stats reset all §7- wipe everyone (asks to confirm)");
        sender.sendMessage("§f/stats reset <player> §7- reset one player (asks to confirm)");
        sender.sendMessage("§f/stats revert [number] §7- restore a backup (asks to confirm)");
        sender.sendMessage("§f/stats backups §7- list saved backups");
        sender.sendMessage("§f/stats confirm §7- confirm the pending action");
    }

    // ---------------------------------------------------------------- reset

    private boolean handleReset(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§eUsage: §f/stats reset all §7or §f/stats reset <player>");
            return true;
        }
        if (args[1].equalsIgnoreCase("all")) {
            pending.put(key(sender), new Pending("reset-all", null, null));
            sender.sendMessage("§c⚠ This will wipe ALL players' stats and the events feed.");
            sender.sendMessage("§7A backup is saved first, so §f/stats revert §7can bring it back.");
            sender.sendMessage("§eType §f/stats confirm §ewithin 30 seconds to continue.");
            return true;
        }
        String name = args[1];
        if (!stats.hasPlayer(name)) {
            sender.sendMessage("§cNo player named §f" + name + " §cin the stats.");
            return true;
        }
        pending.put(key(sender), new Pending("reset-player", name, null));
        sender.sendMessage("§c⚠ This will reset §f" + name + "§c's stats back to a fresh start.");
        sender.sendMessage("§7A backup is saved first, so §f/stats revert §7can restore them.");
        sender.sendMessage("§eType §f/stats confirm §ewithin 30 seconds to continue.");
        return true;
    }

    // --------------------------------------------------------------- revert

    private boolean handleRevert(CommandSender sender, String[] args) {
        List<BackupInfo> backups = stats.listBackups();
        if (backups.isEmpty()) {
            sender.sendMessage("§cThere are no backups yet. One is created automatically whenever stats are reset.");
            return true;
        }
        int index = 1;
        if (args.length >= 2) {
            try {
                index = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                sender.sendMessage("§cUse a backup number from §f/stats backups§c.");
                return true;
            }
        }
        if (index < 1 || index > backups.size()) {
            sender.sendMessage("§cNo backup #" + index + ". Run §f/stats backups §cto see the list.");
            return true;
        }
        BackupInfo b = backups.get(index - 1);
        pending.put(key(sender), new Pending("revert", null, b));
        sender.sendMessage("§eRestore backup #" + index + ": §f" + b.describe());
        if (b.scope.startsWith("player-")) {
            sender.sendMessage("§7Only that player is restored; everyone else's stats stay as they are.");
        } else {
            sender.sendMessage("§c⚠ Everyone's stats are replaced with what the backup contained.");
        }
        sender.sendMessage("§7The current stats are backed up first, so you can undo this too.");
        sender.sendMessage("§eType §f/stats confirm §ewithin 30 seconds to continue.");
        return true;
    }

    // ------------------------------------------------------------- backups

    private boolean handleBackups(CommandSender sender) {
        List<BackupInfo> backups = stats.listBackups();
        if (backups.isEmpty()) {
            sender.sendMessage("§7No backups yet. One is created automatically whenever stats are reset.");
            return true;
        }
        sender.sendMessage("§eStats backups (newest first), restore with §f/stats revert <number>§e:");
        for (int i = 0; i < backups.size() && i < LIST_LIMIT; i++) {
            sender.sendMessage("§f#" + (i + 1) + " §7" + backups.get(i).describe());
        }
        if (backups.size() > LIST_LIMIT) {
            sender.sendMessage("§7...and " + (backups.size() - LIST_LIMIT) + " older (files are in plugins/ReliqueOnlineAPI/backups/).");
        }
        return true;
    }

    // -------------------------------------------------------------- confirm

    private boolean handleConfirm(CommandSender sender) {
        Pending p = pending.remove(key(sender));
        if (p == null || System.currentTimeMillis() > p.expires) {
            sender.sendMessage("§cNothing to confirm (or it expired). Start again with §f/stats reset §cor §f/stats revert§c.");
            return true;
        }
        switch (p.action) {
            case "reset-all":
                if (stats.resetAll()) {
                    sender.sendMessage("§aAll stats and events have been reset. §7Undo with §f/stats revert§7.");
                } else {
                    sender.sendMessage("§cCould not write the backup, so NOTHING was reset. Check the server console.");
                }
                break;
            case "reset-player":
                if (!stats.hasPlayer(p.player)) {
                    sender.sendMessage("§cNo player named §f" + p.player + " §cin the stats any more.");
                } else if (stats.resetPlayer(p.player)) {
                    sender.sendMessage("§aReset stats for §f" + p.player + "§a. §7Undo with §f/stats revert§7.");
                } else {
                    sender.sendMessage("§cCould not write the backup, so NOTHING was reset. Check the server console.");
                }
                break;
            case "revert":
                if (stats.restore(p.backup)) {
                    sender.sendMessage("§aRestored: §f" + p.backup.describe());
                } else {
                    sender.sendMessage("§cCould not restore that backup (missing/unreadable file or backup failed). Nothing was changed.");
                }
                break;
            default:
                break;
        }
        return true;
    }

    private static String key(CommandSender sender) {
        return sender.getName();
    }

    // ------------------------------------------------------------ tab complete

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (!sender.hasPermission("reliqueapi.reset")) return out;
        if (alias.equalsIgnoreCase("statsreset")) {
            String[] shifted = new String[args.length + 1];
            shifted[0] = "reset";
            System.arraycopy(args, 0, shifted, 1, args.length);
            args = shifted;
        }
        if (args.length == 1) {
            for (String s : new String[]{"reset", "revert", "confirm", "backups"}) {
                if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(s);
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("reset")) {
            if ("all".startsWith(args[1].toLowerCase(Locale.ROOT))) out.add("all");
            for (StatsManager.PlayerStats ps : stats.all()) {
                if (ps.name != null && ps.name.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))) out.add(ps.name);
            }
        }
        return out;
    }
}
