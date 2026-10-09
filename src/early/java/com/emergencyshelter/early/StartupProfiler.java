package com.emergencyshelter.early;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.fml.loading.LoadingModList;
import org.jetbrains.annotations.Nullable;

/**
 * 启动耗时记录：加载期间每 0.2 秒看一眼加载线程在执行哪个模组的代码，统计每个模组"占用"了多久。
 * <ul>
 *     <li>启动成功后与以往正常启动比较，明显变慢时在报告里指出是哪个模组，以及它的配置有没有被改过；</li>
 *     <li>加载过程中每隔几秒把统计写到 {@code loading-profile.json}，如果游戏卡死被强制关掉，下次启动能知道卡在了哪个模组。</li>
 * </ul>
 */
final class StartupProfiler {
    static final String LIVE_FILE = "loading-profile.json";
    static final String HISTORY_FILE = "startup-history.json";
    private static final long INTERVAL_MS = 200;
    private static final long WRITE_EVERY_MS = 5000;
    private static final long RECENT_WINDOW_MS = 60_000;
    private static final Set<String> SYSTEM_MODULES = Set.of("minecraft", "neoforge", "emergencyshelter", "emergencyshelter.early");

    private static volatile boolean running;
    private static volatile boolean finished;
    private static boolean started;
    private static long startMillis;
    private static final Map<String, Long> busy = new HashMap<>();
    /** 最近 60 秒里每次采样涉及的模组，用来判断"卡在了哪里"。 */
    private static final Deque<Sample> recent = new ArrayDeque<>();
    private static final Map<String, String> moduleToMod = new HashMap<>();
    private static final Map<String, String> modNames = new HashMap<>();
    private static boolean modulesMapped;
    @Nullable
    private static Summary result;

    private record Sample(long time, Set<String> mods) {
    }

    private StartupProfiler() {
    }

    /** 加载过程中的统计，也是写入文件的格式。 */
    static final class Live {
        long start;
        long elapsedMs;
        long updated;
        boolean finished;
        Map<String, Long> busyMs = new LinkedHashMap<>();
        Map<String, Long> recentMs = new LinkedHashMap<>();
        Map<String, String> names = new HashMap<>();
        /** 本次加载的模组（排序后的 id）。模组列表不同的启动耗时不能互相比较。 */
        List<String> mods;
    }

    static final class History {
        List<Run> runs = new ArrayList<>();
        /** 连续几次启动明显变慢。连续 3 次就当作新的正常情况（玩家可能有意为之，例如加了很多模组）。 */
        int consecutiveSlow;
    }

    static final class Run {
        long time;
        long totalMs;
        Map<String, Long> busyMs = new HashMap<>();
        /** 旧版本记录没有这一项（null），不参与比较。 */
        List<String> mods;
    }

    /** 给游戏内部分的结果。 */
    static final class Summary {
        long totalMs;
        Long baselineMs;
        boolean slow;
        /** 整体或某个模组明显变慢，值得告诉玩家。 */
        boolean notable;
        /** 已经连续多次这样，这次起按新的正常情况对待。 */
        boolean accepted;
        List<ModTime> mods = new ArrayList<>();
    }

    static final class ModTime {
        String id;
        String name;
        long busyMs;
        Long baselineMs;
        boolean slow;
        List<String> changedConfigs = new ArrayList<>();
    }

    static synchronized void start() {
        if (running || finished) {
            return;
        }
        running = true;
        started = true;
        startMillis = ManagementFactory.getRuntimeMXBean().getStartTime();
        Thread thread = new Thread(StartupProfiler::loop, "EmergencyShelter-StartupProfiler");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }

