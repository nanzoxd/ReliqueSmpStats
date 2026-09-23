package com.relique.onlineapi;

import com.relique.onlineapi.stats.StatsManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
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

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Player victim = e.getEntity();
        Player killer = victim.getKiller();
        if (killer != null && !killer.getUniqueId().equals(victim.getUniqueId())) {
            // A real PvP kill — this is the only path that touches Elo.
            stats.recordKill(killer.getUniqueId(), killer.getName(), victim.getUniqueId(), victim.getName());
        } else {
            // Environmental/self death — counts toward deaths and K/D, but not Elo.
            stats.recordEnvironmentalDeath(victim.getUniqueId(), victim.getName());
        }
    }
}
