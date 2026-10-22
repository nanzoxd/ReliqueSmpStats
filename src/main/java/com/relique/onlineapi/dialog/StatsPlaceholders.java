package com.relique.onlineapi.dialog;

import com.relique.onlineapi.stats.StatsManager;
import com.relique.onlineapi.stats.StatsManager.PlayerStats;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

import java.util.List;
import java.util.Locale;

/**
 * PlaceholderAPI expansion, identifier "relique". Works for offline players too.
 *
 * Per player:   %relique_elo% %relique_peak_elo% %relique_position% %relique_wins% %relique_losses%
 *               %relique_winrate% %relique_kills% %relique_deaths% %relique_kd% %relique_streak%
 *               %relique_best_streak% %relique_playtime% %relique_playtime_hours% %relique_blocks%
 *               %relique_first_joined% %relique_last_seen% %relique_premium%
 *               %relique_tier_platform% %relique_tier_best% %relique_tier_mode% %relique_tier_rank%
 * Leaderboard:  %relique_top_1_name% %relique_top_1_elo% %relique_top_1_kills% %relique_top_1_kd%
 *               %relique_total_players%
 * Anything unknown prints N/A (never an invented value).
 */
public final class StatsPlaceholders extends PlaceholderExpansion {

    private static final String NA = "N/A";

    private final StatsManager stats;
    private final String version;

    public StatsPlaceholders(StatsManager stats, String version) {
        this.stats = stats;
        this.version = version;
    }

    @Override public String getIdentifier() { return "relique"; }
    @Override public String getAuthor() { return "Relique"; }
    @Override public String getVersion() { return version; }
    @Override public boolean persist() { return true; }
    @Override public boolean canRegister() { return true; }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        String p = params.toLowerCase(Locale.ROOT);

        if (p.equals("total_players")) return String.valueOf(stats.leaderboard().size());

        if (p.startsWith("top_")) {
            // top_<n>_<field>
            String[] parts = p.split("_", 3);
            if (parts.length < 3) return NA;
            int n;
            try { n = Integer.parseInt(parts[1]); } catch (NumberFormatException e) { return NA; }
            List<PlayerStats> lb = stats.leaderboard();
            if (n < 1 || n > lb.size()) return NA;
            PlayerStats s = lb.get(n - 1);
            switch (parts[2]) {
                case "name":  return s.name == null ? NA : s.name;
                case "elo":   return String.valueOf(Math.round(s.elo));
                case "kills": return String.valueOf(s.kills);
                case "deaths":return String.valueOf(s.deaths);
                case "kd":    return String.format("%.2f", s.kd());
                default:      return NA;
            }
        }

        if (player == null) return NA;
        PlayerStats s = stats.find(player.getUniqueId());
        if (s == null) return NA;

        int wins = s.kills;
        int losses = s.pvpDeaths;
        switch (p) {
            case "elo":            return String.valueOf(Math.round(s.elo));
            case "peak_elo":       return String.valueOf(Math.round(Math.max(s.peakElo, s.elo)));
            case "position": {
                int pos = stats.positionOf(s.uuid);
                return pos > 0 ? String.valueOf(pos) : NA;
            }
            case "wins":           return String.valueOf(wins);
            case "losses":         return String.valueOf(losses);
            case "winrate":        return (wins + losses) == 0 ? NA : String.valueOf(Math.round(100.0 * wins / (wins + losses)));
            case "kills":          return String.valueOf(s.kills);
            case "deaths":         return String.valueOf(s.deaths);
            case "kd":             return String.format("%.2f", s.kd());
            case "streak":         return String.valueOf(s.winStreak);
            case "best_streak":    return String.valueOf(s.bestWinStreak);
            case "blocks":         return String.valueOf(s.blocksMined);
            case "playtime":       return Ui.playtime(livePlaytime(s));
            case "playtime_hours": return String.valueOf(livePlaytime(s) / 3600);
            case "first_joined":   return Ui.date(s.firstJoined);
            case "last_seen":      return player.isOnline() ? "Online" : Ui.dateTime(s.lastSeen);
            case "premium":        return s.premium ? "yes" : "no";
            case "tier_platform":  return orNa(s.tierPlatform);
            case "tier_best":      return orNa(s.tierBest);
            case "tier_mode":      return orNa(s.tierMode);
            case "tier_rank":      return orNa(s.tierRank);
            default:               return null; // not ours
        }
    }

    private static long livePlaytime(PlayerStats s) {
        return s.playtimeSeconds + (s.sessionStart == 0 ? 0 : (System.currentTimeMillis() - s.sessionStart) / 1000L);
    }

    private static String orNa(String s) {
        return (s == null || s.isBlank()) ? NA : s;
    }
}