    private static void loop() {
        ThreadMXBean mx = ManagementFactory.getThreadMXBean();
        long lastWrite = System.currentTimeMillis();
        long markerGone = 0;
        while (running) {
            try {
                Thread.sleep(INTERVAL_MS);
                sample(mx);
                long now = System.currentTimeMillis();
                if (now - lastWrite >= WRITE_EVERY_MS) {
                    lastWrite = now;
                    ShelterFiles.write(LIVE_FILE, live(false));
                    // 正常情况下游戏内部分会通知加载完成；万一没有通知到，启动成功几分钟后自己结束，最多采样 30 分钟
                    if (!java.nio.file.Files.exists(ShelterFiles.file(ShelterFiles.LAUNCH_MARKER))) {
                        markerGone = markerGone == 0 ? now : markerGone;
                    }
                    if ((markerGone != 0 && now - markerGone > 300_000) || now - startMillis > 1_800_000) {
                        finish();
                        return;
                    }
                }
            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {
                EarlyLog.LOG.debug("[紧急避险] 启动耗时采样出错", t);
            }
        }
    }

    private static void sample(ThreadMXBean mx) {
        if (!modulesMapped) {
            mapModules();
        }
        Set<String> mods = new HashSet<>();
        for (ThreadInfo info : mx.dumpAllThreads(false, false, 48)) {
            if (info == null || !isLoadingThread(info.getThreadName())) {
                continue;
            }
            String owner = owner(info.getStackTrace());
            if (owner != null) {
                mods.add(owner);
            }
        }
        long now = System.currentTimeMillis();
        synchronized (StartupProfiler.class) {
            for (String mod : mods) {
                busy.merge(mod, INTERVAL_MS, Long::sum);
            }
            recent.addLast(new Sample(now, mods));
            while (!recent.isEmpty() && now - recent.peekFirst().time() > RECENT_WINDOW_MS) {
                recent.removeFirst();
            }
        }
    }

    /** 参与加载的线程：主线程、渲染线程、服务端线程、NeoForge 的并行加载线程。 */
    private static boolean isLoadingThread(String name) {
        return name.equals("main") || name.equals("Render thread") || name.equals("Server thread")
                || name.startsWith("modloading-worker-") || name.startsWith("Worker-Main-") || name.startsWith("Worker-Bootstrap-");
    }

    /** 从栈顶往下找第一个属于某个模组的栈帧。等待中的线程（栈顶全是 JDK 代码）不算。 */
    @Nullable
    private static String owner(StackTraceElement[] frames) {
        for (StackTraceElement frame : frames) {
            String module = frame.getModuleName();
            if (module == null || module.startsWith("java.") || module.startsWith("jdk.")) {
                continue;
            }
            String mod = moduleToMod.get(module);
            if (mod != null) {
                return mod;
            }
            if (SYSTEM_MODULES.contains(module)) {
                return null; // 原版 / NeoForge 自己的代码
            }
        }
        return null;
    }

    private static void mapModules() {
        LoadingModList list = LoadingModList.get();
        if (list == null) {
            return;
        }
        Path modsDir = FMLPaths.MODSDIR.get().toAbsolutePath().normalize();
        for (var info : list.getModFiles()) {
            try {
                if (info.getMods().isEmpty()) {
                    continue;
                }
                String id = info.getMods().getFirst().getModId();
                if (SYSTEM_MODULES.contains(id)) {
                    continue;
                }
                moduleToMod.put(info.moduleName(), id);
                modNames.put(id, info.getMods().getFirst().getDisplayName());
            } catch (Throwable ignored) {
            }
        }
        modulesMapped = true;
        if (modsDir.getFileSystem() != FileSystems.getDefault()) {
            EarlyLog.LOG.debug("[紧急避险] mods 目录不在默认文件系统中");
        }
    }

    private static synchronized Live live(boolean done) {
        Live live = new Live();
        live.start = startMillis;
        live.updated = System.currentTimeMillis();
        live.elapsedMs = live.updated - startMillis;
        live.finished = done;
        busy.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(30)
                .forEach(e -> live.busyMs.put(e.getKey(), e.getValue()));
        Map<String, Long> recentMs = new HashMap<>();
        for (Sample s : recent) {
            for (String mod : s.mods()) {
                recentMs.merge(mod, INTERVAL_MS, Long::sum);
            }
        }
        recentMs.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(10)
                .forEach(e -> live.recentMs.put(e.getKey(), e.getValue()));
        live.busyMs.keySet().forEach(id -> live.names.put(id, modNames.getOrDefault(id, id)));
        live.recentMs.keySet().forEach(id -> live.names.put(id, modNames.getOrDefault(id, id)));
        live.mods = modulesMapped ? modList() : null;
        return live;
    }

    private static List<String> modList() {
        List<String> mods = new ArrayList<>(modNames.keySet());
        mods.sort(null);
        return mods;
    }

    /** 和本次模组列表相同的历史记录。加了或删了模组之后，以前的耗时不能当作"平时"（否则新装一批模组会被误报成变慢或卡死）。 */
    private static List<Run> comparable(History history, @Nullable List<String> mods) {
        if (mods == null || history.runs == null) {
            return List.of();
        }
        return history.runs.stream().filter(r -> mods.equals(r.mods)).toList();
    }

    /** 某次启动里这个模组用了多久。只记录了前 30 名：没进前 30 的，按第 30 名的时间算（偏大，宁可少报）。 */
    private static long busyOf(Run run, String id) {
        Long ms = run.busyMs.get(id);
        if (ms != null) {
            return ms;
        }
        return run.busyMs.size() >= 30 ? run.busyMs.values().stream().min(Long::compare).orElse(0L) : 0L;
    }

    /** 加载完成：停止采样，与以往比较，写入历史。 */
    static synchronized Summary finish() {
        if (result != null) {
            return result;
        }
        if (!started) {
            result = new Summary(); // 没有启用耗时记录
            return result;
        }
        running = false;
        finished = true;
        Live live = live(true);
        ShelterFiles.write(LIVE_FILE, live);

        History history = ShelterFiles.read(HISTORY_FILE, History.class);
        if (history == null || history.runs == null) {
            history = new History();
            history.runs = new ArrayList<>();
        }
        Summary summary = new Summary();
        summary.totalMs = live.elapsedMs;
        List<Run> previous = comparable(history, live.mods);
        if (previous.size() >= 2) {
            summary.baselineMs = median(previous.stream().map(r -> r.totalMs).toList());
        }
        // 明显变慢：比平时多出 50% 且至少 30 秒
        summary.slow = summary.baselineMs != null && summary.totalMs > summary.baselineMs * 3 / 2 && summary.totalMs - summary.baselineMs > 30_000;

        ConfigGuard.State changes = null;
        for (Map.Entry<String, Long> e : live.busyMs.entrySet()) {
            ModTime mt = new ModTime();
            mt.id = e.getKey();
            mt.name = live.names.getOrDefault(mt.id, mt.id);
            mt.busyMs = e.getValue();
            List<Long> before = previous.stream().map(r -> busyOf(r, mt.id)).toList();
            if (before.size() >= 2) {
                mt.baselineMs = median(before);
                mt.slow = mt.busyMs > mt.baselineMs * 2 && mt.busyMs - mt.baselineMs > 15_000;
            }
            if (mt.slow || (summary.baselineMs == null && mt.busyMs > 60_000)) {
                if (changes == null) {
                    changes = ConfigGuard.compareNow();
                }
                mt.changedConfigs.addAll(ConfigGuard.changedFilesOf(changes, mt.id));
            }
            if (summary.mods.size() < 12) {
                summary.mods.add(mt);
            }
        }

        summary.notable = summary.slow || summary.mods.stream().anyMatch(m -> m.slow);
        Run run = new Run();
        run.time = System.currentTimeMillis();
        run.totalMs = live.elapsedMs;
        run.busyMs.putAll(live.busyMs);
        run.mods = live.mods;
        // 明显异常的那次不计入"平时"，避免一次卡顿拉高基准；但连续 3 次都这样，就接受为新的正常情况
        if (summary.notable && ++history.consecutiveSlow < 3) {
            ShelterFiles.write(HISTORY_FILE, history);
        } else {
            summary.accepted = summary.notable;
            history.consecutiveSlow = 0;
            history.runs.add(run);
            while (history.runs.size() > 5) {
                history.runs.removeFirst();
            }
            ShelterFiles.write(HISTORY_FILE, history);
        }
        EarlyLog.LOG.info("[紧急避险] 本次启动用时 {} 秒{}；占用最多的模组：{}", summary.totalMs / 1000,
                summary.baselineMs == null ? "" : "（平时约 " + summary.baselineMs / 1000 + " 秒）", describeTop(summary));
        result = summary;
        return summary;
    }

    private static String describeTop(Summary summary) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(5, summary.mods.size()); i++) {
            ModTime m = summary.mods.get(i);
            if (i > 0) {
                sb.append("，");
            }
            sb.append(m.name).append(' ').append(m.busyMs / 1000).append('s');
        }
        return sb.length() == 0 ? "无" : sb.toString();
    }

    private static long median(List<Long> values) {
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(Long::compare);
        return sorted.get(sorted.size() / 2);
    }

    /**
     * 上次启动没有完成、也没有崩溃报告时：看看是不是卡死在某个模组里了。
     * 条件：已经加载了足够久（比平时多一倍，没有记录时至少 4 分钟），而且最后 60 秒里该模组几乎一直在运行。
     */
    @Nullable
    static String[] hungMod(long markerTime) {
        Live live = ShelterFiles.read(LIVE_FILE, Live.class);
        if (live == null || live.finished || live.updated < markerTime || live.recentMs == null || live.recentMs.isEmpty()) {
            return null;
        }
        History history = ShelterFiles.read(HISTORY_FILE, History.class);
        long threshold = 240_000;
        List<Run> previous = history == null ? List.of() : comparable(history, live.mods);
        if (previous.size() >= 2) {
            threshold = Math.max(90_000, median(previous.stream().map(r -> r.totalMs).toList()) * 2);
        }
        if (live.elapsedMs < threshold) {
            return null;
        }
        Map.Entry<String, Long> top = live.recentMs.entrySet().stream().max(Map.Entry.comparingByValue()).orElse(null);
        if (top == null || top.getValue() < RECENT_WINDOW_MS * 8 / 10) {
            return null;
        }
        String name = live.names == null ? top.getKey() : live.names.getOrDefault(top.getKey(), top.getKey());
        return new String[]{top.getKey(), name, String.valueOf(live.elapsedMs / 1000)};
    }
}
