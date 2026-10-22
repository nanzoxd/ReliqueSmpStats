package com.relique.onlineapi;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.List;
import java.util.Locale;

/**
 * /reliqueapi reload   (aliases: /rapi, /relique)
 *
 * Re-reads config.yml and applies it live, so config changes (Elo settings, dialog settings,
 * API port / key, autosave interval) need no restart. Works from the console.
 */
public class ReloadCommand implements CommandExecutor, TabCompleter {

    private final OnlineApiPlugin plugin;

    public ReloadCommand(OnlineApiPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("reliqueapi.reload")) {
            sender.sendMessage("§cYou don't have permission to do that.");
            return true;
        }
        if (args.length == 0 || !args[0].equalsIgnoreCase("reload")) {
            sender.sendMessage("§eUsage: §f/" + label + " reload §7- reload config.yml without a restart");
            return true;
        }
        try {
            plugin.reloadAll();
            sender.sendMessage("§aReliqueOnlineAPI config reloaded. §7Stats were not touched.");
        } catch (Throwable t) {
            sender.sendMessage("§cReload failed: " + t + " §7(see console)");
            plugin.getLogger().warning("Reload failed: " + t);
            t.printStackTrace();
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1 && "reload".startsWith(args[0].toLowerCase(Locale.ROOT))
                && sender.hasPermission("reliqueapi.reload")) {
            return List.of("reload");
        }
        return List.of();
    }
}
