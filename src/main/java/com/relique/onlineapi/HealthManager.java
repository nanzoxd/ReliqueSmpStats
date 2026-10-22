package com.relique.onlineapi;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Server health: samples TPS, MSPT, RAM, CPU, players, chunks, entities and disk on a timer,
 * keeps a rolling history on disk (plugins/ReliqueOnlineAPI/health-samples.csv), and remembers
 * restarts and downtime across server stops (health-meta.yml).
 *
 * Served as JSON by HealthHttpHandler at /api/health.
 *
 * Note: a Minecraft server has no GPU, so there is no GPU graph. CPU is shown instead.
 */
public class HealthManager implements Listener {

    public static final class Sample {
        public long t;
        public double tps, mspt, cpuProc, cpuSys;
        public long ramUsed, ramMax, diskUsed, diskTotal;
        public int online, max, chunks, entities;

        String toCsv() {
            return t + "," + f(tps) + "," + f(mspt) + "," + ramUsed + "," + ramMax + "," + f(cpuProc) + ","
                    + f(cpuSys) + "," + online + "," + max + "," + chunks + "," + entities + ","
                    + diskUsed + "," + diskTotal;
        }

        static Sample fromCsv(String line) {
            try {
                String[] p = line.trim().split(",");
                if (p.length < 13) return null;
                Sample s = new Sample();
                s.t = Long.parseLong(p[0]);
                s.tps = Double.parseDouble(p[1]);
                s.mspt = Double.parseDouble(p[2]);
                s.ramUsed = Long.parseLong(p[3]);
                s.ramMax = Long.parseLong(p[4]);
                s.cpuProc = Double.parseDouble(p[5]);
                s.cpuSys = Double.parseDouble(p[6]);
                s.online = Integer.parseInt(p[7]);
                s.max = Integer.parseInt(p[8]);
                s.chunks = Integer.parseInt(p[9]);
                s.entities = Integer.parseInt(p[10]);
                s.diskUsed = Long.parseLong(p[11]);
                s.diskTotal = Long.parseLong(p[12]);
                return s;
            } catch (Exception e) {
                return null;
            }
        }

        private static String f(double v) {
            return String.format(Locale.ROOT, "%.2f", v);
        }
    }

    public static final class Downtime {
        public long from, to;
        public boolean clean; // true = the server was stopped normally, false = crash / power loss (time is approximate)
        public long seconds() { return Math.max(0, (to - from) / 1000); }
    }

    private static final long MB = 1024L * 1024L;
    private static final int MAX_DOWNTIMES = 25;

    private final OnlineApiPlugin plugin;
    private final File samplesFile;
    private final File metaFile;
    private final Object fileLock = new Object();
    private final List<Sample> samples = new ArrayList<>();
    private final List<Downtime> downtimes = new ArrayList<>();
    private final long startedAt = ManagementFactory.getRuntimeMXBean().getStartTime();

    private YamlConfiguration meta = new YamlConfiguration();
    private BukkitTask task;
    private volatile boolean enabled = true;
    private volatile int intervalSeconds = 30;
    private volatile int keepHours = 48;
    private int trimmedSinceCompact = 0;

    private volatile int peakSession = 0;
    private volatile long peakSessionAt = 0;
    private volatile int peakAll = 0;
    private volatile long peakAllAt = 0;

    public HealthManager(OnlineApiPlugin plugin) {
        this.plugin = plugin;
        this.samplesFile = new File(plugin.getDataFolder(), "health-samples.csv");
        this.metaFile = new File(plugin.getDataFolder(), "health-meta.yml");
    }

    // ------------------------------------------------------------------ lifecycle

    public void start() {
        readSettings();
        if (!enabled) return;
        plugin.getDataFolder().mkdirs();
        long now = System.currentTimeMillis();
        long lastSampleOnDisk = loadSamples(now);
        loadMeta();
        detectDowntime(now, lastSampleOnDisk);
        schedule();
    }

    /** Called by /reliqueapi reload. */
    public void reload() {
        boolean was = enabled;
        readSettings();
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (enabled && !was) {
            start();
        } else if (enabled) {
            schedule();
        }
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (!enabled) return;
        meta.set("last-stop", System.currentTimeMillis()); // a clean stop: gives an exact "went offline at"
        saveMeta();
        compactNow();
    }

