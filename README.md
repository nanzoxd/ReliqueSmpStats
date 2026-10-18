# Relique Online API

A small Paper/Spigot plugin that exposes live player data as JSON — no
external plugins or database needed for a basic leaderboard.

## Endpoints

### `GET /api/online`
Everyone currently online (bypasses the ~12-name cap of the normal server
status ping).

```json
{
  "online": 2,
  "max": 100,
  "players": [
    { "name": "Steve", "uuid": "069a79f4-44e9-4726-a5be-fca90e38aaf5" },
    { "name": "Alex",  "uuid": "ec561538-f3fd-461d-aff5-086b6947c566" }
  ]
}
```

### `GET /api/stats`
Every player the plugin has ever seen (online or not), with everything a
leaderboard needs: **Elo, kills, deaths, K/D, playtime, blocks mined**.

Query params (all optional):
- `sort` — `elo` (default), `kills`, `deaths`, `kd`, `playtime`, `blocks`, `joined`
- `order` — `desc` (default) or `asc`
- `limit` — top N results only

Examples:
```
GET /api/stats                          -> everyone, ranked by Elo
GET /api/stats?sort=kills&limit=10      -> top 10 by kills
GET /api/stats?sort=blocks&limit=10     -> top 10 by blocks mined
GET /api/stats?sort=kd&order=asc        -> worst K/D first
GET /api/stats?sort=joined&order=asc    -> everyone, oldest join first
```

```json
{
  "players": [
    {
      "name": "Steve",
      "uuid": "069a79f4-44e9-4726-a5be-fca90e38aaf5",
      "elo": 1614,
      "rank": "Combat Specialist",
      "kills": 37,
      "deaths": 19,
      "kd": 1.95,
      "blocks_mined": 5820,
      "playtime_seconds": 145200,
      "first_joined": 1727740800000
    }
  ]
}
```

`first_joined` is an epoch-millis timestamp for the first time the plugin
ever saw that player. Existing players (from before this field existed) get
backfilled with the moment this update first runs, not a fake old date.

### `GET /api/teams`
Live team data read straight from the **BetterTeams** plugin, if it's
installed and enabled. This endpoint always responds (never 500s) — if
BetterTeams isn't on the server, or its version doesn't match what this
plugin expects, you just get `"available": false`.

```json
{
  "available": true,
  "teams": [
    {
      "name": "hesw",
      "tag": "HESW",
      "members": [
        { "name": "NotmyaltBG", "uuid": "069a79f4-44e9-4726-a5be-fca90e38aaf5", "rank": "OWNER", "online": false }
      ]
    }
  ]
}
```

