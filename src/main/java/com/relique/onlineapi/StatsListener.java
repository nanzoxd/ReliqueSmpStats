package com.relique.onlineapi;

import com.relique.onlineapi.stats.StatsManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public class StatsListener implements Listener {

    private final StatsManager stats;

    public StatsListener(StatsManager stats) {
        this.stats = stats;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        stats.onJoin(p.getUniqueId(), p.getName());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        stats.onQuit(p.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        stats.recordBlockMined(p.getUniqueId(), p.getName());
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Player victim = e.getEntity();
        Player killer = victim.getKiller();

        if (killer != null && !killer.getUniqueId().equals(victim.getUniqueId())) {
            StatsManager.KillResult r = stats.recordKill(
                    killer.getUniqueId(), killer.getName(),
                    victim.getUniqueId(), victim.getName());

            if (r.applied) {
                killer.sendMessage("§a+" + r.kGain() + " Elo §7for killing §f" + victim.getName()
                        + " §7(now §f" + Math.round(r.killerEloAfter) + " Elo §7— §e" + r.killerRankAfter + "§7)");
                victim.sendMessage("§c" + r.vChange() + " Elo §7for dying to §f" + killer.getName()
                        + " §7(now §f" + Math.round(r.victimEloAfter) + " Elo §7— §e" + r.victimRankAfter + "§7)");

                if ("Combat Grandmaster".equals(r.killerRankAfter)
                        && !"Combat Grandmaster".equals(StatsManager.tierForElo(r.killerEloBefore))) {
                    Bukkit.broadcastMessage("§6★ " + killer.getName() + " has reached §lCombat Grandmaster§r§6! ★");
                }
            } else {
                killer.sendMessage("§7Kill counted, but Elo is on cooldown against " + victim.getName()
                        + " for a bit (no repeat-farming).");
            }
        } else {
            // Environmental/self death — counts toward deaths and K/D, but not Elo.
            stats.recordEnvironmentalDeath(victim.getUniqueId(), victim.getName());
        }
    }
}
