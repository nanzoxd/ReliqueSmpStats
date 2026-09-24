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
- `sort` — `elo` (default), `kills`, `deaths`, `kd`, `playtime`, `blocks`
- `order` — `desc` (default) or `asc`
- `limit` — top N results only

Examples:
```
GET /api/stats                          -> everyone, ranked by Elo
GET /api/stats?sort=kills&limit=10      -> top 10 by kills
GET /api/stats?sort=blocks&limit=10     -> top 10 by blocks mined
GET /api/stats?sort=kd&order=asc        -> worst K/D first
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
      "playtime_seconds": 145200
    }
  ]
}
```

Both endpoints send `Access-Control-Allow-Origin: *` so your website can
`fetch()` them directly.

## How the Elo system works

- Every player starts at Elo **700** — the base of the ladder (Rookie). Elo
  can never drop below 700 either.
- Only **PvP kills** move Elo — deaths to fall damage, lava, mobs, etc. still
  count toward your death total and K/D, but never touch rating.
- Standard competitive Elo math: your expected chance to win a given matchup
  is calculated from the rating gap, then rating moves toward the actual
  result by a fixed **K-factor** (default 24).
- **Anti-farming safeguard**: if the same two players trade a kill again
  within 5 minutes (in either direction), it still counts toward
  kills/deaths, but Elo won't move a second time — so two players can't
  camp each other for easy rating.
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

All of this is tunable in `config.yml` (starting/floor Elo, K-factor,
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