    private void readSettings() {
        enabled = plugin.getConfig().getBoolean("health.enabled", true);
        intervalSeconds = Math.max(5, plugin.getConfig().getInt("health.sample-interval-seconds", 30));
        keepHours = Math.max(1, plugin.getConfig().getInt("health.keep-hours", 48));
    }

    private void schedule() {
        long ticks = intervalSeconds * 20L;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::sampleNow, 20L, ticks);
    }

    public boolean isEnabled() { return enabled; }
    public int getKeepHours() { return keepHours; }

    // ------------------------------------------------------------------ sampling (main thread)

    private void sampleNow() {
        try {
            Sample s = new Sample();
            s.t = System.currentTimeMillis();

            try {
                s.tps = Math.min(20.0, Bukkit.getTPS()[0]);
            } catch (Throwable e) {
                s.tps = 20.0;
            }
            try {
                s.mspt = Bukkit.getAverageTickTime();
            } catch (Throwable e) {
                s.mspt = -1; // not available on this server software
            }

            Runtime rt = Runtime.getRuntime();
            s.ramUsed = rt.totalMemory() - rt.freeMemory();
            s.ramMax = rt.maxMemory();

            s.cpuProc = -1;
            s.cpuSys = -1;
            try {
                java.lang.management.OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
                if (os instanceof com.sun.management.OperatingSystemMXBean x) {
                    double p = x.getProcessCpuLoad();
                    double c = x.getCpuLoad();
                    s.cpuProc = p < 0 ? -1 : p * 100.0;
                    s.cpuSys = c < 0 ? -1 : c * 100.0;
                }
            } catch (Throwable ignored) { /* CPU stats unavailable */ }

            s.online = Bukkit.getOnlinePlayers().size();
            s.max = Bukkit.getMaxPlayers();
            for (World w : Bukkit.getWorlds()) {
                s.chunks += w.getChunkCount();
                s.entities += w.getEntityCount();
            }
            try {
                File root = new File(".").getAbsoluteFile();
                s.diskTotal = root.getTotalSpace();
                s.diskUsed = Math.max(0, s.diskTotal - root.getUsableSpace());
            } catch (Throwable ignored) { /* disk stats unavailable */ }

            updatePeak(s.online, s.t);

            long cutoff = s.t - keepHours * 3600_000L;
            synchronized (samples) {
                samples.add(s);
                while (!samples.isEmpty() && samples.get(0).t < cutoff) {
                    samples.remove(0);
                    trimmedSinceCompact++;
                }
            }
            final String line = s.toCsv();
            final boolean compact = trimmedSinceCompact >= 500;
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                if (compact) compactNow();
                else appendLine(line);
            });
        } catch (Throwable t) {
            plugin.getLogger().warning("Health sample failed: " + t);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (!enabled) return;
        updatePeak(Bukkit.getOnlinePlayers().size(), System.currentTimeMillis());
    }

    private void updatePeak(int online, long now) {
        if (online > peakSession) {
            peakSession = online;
            peakSessionAt = now;
        }
        if (online > peakAll) {
            peakAll = online;
            peakAllAt = now;
            meta.set("peak-all", peakAll);
            meta.set("peak-all-at", peakAllAt);
            saveMeta();
        }
    }

    // ------------------------------------------------------------------ persistence

    private long loadSamples(long now) {
        long last = 0;
        long cutoff = now - keepHours * 3600_000L;
        synchronized (fileLock) {
            if (!samplesFile.exists()) return 0;
            try {
                for (String line : Files.readAllLines(samplesFile.toPath(), StandardCharsets.UTF_8)) {
                    Sample s = Sample.fromCsv(line);
                    if (s == null) continue;
                    last = Math.max(last, s.t);
                    if (s.t >= cutoff) samples.add(s);
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Could not read health history: " + e);
            }
        }
        samples.sort((a, b) -> Long.compare(a.t, b.t));
        return last;
    }

    private void appendLine(String line) {
        synchronized (fileLock) {
            try {
                Files.writeString(samplesFile.toPath(), line + "\n", StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (Exception e) {
                plugin.getLogger().warning("Could not write health history: " + e);
            }
        }
    }

    /** Rewrites the CSV from memory so it never grows past keep-hours. */
    private void compactNow() {
        List<Sample> copy;
        synchronized (samples) {
            copy = new ArrayList<>(samples);
            trimmedSinceCompact = 0;
        }
        StringBuilder sb = new StringBuilder();
        for (Sample s : copy) sb.append(s.toCsv()).append('\n');
        synchronized (fileLock) {
            try {
                Files.writeString(samplesFile.toPath(), sb.toString(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            } catch (Exception e) {
                plugin.getLogger().warning("Could not compact health history: " + e);
            }
        }
    }

    private void loadMeta() {
        meta = metaFile.exists() ? YamlConfiguration.loadConfiguration(metaFile) : new YamlConfiguration();
        peakAll = meta.getInt("peak-all", 0);
        peakAllAt = meta.getLong("peak-all-at", 0);
        downtimes.clear();
        for (Map<?, ?> m : meta.getMapList("downtimes")) {
            try {
                Downtime d = new Downtime();
                d.from = ((Number) m.get("from")).longValue();
                d.to = ((Number) m.get("to")).longValue();
                d.clean = Boolean.TRUE.equals(m.get("clean"));
                downtimes.add(d);
            } catch (Exception ignored) { /* skip a damaged entry */ }
        }
    }

    private synchronized void saveMeta() {
        try {
            List<Map<String, Object>> list = new ArrayList<>();
            for (Downtime d : downtimes) {
                Map<String, Object> m = new HashMap<>();
                m.put("from", d.from);
                m.put("to", d.to);
                m.put("clean", d.clean);
                list.add(m);
            }
            meta.set("downtimes", list);
            meta.save(metaFile);
        } catch (Exception e) {
            plugin.getLogger().warning("Could not save health-meta.yml: " + e);
        }
    }

    /**
     * Works out whether the server was off since we last ran. A normal stop leaves an exact
     * "last-stop" time; after a crash we fall back to the last recorded sample.
     */
    private void detectDowntime(long now, long lastSampleOnDisk) {
        long lastStop = meta.getLong("last-stop", 0);
        long lastAlive = Math.max(lastSampleOnDisk, lastStop);
        if (lastAlive > 0 && now - lastAlive >= 10_000) {
            Downtime d = new Downtime();
            d.from = lastAlive;
            d.to = now;
            d.clean = lastStop > 0 && lastStop >= lastSampleOnDisk;
            downtimes.add(0, d);
            while (downtimes.size() > MAX_DOWNTIMES) downtimes.remove(downtimes.size() - 1);
        }
        meta.set("last-stop", 0);
        saveMeta();
    }

    // ------------------------------------------------------------------ JSON for /api/health

    private static String num(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v) || v < 0) return "null";
        return String.format(Locale.ROOT, "%.2f", v);
    }

    private static String downtimeJson(Downtime d) {
        return "{\"from\":" + d.from + ",\"to\":" + d.to + ",\"seconds\":" + d.seconds()
                + ",\"clean\":" + d.clean + "}";
    }

    /** Average / lowest / highest of one metric over the raw samples (values below 0 mean "unknown"). */
    private static String statJson(List<Sample> list, java.util.function.ToDoubleFunction<Sample> fn) {
        double sum = 0, min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        int n = 0;
        for (Sample s : list) {
            double v = fn.applyAsDouble(s);
            if (v < 0) continue;
            sum += v;
            n++;
            if (v < min) min = v;
            if (v > max) max = v;
        }
        if (n == 0) return "{\"avg\":null,\"min\":null,\"max\":null}";
        return "{\"avg\":" + num(sum / n) + ",\"min\":" + num(min) + ",\"max\":" + num(max) + "}";
    }

    public String toJson(int hours, int maxPoints) {
        long now = System.currentTimeMillis();
        hours = Math.max(1, Math.min(hours, keepHours));
        maxPoints = Math.max(20, Math.min(maxPoints, 1000));
        long since = now - hours * 3600_000L;

        List<Sample> range = new ArrayList<>();
        Sample latest = null;
        synchronized (samples) {
            for (Sample s : samples) if (s.t >= since) range.add(s);
            if (!samples.isEmpty()) latest = samples.get(samples.size() - 1);
        }

        StringBuilder j = new StringBuilder(4096);
        j.append("{\"available\":true,\"now\":").append(now)
                .append(",\"interval_seconds\":").append(intervalSeconds)
                .append(",\"keep_hours\":").append(keepHours);

        j.append(",\"server\":{\"started_at\":").append(startedAt)
                .append(",\"uptime_seconds\":").append(Math.max(0, (now - startedAt) / 1000))
                .append(",\"tick_budget_ms\":50")
                .append(",\"version\":\"").append(esc(Bukkit.getVersion())).append("\"}");

        if (latest != null) {
            j.append(",\"current\":{\"sampled_at\":").append(latest.t)
                    .append(",\"tps\":").append(num(latest.tps))
                    .append(",\"mspt\":").append(num(latest.mspt))
                    .append(",\"ram_used_mb\":").append(latest.ramUsed / MB)
                    .append(",\"ram_max_mb\":").append(latest.ramMax / MB)
                    .append(",\"cpu_process\":").append(num(latest.cpuProc))
                    .append(",\"cpu_system\":").append(num(latest.cpuSys))
                    .append(",\"online\":").append(latest.online)
                    .append(",\"max_players\":").append(latest.max)
                    .append(",\"chunks\":").append(latest.chunks)
                    .append(",\"entities\":").append(latest.entities)
                    .append(",\"disk_used_mb\":").append(latest.diskUsed / MB)
                    .append(",\"disk_total_mb\":").append(latest.diskTotal / MB)
                    .append("}");
        } else {
            j.append(",\"current\":null");
        }

        j.append(",\"peak\":{\"session\":").append(peakSession)
                .append(",\"session_at\":").append(peakSessionAt)
                .append(",\"all_time\":").append(peakAll)
                .append(",\"all_time_at\":").append(peakAllAt).append("}");

        j.append(",\"history\":{\"hours\":").append(hours)
                .append(",\"samples\":").append(range.size())
                .append(",\"oldest\":").append(range.isEmpty() ? 0 : range.get(0).t).append("}");

        j.append(",\"stats\":{")
                .append("\"tps\":").append(statJson(range, s -> s.tps))
                .append(",\"mspt\":").append(statJson(range, s -> s.mspt))
                .append(",\"ram_mb\":").append(statJson(range, s -> (double) s.ramUsed / MB))
                .append(",\"cpu\":").append(statJson(range, s -> s.cpuProc))
                .append(",\"online\":").append(statJson(range, s -> s.online))
                .append("}");

        j.append(",\"downtimes\":[");
        for (int i = 0; i < downtimes.size(); i++) {
            if (i > 0) j.append(",");
            j.append(downtimeJson(downtimes.get(i)));
        }
        j.append("],\"last_downtime\":").append(downtimes.isEmpty() ? "null" : downtimeJson(downtimes.get(0)));

        // Points for the graphs, averaged into buckets so a 24h view stays small.
        j.append(",\"samples\":[");
        int n = range.size();
        int step = Math.max(1, (int) Math.ceil(n / (double) maxPoints));
        boolean first = true;
        for (int i = 0; i < n; i += step) {
            int end = Math.min(n, i + step);
            double tps = 0, mspt = 0, ram = 0, cpu = 0, chunks = 0, ents = 0;
            int msptN = 0, cpuN = 0, online = 0, cnt = end - i;
            for (int k = i; k < end; k++) {
                Sample s = range.get(k);
                tps += s.tps;
                ram += (double) s.ramUsed / MB;
                chunks += s.chunks;
                ents += s.entities;
                online = Math.max(online, s.online); // keep the busiest moment so peaks stay visible
                if (s.mspt >= 0) { mspt += s.mspt; msptN++; }
                if (s.cpuProc >= 0) { cpu += s.cpuProc; cpuN++; }
            }
            if (!first) j.append(",");
            first = false;
            j.append("{\"t\":").append(range.get(end - 1).t)
                    .append(",\"tps\":").append(num(tps / cnt))
                    .append(",\"mspt\":").append(msptN == 0 ? "null" : num(mspt / msptN))
                    .append(",\"ram\":").append(Math.round(ram / cnt))
                    .append(",\"cpu\":").append(cpuN == 0 ? "null" : num(cpu / cpuN))
                    .append(",\"online\":").append(online)
                    .append(",\"chunks\":").append(Math.round(chunks / cnt))
                    .append(",\"entities\":").append(Math.round(ents / cnt))
                    .append("}");
        }
        j.append("]}");
        return j.toString();
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
