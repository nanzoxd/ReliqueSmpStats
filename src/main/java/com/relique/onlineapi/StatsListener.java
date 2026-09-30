package com.relique.onlineapi;

import com.relique.onlineapi.stats.StatsManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class StatsListener implements Listener {

    private static final Pattern SKIN_URL = Pattern.compile("\"SKIN\"\\s*:\\s*\\{\\s*\"url\"\\s*:\\s*\"([^\"]+)\"");

    private final StatsManager stats;
    private final JavaPlugin plugin;

    public StatsListener(StatsManager stats, JavaPlugin plugin) {
        this.stats = stats;
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        stats.onJoin(p.getUniqueId(), p.getName());
        // SkinsRestorer sets the skin at login, but re-check a bit later in case another plugin changes it.
        captureSkin(p);
        Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline()) captureSkin(p); }, 40L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline()) captureSkin(p); }, 200L);
    }

    /** Saves the skin URL the server currently shows for this player, whatever plugin set it. */
    private void captureSkin(Player p) {
        try {
            com.destroystokyo.paper.profile.PlayerProfile profile = p.getPlayerProfile();
            for (com.destroystokyo.paper.profile.ProfileProperty prop : profile.getProperties()) {
                if (!"textures".equals(prop.getName())) continue;
                String json = new String(Base64.getDecoder().decode(prop.getValue()), StandardCharsets.UTF_8);
                Matcher m = SKIN_URL.matcher(json);
                if (m.find()) {
                    stats.setSkin(p.getUniqueId(), p.getName(), m.group(1));
                    return;
                }
            }
        } catch (Throwable ignored) {
            // Not a Paper server, or the profile has no textures — the site falls back to name-based heads.
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        stats.onQuit(e.getPlayer().getUniqueId());
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
                    victim.getUniqueId(), victim.getName(),
                    pvpVerb(victim, killer));

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
            stats.recordEnvironmentalDeath(victim.getUniqueId(), victim.getName(), envCause(victim));
        }
    }

    /** "smashed", "slain", "shot"... — how the killer got them, for the events feed. */
    private String pvpVerb(Player victim, Player killer) {
        EntityDamageEvent last = victim.getLastDamageCause();
        if (last != null) {
            String c = last.getCause().name();
            if (c.equals("PROJECTILE")) return "shot";
            if (c.equals("ENTITY_EXPLOSION") || c.equals("BLOCK_EXPLOSION")) return "blown up";
            if (c.equals("FALL")) return "knocked down";
        }
        String item = killer.getInventory().getItemInMainHand().getType().name();
        if (item.equals("MACE")) return "smashed";
        if (item.endsWith("_SWORD")) return "slain";
        if (item.endsWith("_AXE")) return "cut down";
        if (item.equals("TRIDENT")) return "impaled";
        if (item.equals("BOW") || item.equals("CROSSBOW")) return "shot";
        return "killed";
    }

    /** Human-readable reason for a non-PvP death. */
    private String envCause(Player victim) {
        EntityDamageEvent last = victim.getLastDamageCause();
        if (last == null) return "died";

        if (last instanceof EntityDamageByEntityEvent) {
            Entity damager = ((EntityDamageByEntityEvent) last).getDamager();
            if (damager instanceof Projectile) {
                Object shooter = ((Projectile) damager).getShooter();
                if (shooter instanceof Entity) damager = (Entity) shooter;
            }
            return "was slain by " + pretty(damager.getType().name());
        }

        switch (last.getCause().name()) {
            case "FALL":            return "fell from a high place";
            case "DROWNING":        return "drowned";
            case "LAVA":            return "tried to swim in lava";
            case "FIRE":
            case "FIRE_TICK":
            case "MELTING":         return "burned to death";
            case "HOT_FLOOR":       return "walked into magma";
            case "SUFFOCATION":     return "suffocated in a wall";
            case "STARVATION":      return "starved to death";
            case "VOID":            return "fell out of the world";
            case "LIGHTNING":       return "was struck by lightning";
            case "CONTACT":         return "was pricked by a cactus";
            case "BLOCK_EXPLOSION":
            case "ENTITY_EXPLOSION":return "blew up";
            case "POISON":          return "was poisoned";
            case "MAGIC":           return "was killed by magic";
            case "WITHER":          return "withered away";
            case "FALLING_BLOCK":   return "was crushed by a falling block";
            case "FLY_INTO_WALL":   return "hit a wall too fast";
            case "CRAMMING":        return "was squished";
            case "FREEZE":          return "froze to death";
            case "SUICIDE":         return "took their own life";
            case "WORLD_BORDER":    return "left the world border";
            default:                return "died";
        }
    }

    private String pretty(String enumName) {
        StringBuilder out = new StringBuilder();
        for (String part : enumName.toLowerCase(Locale.ROOT).split("_")) {
            if (part.isEmpty()) continue;
            if (out.length() > 0) out.append(' ');
            out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return out.toString();
    }
}