`rank` is one of `OWNER`, `ADMIN`, `DEFAULT` (BetterTeams' own ranks).

Both stats-style endpoints send `Access-Control-Allow-Origin: *` so your
website can `fetch()` them directly.

## How the Elo system works

- Every player starts at Elo **700** — the base of the ladder (Rookie). Elo
  can never drop below 700 either.
- Only **PvP kills** move Elo — deaths to fall damage, lava, mobs, etc. still
  count toward your death total and K/D, but never touch rating.
- Every kill moves Elo by **10 to 40 points** (`elo.min-change` /
  `elo.max-change`), never 0. The rating gap decides where in that range it
  lands: beating a much stronger player pays ~40, beating a much weaker one
  pays 10. Losing to a weaker player costs ~40, losing to a stronger one ~10.
- **Anti-farming safeguard**: if the same two players trade a kill again
  within 60 seconds (in either direction), it still counts toward
  kills/deaths, but Elo won't move a second time — so two players can't
  camp each other for easy rating.
- **No armor, no Elo**: if the victim had no armor piece at all (worn or in
  their inventory), the kill still counts for kills/deaths but neither player's
  Elo moves, so naked players can't be farmed.
- **Every kill tells both players exactly what happened** — the killer sees
  their Elo gain and new rank in chat, the victim sees their loss and new
  rank. If the kill was on cooldown (anti-farm), the killer is told the kill
  counted but Elo didn't move.
- **Playtime also earns Elo** — 5 points (configurable) for every full hour
  a player is online, paid out automatically and announced in chat
  (`elo.playtime-reward-per-hour` in `config.yml`, set to `0` to turn it
  off). This is separate from PvP — playtime Elo alone will never earn
  someone Combat Grandmaster, since that still requires an actual PvP
  record (see below).

All of this is tunable in `config.yml` (starting/floor Elo, min/max Elo per kill,
farm-cooldown window, Grandmaster slot count, playtime reward rate).

## Rank tiers

Each player's Elo maps to a rank, included as `"rank"` in `/api/stats`.
**Combat Grandmaster is not a fixed Elo number** — it's reserved for only the
top 2 players server-wide (configurable via `elo.grandmaster-slots`), and
only among players already above the Combat Master threshold. Everyone else
caps out at Combat Master no matter how high their Elo climbs, until one of
those 2 seats opens up (someone gets overtaken).

| Rank | Elo range |
|---|---|
| Rookie | 700 – 949 |
| Combat Novice | 950 – 1199 |
| Combat Cadet | 1200 – 1449 |
| Combat Specialist | 1450 – 1699 |
| Combat Ace | 1700 – 1949 |
| Combat Master | 1950+ |
| Combat Grandmaster | top 2 players only, among those 1950+ |

New players start at 700 Elo, so everyone begins as a **Rookie**. The
thresholds and Grandmaster slot count live in `config.yml` / the `TIERS`
array in `StatsManager.java` — send me new numbers any time and I'll adjust.

## In-game leaderboard and profile (Dialog API)

Needs **Paper 1.21.6+** (built against 1.21.11). Players run:

| Command | What it does |
| --- | --- |
| `/leaderboard [search]` (`/lb`, `/elotop`) | Elo leaderboard: 12 per page, search box, Previous/Next, My Position, Find Player, Close |
| `/pvpprofile [player]` (`/pvpstats`) | One-page profile: rating, record, position, streak, stats, best tier, latest fight |
| `/besttier <player> <platform> <tier> <gamemode> [rank]` | Staff: set the Best PvP Tier shown on a profile (`_` = space). `/besttier <player> clear` removes it |

Clicking a leaderboard row opens that player's profile; **Back to Leaderboard** returns to the same
page and search. Anything the plugin has no data for shows `N/A` (best tier shows `Not ranked`).
Wins = PvP kills, Losses = deaths to another player, SMP Rank = the RankedSMPX rank number.
Settings live under `dialogs:` in `config.yml` (page size, widths, time zone, premium permission).

## PlaceholderAPI placeholders (identifier `relique`)

Per player: `%relique_elo%` `%relique_peak_elo%` `%relique_position%` `%relique_wins%` `%relique_losses%`
`%relique_winrate%` `%relique_kills%` `%relique_deaths%` `%relique_kd%` `%relique_streak%`
`%relique_best_streak%` `%relique_playtime%` `%relique_playtime_hours%` `%relique_blocks%`
`%relique_smp_rank%` `%relique_first_joined%` `%relique_last_seen%` `%relique_premium%`
`%relique_tier_platform%` `%relique_tier_best%` `%relique_tier_mode%` `%relique_tier_rank%`

Leaderboard: `%relique_top_<n>_name%` `%relique_top_<n>_elo%` `%relique_top_<n>_kills%`
`%relique_top_<n>_deaths%` `%relique_top_<n>_kd%` `%relique_total_players%`

## Storage

Stats persist in `plugins/ReliqueOnlineAPI/stats.yml`, autosaved on the
interval set in `config.yml` (default every 5 minutes) and on shutdown. No
external database required.

---

## Turning this project into a `.jar`

**Important: you cannot just rename the `.zip` to `.jar`.** This zip is the
plugin's *source code* — it has to be compiled first. There are two ways to
do that:

### Option A — Build it yourself (needs Java + Maven installed)

1. Install Java 17+ and [Maven](https://maven.apache.org/download.cgi).
2. Unzip this project.
3. Open a terminal in the unzipped folder and run:
   ```bash
   mvn package
   ```
4. The compiled plugin appears at `target/relique-online-api.jar`.

If it fails to compile, it's almost always the `paper-api` version in
`pom.xml` not matching your server — open it and change the `<version>` to
match your Minecraft version (e.g. `1.19.4-R0.1-SNAPSHOT`), then run
`mvn package` again.

### Option B — Build it in the cloud, no install needed (recommended if you don't have Java/Maven)

This project already includes a GitHub Actions workflow that builds the jar
for you automatically:

1. Create a free GitHub account if you don't have one, and create a new
   repository.
2. Upload the contents of this unzipped folder to that repository (GitHub's
   web "Add file → Upload files" works fine, no git command line needed).
3. Go to the repo's **Actions** tab — a build will start automatically
   (or click **Run workflow** if it doesn't).
4. When it finishes (green check), open that run and download the
   **relique-online-api-jar** artifact — that's your `.jar`, ready to drop
   into `plugins/`.

## Install

1. Put the built jar in your server's `plugins/` folder.
2. Restart the server.
3. Edit `plugins/ReliqueOnlineAPI/config.yml` to set your port, optional
   API key, and Elo tuning.
4. Forward/open that port (separately from your Minecraft port).

## Important: HTTPS

If your website is `https://`, browsers block a plain `http://` request to
this API (mixed content). Put the port behind HTTPS via a reverse proxy
(nginx/Caddy + Let's Encrypt) or a tunnel like Cloudflare Tunnel, then send
me the final `https://` URL (and API key, if set) and I'll wire the site's
leaderboard up to it.

## Resetting and reverting stats

Requires the `reliqueapi.reset` permission (OP by default).

| Command | What it does |
| --- | --- |
| `/stats reset all` | Asks to wipe everyone's stats and the events feed |
| `/stats reset <player>` | Asks to reset one player |
| `/stats confirm` | Confirms the pending action (must be within 30 seconds) |
| `/stats backups` | Lists saved backups, newest first |
| `/stats revert [number]` | Asks to restore a backup (default: newest) |

Every reset saves a copy of `stats.yml` to `plugins/ReliqueOnlineAPI/backups/`
first, and if the backup can't be written the reset is cancelled. A backup made
by a single-player reset only restores that player, so everyone else's progress
since then is kept. Reverting also backs up the current stats first, so a revert
can be undone too. The old `/statsreset` command still works as an alias for
`/stats reset`. Backups are never deleted automatically.
