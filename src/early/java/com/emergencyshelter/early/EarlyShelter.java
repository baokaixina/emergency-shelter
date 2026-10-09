package com.emergencyshelter.early;

import cpw.mods.niofs.union.UnionFileSystem;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.fml.loading.LibraryFinder;
import net.neoforged.fml.loading.ModDirTransformerDiscoverer;
import net.neoforged.neoforgespi.ILaunchContext;
import net.neoforged.neoforgespi.locating.IDiscoveryPipeline;
import net.neoforged.neoforgespi.locating.IncompatibleFileReporting;
import net.neoforged.neoforgespi.locating.ModFileDiscoveryAttributes;
import org.apache.maven.artifact.versioning.ArtifactVersion;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.jetbrains.annotations.Nullable;

/**
 * 紧急避险的启动前检查。在 NeoForge 扫描 mods 文件夹之前运行：
 * 推算哪些模组会导致无法启动，把它们"提前认领"掉，NeoForge 就会跳过它们（文件本身不做任何改动）。
 */
public final class EarlyShelter {
    static final String EMBEDDED_MOD = "META-INF/emergencyshelter/emergencyshelter-mod.jar";
    /** 分析规则有变化时改一下，让旧的缓存结论失效。 */
    private static final String ANALYSIS_VERSION = "analysis-2";
    private static boolean ran;

    private EarlyShelter() {
    }

    static synchronized void locate(ILaunchContext ctx, IDiscoveryPipeline pipeline) {
        if (ran) {
            return;
        }
        ran = true;
        long start = System.nanoTime();
        try {
            analyzeAndClaim(ctx, pipeline);
        } catch (Throwable t) {
            // 出任何意外都退回到"什么也不做"，绝不能让本模组自己成为无法启动的原因
            EarlyLog.LOG.error("[紧急避险] 启动前检查出现意外错误，本次不做任何干预", t);
        }
        try {
            addEmbeddedMod(pipeline);
        } catch (Throwable t) {
            EarlyLog.LOG.error("[紧急避险] 无法加载游戏内部分", t);
        }
        EarlyLog.LOG.info("[紧急避险] 启动前检查完成，用时 {} ms", (System.nanoTime() - start) / 1_000_000);
    }

    private static void analyzeAndClaim(ILaunchContext ctx, IDiscoveryPipeline pipeline) throws Exception {
        ShelterSettings settings = ShelterSettings.load();
        LaunchReport report = new LaunchReport();
        report.side = FMLLoader.getDist().name();

        Path marker = ShelterFiles.file(ShelterFiles.LAUNCH_MARKER);
        boolean previousFailed = Files.exists(marker);
        long markerTime = previousFailed ? Files.getLastModifiedTime(marker).toMillis() : 0L;
        writeMarker();
        LaunchMonitor.install();

        if (!settings.enabled) {
            report.notes.add("紧急避险已在 emergencyshelter/settings.json 中关闭，本次不做任何干预。");
            if (settings.problem != null) {
                report.notes.add(settings.problem);
            }
            ShelterFiles.write(ShelterFiles.REPORT, report);
            return;
        }
        if (settings.startupProfiler) {
            StartupProfiler.start();
        }

        // 只和本次启动有关的提示，不写进缓存
        LaunchReport transientNotes = new LaunchReport();
        if (settings.problem != null) {
            transientNotes.notes.add(settings.problem);
        }
        // 配置保护必须在任何模组读取配置之前完成
        ConfigGuard.State configState = new ConfigGuard.State();
        if (settings.configGuard) {
            try {
                configState = ConfigGuard.checkAtLaunch(settings, transientNotes);
            } catch (Throwable t) {
                EarlyLog.LOG.error("[紧急避险] 检查配置文件时出错，本次跳过配置保护", t);
            }
        }
        // 光影、资源包引起的客户端崩溃：必须在游戏读取 options.txt 和光影设置之前处理
        boolean graphicsHandled = false;
        if (FMLLoader.getDist().isClient()) {
            try {
                graphicsHandled = GraphicsGuard.checkAtLaunch(settings, transientNotes);
            } catch (Throwable t) {
                EarlyLog.LOG.error("[紧急避险] 检查光影和资源包时出错，本次跳过", t);
            }
        }
        WorldSessionCheck.Session worldSession = WorldSessionCheck.take();
        boolean worldCrash = worldSession != null && worldSession.crashedOnEntry();

        Path modsDir = FMLPaths.MODSDIR.get();
        List<Path> jars = listJars(modsDir, ctx);
        Map<String, String> stamps = new TreeMap<>();
        for (Path jar : jars) {
            stamps.put(jar.getFileName().toString(), stamp(jar));
        }
        Set<String> stampSet = new HashSet<>();
        stamps.forEach((name, s) -> stampSet.add(name + "|" + s));

        HealState heal = ShelterFiles.read(ShelterFiles.HEAL_STATE, HealState.class);
        if (heal == null) {
            heal = new HealState();
        }
        heal.fixNulls();
        if (!previousFailed) {
            // 上次启动成功了：之前的配置恢复、卡顿记录都已经起了作用
            heal.consecutiveHeals = 0;
            heal.configRolledBack.clear();
            heal.configRolledBackAll = false;
            heal.hangs.clear();
        }
        if (worldSession == null) {
            heal.worldRollbacks.clear(); // 上次正常退出了存档
        }
        expireHealEntries(heal, stampSet, transientNotes);
        if (worldSession != null) {
            WorldSessionCheck.handleHang(worldSession, settings, heal, configState, transientNotes);
        }

        Set<String> neverDisable = new HashSet<>(settings.neverDisable);
        AnalysisCache cache = ShelterFiles.read(ShelterFiles.ANALYSIS_CACHE, AnalysisCache.class);
        String fingerprint = fingerprint(stamps, heal, neverDisable);

        List<String> claimed;
        if (!previousFailed && !worldCrash && cache != null && cache.report != null && fingerprint.equals(cache.fingerprint)) {
            EarlyLog.LOG.info("[紧急避险] mods 文件夹没有变化，沿用上次的检查结果");
            claimed = cache.claimed;
            report = cache.report;
            report.time = System.currentTimeMillis();
        } else {
            claimed = new ArrayList<>();
            List<ModCandidate> candidates = new ArrayList<>();
            try {
                for (Path jar : jars) {
                    ModScanner.ScanResult result = ModScanner.scan(jar, pipeline);
                    if (result instanceof ModScanner.Ok ok) {
                        candidates.add(ok.candidate());
                    } else if (result instanceof ModScanner.Broken broken) {
                        LaunchReport.IgnoredFile ignored = new LaunchReport.IgnoredFile();
                        ignored.file = jar.getFileName().toString();
                        ignored.reason = broken.reason();
                        ignored.detail = broken.detail();
                        report.ignored.add(ignored);
                        claimed.add(ignored.file);
                        EarlyLog.LOG.warn("[紧急避险] 跳过无法读取的文件 {}：{}", ignored.file, broken.detail());
                    }
                }

                if (previousFailed && !graphicsHandled) {
                    handlePreviousFailure(settings, heal, markerTime, candidates, stampSet, configState, transientNotes);
                }
                if (worldCrash) {
                    WorldSessionCheck.handle(worldSession, settings, heal, candidates, stampSet, configState, transientNotes);
                }
                ShelterFiles.write(ShelterFiles.HEAL_STATE, heal);

                DependencyAnalysis analysis = new DependencyAnalysis(candidates, systemMods(), FMLLoader.getDist(), neverDisable);
                for (HealState.Entry entry : heal.entries) {
                    for (ModCandidate c : candidates) {
                        if (c.fileName.equals(entry.file)) {
                            LaunchReport.Reason r = new LaunchReport.Reason();
                            r.kind = "HEALED";
                            r.mod = entry.modIds.isEmpty() ? null : entry.modIds.getFirst();
                            r.detail = entry.reason;
                            analysis.disableUpfront(c, r);
                        }
                    }
                }
                analysis.run();
                fillReport(report, analysis);
                analysis.disabled.keySet().forEach(c -> claimed.add(c.fileName));
                analysis.superseded.keySet().forEach(c -> claimed.add(c.fileName));
            } finally {
                candidates.forEach(ModCandidate::closeQuietly);
            }

            // 读不了的文件可能只是暂时的（云盘占位文件、杀毒软件占用、还没复制完），它的大小和修改时间不会变，
            // 写进缓存就会一直被跳过：这种情况不缓存，下次启动重新检查
            boolean transientFailure = report.ignored.stream().anyMatch(i -> "UNREADABLE".equals(i.reason) || "BROKEN_ZIP".equals(i.reason));
            if (transientFailure) {
                ShelterFiles.delete(ShelterFiles.ANALYSIS_CACHE);
            } else {
                AnalysisCache newCache = new AnalysisCache();
                newCache.fingerprint = fingerprint(stamps, heal, neverDisable);
                newCache.claimed = claimed;
                newCache.report = report;
                ShelterFiles.write(ShelterFiles.ANALYSIS_CACHE, newCache);
            }
        }

        if (!previousFailed && !worldCrash) {
            ShelterFiles.write(ShelterFiles.HEAL_STATE, heal);
        }
        report.notes.addAll(transientNotes.notes);
        report.healNotes.addAll(transientNotes.healNotes);
        report.configRestored.addAll(transientNotes.configRestored);

        // 认领：NeoForge 会认为这些文件"已经处理过"而跳过它们
        for (String name : claimed) {
            ctx.addLocated(modsDir.resolve(name));
        }
        report.modsFingerprint = fingerprint;
        ShelterFiles.write(ShelterFiles.REPORT, report);
        ShelterFiles.delete(ShelterFiles.PENDING_ISSUES);
        logSummary(report);
    }

    // ------------------------------------------------------------------ 自动修复

    private static void handlePreviousFailure(ShelterSettings settings, HealState heal, long markerTime, List<ModCandidate> candidates,
                                              Set<String> stampSet, ConfigGuard.State configState, LaunchReport report) {
        List<PendingIssues.Culprit> culprits = new ArrayList<>();
        PendingIssues pending = ShelterFiles.read(ShelterFiles.PENDING_ISSUES, PendingIssues.class);
        if (pending != null && pending.time >= markerTime - 1000 && pending.culprits != null) {
            culprits.addAll(pending.culprits);
        }
        if (culprits.isEmpty()) {
            culprits.addAll(CrashAnalyzer.analyze(FMLPaths.GAMEDIR.get(), markerTime, candidates));
        }

        Set<String> known = new HashSet<>();
        candidates.forEach(c -> known.add(c.fileName));
        Set<String> alreadyHealed = new HashSet<>();
        heal.entries.forEach(e -> alreadyHealed.add(e.file));
        Set<String> seen = new LinkedHashSet<>();
        culprits.removeIf(c -> c.file == null || !known.contains(c.file) || alreadyHealed.contains(c.file)
                || (c.modId != null && settings.neverDisable.contains(c.modId)) || !seen.add(c.file));

        if (heal.consecutiveHeals >= settings.maxConsecutiveHeals) {
            report.healNotes.add("已经连续自动修复 " + heal.consecutiveHeals + " 次仍未能正常启动，为避免越改越多，本次停止自动修复。"
                    + "请查看 logs/latest.log 或崩溃报告。");
            return;
        }
        if (culprits.isEmpty()) {
            handleUnattributedFailure(settings, heal, markerTime, candidates, stampSet, configState, report);
            return;
        }
        heal.consecutiveHeals++;
        for (PendingIssues.Culprit c : culprits) {
            // 先怀疑配置和脚本：自上次正常启动后被改过，就先换回去，这次不禁用
            if (settings.configGuard && c.modId != null && rollBackConfigsOf(c.modId, heal, configState, report, "HEAL",
                    "上次启动时 " + c.file + " 出错（" + c.message + "）", "如果仍然出错，下次启动会禁用这个模组。")) {
                continue;
            }
            if (!settings.autoHeal) {
                report.notes.add("上次启动时 " + c.file + " 出错（" + c.message + "）。自动修复已关闭，未做处理。");
                continue;
            }
            disable(heal, c.file, c.modId, c.message, stampSet);
            String owner = ScriptGuard.ownerOf(c.modId);
            report.healNotes.add("上次启动时 " + c.file + " 出错，本次已自动禁用它。原因：" + c.message
                    + (owner == null ? "" : "。修改 " + ScriptGuard.displayName(owner) + " 的脚本后，下次启动会自动重新启用它。"));
            EarlyLog.LOG.warn("[紧急避险] 上次启动失败，自动禁用 {}：{}", c.file, c.message);
        }
    }

    /**
     * 出错的模组自上次正常启动以来改过配置（脚本模组：改过或新加了脚本）：换回当时的版本。
     * 每个模组只做一次，换回后仍然出错才走下一步。返回 true 表示这次已经处理。
     */
    static boolean rollBackConfigsOf(String modId, HealState heal, ConfigGuard.State configState, LaunchReport report, String why,
                                     String cause, String retryHint) {
        String scriptOwner = ScriptGuard.ownerOf(modId);
        String owner = scriptOwner != null ? scriptOwner : modId;
        if (heal.configRolledBack.containsKey(owner)) {
            return false;
        }
        List<String> restored = ConfigGuard.restoreForMod(configState, owner, report, why, "PLAY_HANG".equals(why));
        List<String> moved = scriptOwner != null ? ScriptGuard.quarantineNew(scriptOwner, configState, "PLAY_HANG".equals(why), report) : List.of();
        if (restored.isEmpty() && moved.isEmpty()) {
            return false;
        }
        heal.configRolledBack.put(owner, System.currentTimeMillis());
        if (scriptOwner != null) {
            report.healNotes.add(ScriptGuard.note(scriptOwner, cause, restored, moved, retryHint));
        } else {
            report.healNotes.add(cause + "。它的配置最近被改过，已先恢复为改动之前的版本：" + String.join("、", restored) + "。" + retryHint);
        }
        return true;
    }

    /** 上次启动失败、但找不到出错的模组：可能是卡死在某个模组里，也可能是改动过的配置导致的。 */
    private static void handleUnattributedFailure(ShelterSettings settings, HealState heal, long markerTime, List<ModCandidate> candidates,
                                                  Set<String> stampSet, ConfigGuard.State configState, LaunchReport report) {
        String[] hung = settings.startupProfiler ? StartupProfiler.hungMod(markerTime) : null;
        if (hung != null) {
            String modId = hung[0];
            String name = hung[1];
            heal.consecutiveHeals++;
            if (settings.configGuard && rollBackConfigsOf(modId, heal, configState, report, "HANG",
                    "上次启动加载了 " + hung[2] + " 秒仍未完成，最后一直停在 " + name, "")) {
                return;
            }
            int times = heal.hangs.merge(modId, 1, Integer::sum);
            ModCandidate candidate = findByModId(candidates, modId);
            if (times >= 2 && settings.autoHeal && candidate != null && !settings.neverDisable.contains(modId)) {
                disable(heal, candidate.fileName, modId, "连续两次启动卡在这个模组", stampSet);
                report.healNotes.add("连续两次启动都停在 " + name + "（" + candidate.fileName + "），本次已自动禁用它。");
                EarlyLog.LOG.warn("[紧急避险] 连续两次启动卡在 {}，自动禁用 {}", modId, candidate.fileName);
            } else {
                report.healNotes.add("上次启动加载了 " + hung[2] + " 秒仍未完成，最后一直停在 " + name + "。"
                        + "如果这次还是卡在它，下次启动会自动禁用它。");
            }
            return;
        }
        boolean crashed = CrashAnalyzer.hasCrashSince(FMLPaths.GAMEDIR.get(), markerTime);
        if (crashed && settings.configGuard && !heal.configRolledBackAll && !configState.changed.isEmpty()) {
            heal.consecutiveHeals++;
            heal.configRolledBackAll = true;
            List<String> restored = ConfigGuard.restoreAll(configState, new ArrayList<>(configState.changed.keySet()), null, report, "HEAL");
            if (!restored.isEmpty()) {
                report.healNotes.add("上次启动崩溃了，崩溃报告里看不出是哪个模组。自上次正常启动以来有 " + restored.size()
                        + " 个配置文件被改过，已全部恢复为当时的版本（改过的版本已备份）。");
                return;
            }
        }
        report.notes.add("上次启动没有完成，但没有找到可以明确归因的出错模组，本次不额外禁用任何模组。");
    }

    private static void disable(HealState heal, String file, String modId, String reason, Set<String> stampSet) {
        HealState.Entry entry = new HealState.Entry();
        entry.file = file;
        if (modId != null) {
            entry.modIds.add(modId);
        }
        entry.reason = reason;
        entry.time = System.currentTimeMillis();
        entry.filesAtHeal = new ArrayList<>(stampSet);
        entry.scriptOwner = ScriptGuard.ownerOf(modId);
        entry.scriptStamp = entry.scriptOwner == null ? null : ScriptGuard.stamp(entry.scriptOwner);
        heal.entries.add(entry);
    }

    @Nullable
    private static ModCandidate findByModId(List<ModCandidate> candidates, String modId) {
        for (ModCandidate c : candidates) {
            for (ModCandidate.ModDecl mod : c.topLevelMods()) {
                if (mod.modId().equals(modId)) {
                    return c;
                }
            }
        }
        return null;
    }

    private static void expireHealEntries(HealState heal, Set<String> stampSet, LaunchReport report) {
        heal.entries.removeIf(entry -> {
            Set<String> before = new HashSet<>(entry.filesAtHeal == null ? List.of() : entry.filesAtHeal);
            boolean changed = stampSet.stream().anyMatch(s -> !before.contains(s));
            if (changed) {
                report.notes.add("mods 文件夹有新增或更新的文件，重新尝试加载之前自动禁用的 " + entry.file + "。");
                return true;
            }
            if (entry.scriptOwner != null && entry.scriptStamp != null && !entry.scriptStamp.equals(ScriptGuard.stamp(entry.scriptOwner))) {
                report.notes.add(ScriptGuard.displayName(entry.scriptOwner) + " 的脚本有改动，重新尝试加载之前自动禁用的 " + entry.file + "。");
                return true;
            }
            return false;
        });
    }

    // ------------------------------------------------------------------ 报告

    private static void fillReport(LaunchReport report, DependencyAnalysis analysis) {
        for (var e : analysis.disabled.entrySet()) {
            ModCandidate c = e.getKey();
            LaunchReport.DisabledFile df = new LaunchReport.DisabledFile();
            df.file = c.fileName;
            for (ModCandidate.ModDecl mod : c.topLevelMods()) {
                df.mods.add(new LaunchReport.ModRef(mod.modId(), mod.displayName(), mod.version().toString()));
            }
            df.reasons = e.getValue();
            df.cause = df.reasons.stream().anyMatch(r -> "HEALED".equals(r.kind)) ? "HEAL" : "DEPENDENCY";
            report.disabled.add(df);
        }
        for (var e : analysis.missing.entrySet()) {
            LaunchReport.MissingMod mm = new LaunchReport.MissingMod();
            mm.id = e.getKey();
            mm.requiredBy.addAll(e.getValue());
            report.missing.add(mm);
        }
        for (var e : analysis.superseded.entrySet()) {
            LaunchReport.IgnoredFile ignored = new LaunchReport.IgnoredFile();
            ignored.file = e.getKey().fileName;
            ignored.reason = "OLDER_DUPLICATE";
            ignored.detail = e.getValue();
            report.ignored.add(ignored);
        }
        for (var e : analysis.protectedFailures.entrySet()) {
            report.notes.add(e.getKey().fileName + " 按规则需要禁用，但它在 neverDisable 列表中，已保留（NeoForge 可能会因此报错）。");
        }
        report.disabled.sort(Comparator.comparing(d -> d.file.toLowerCase(Locale.ROOT)));
    }

    private static void logSummary(LaunchReport report) {
        if (report.isEmpty()) {
            EarlyLog.LOG.info("[紧急避险] 所有模组的依赖都完整，没有需要禁用的模组");
            return;
        }
        for (LaunchReport.MissingMod m : report.missing) {
            EarlyLog.LOG.warn("[紧急避险] 缺少模组 {}（被 {} 需要）", m.id, String.join(", ", m.requiredBy));
        }
        for (LaunchReport.DisabledFile d : report.disabled) {
            EarlyLog.LOG.warn("[紧急避险] 本次跳过 {}", d.file);
        }
        if (!report.disabled.isEmpty() || !report.ignored.isEmpty()) {
            EarlyLog.LOG.warn("[紧急避险] 共跳过 {} 个模组文件，忽略 {} 个无效文件，详见 emergencyshelter/report.json",
                    report.disabled.size(), report.ignored.size());
        }
    }

    // ------------------------------------------------------------------ 工具

    /** 与 NeoForge 的 ModsFolderLocator 用完全相同的方式列出文件，保证路径一致。 */
    private static List<Path> listJars(Path modsDir, ILaunchContext ctx) throws IOException {
        if (!Files.isDirectory(modsDir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(modsDir)) {
            return files.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .filter(Files::isRegularFile)
                    .filter(p -> !ctx.isLocated(p))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .toList();
        }
    }

    private static String stamp(Path p) {
        try {
            return Files.size(p) + "|" + Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return "?";
        }
    }

    /** 由加载器自己提供的模组：Minecraft、NeoForge，以及 NeoForge 本体内嵌的库（例如 MixinExtras）。 */
    static Map<String, ArtifactVersion> systemMods() {
        Map<String, ArtifactVersion> map = new HashMap<>();
        map.put("minecraft", new DefaultArtifactVersion(FMLLoader.versionInfo().mcVersion()));
        map.put("neoforge", new DefaultArtifactVersion(FMLLoader.versionInfo().neoForgeVersion()));
        try {
            Path universal = LibraryFinder.findPathForMaven("net.neoforged", "neoforge", "", "universal",
                    FMLLoader.versionInfo().neoForgeVersion());
            if (Files.isRegularFile(universal)) {
                for (ModCandidate.ModDecl mod : ModScanner.readJarInJarOf(universal)) {
                    map.putIfAbsent(mod.modId(), mod.version());
                }
            }
        } catch (Throwable t) {
            EarlyLog.LOG.debug("[紧急避险] 无法读取 NeoForge 内嵌模组", t);
        }
        // "早期服务"类的 jar（例如 Sodium 0.6 以后的版本）：真正的模组由它们自己的加载器在启动时加入，
        // 扫描 mods 文件夹时看不到，但它们一定会被加载，依赖它们的模组不能当成缺少前置
        try {
            for (Path path : ModDirTransformerDiscoverer.allExcluded()) {
                if (hasEmbeddedMod(path)) {
                    continue; // 本模组自己
                }
                for (ModCandidate.ModDecl mod : ModScanner.readOwnMods(path)) {
                    map.putIfAbsent(mod.modId(), mod.version());
                }
                for (ModCandidate.ModDecl mod : ModScanner.readJarInJarOf(path)) {
                    map.putIfAbsent(mod.modId(), mod.version());
                }
            }
        } catch (Throwable t) {
            EarlyLog.LOG.debug("[紧急避险] 无法读取早期服务类模组", t);
        }
        return map;
    }

    private static String fingerprint(Map<String, String> stamps, HealState heal, Set<String> neverDisable) {
        StringBuilder sb = new StringBuilder();
        sb.append(ownVersion()).append('\n');
        sb.append(ANALYSIS_VERSION).append('\n');
        sb.append(FMLLoader.getDist()).append('\n');
        sb.append(FMLLoader.versionInfo()).append('\n');
        sb.append(new TreeMap<>(net.neoforged.fml.loading.FMLConfig.getDependencyOverrides())).append('\n');
        sb.append(new java.util.TreeSet<>(neverDisable)).append('\n');
        heal.entries.stream().map(e -> e.file).sorted().forEach(f -> sb.append("heal:").append(f).append('\n'));
        stamps.forEach((k, v) -> sb.append(k).append('=').append(v).append('\n'));
        return sha256(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            return Integer.toHexString(java.util.Arrays.hashCode(bytes));
        }
    }

    static String ownVersion() {
        String v = EarlyShelter.class.getPackage().getImplementationVersion();
        return v == null ? "dev" : v;
    }

    static void writeMarker() {
        try {
            Files.writeString(ShelterFiles.file(ShelterFiles.LAUNCH_MARKER),
                    "launch started " + System.currentTimeMillis() + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            EarlyLog.LOG.warn("[紧急避险] 无法写入启动标记", e);
        }
    }

    // ------------------------------------------------------------------ 游戏内部分

    private static void addEmbeddedMod(IDiscoveryPipeline pipeline) throws IOException {
        Path outer = findOwnJar();
        if (outer == null) {
            EarlyLog.LOG.warn("[紧急避险] 找不到自身 jar，游戏内部分不会加载（开发环境下属于正常情况）");
            return;
        }
        byte[] bytes;
        try (ZipFile zip = new ZipFile(outer.toFile())) {
            ZipEntry entry = zip.getEntry(EMBEDDED_MOD);
            if (entry == null) {
                EarlyLog.LOG.warn("[紧急避险] {} 中没有内嵌的游戏内部分", outer);
                return;
            }
            try (InputStream in = zip.getInputStream(entry)) {
                bytes = in.readAllBytes();
            }
        }
        String hash = sha256(bytes).substring(0, 16);
        Path cacheDir = ShelterFiles.dir().resolve(".cache");
        Files.createDirectories(cacheDir);
        String fileName = "emergencyshelter-mod-" + hash + ".jar";
        Path target = cacheDir.resolve(fileName);
        if (!Files.isRegularFile(target) || Files.size(target) != bytes.length) {
            Path tmp = cacheDir.resolve(fileName + ".tmp");
            Files.write(tmp, bytes);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
        try (Stream<Path> old = Files.list(cacheDir)) {
            old.filter(p -> p.getFileName().toString().startsWith("emergencyshelter-mod-") && !p.equals(target))
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                            // 可能正被另一个游戏实例使用
                        }
                    });
        }
        pipeline.addPath(target, ModFileDiscoveryAttributes.DEFAULT, IncompatibleFileReporting.ERROR);
    }

    @Nullable
    private static Path findOwnJar() {
        try {
            URL location = EarlyShelter.class.getProtectionDomain().getCodeSource().getLocation();
            Path path = Paths.get(location.toURI());
            if (path.getFileSystem() instanceof UnionFileSystem union) {
                path = union.getPrimaryPath();
            }
            if (Files.isRegularFile(path) && hasEmbeddedMod(path)) {
                return path;
            }
        } catch (Throwable ignored) {
        }
        for (Path path : ModDirTransformerDiscoverer.allExcluded()) {
            if (hasEmbeddedMod(path)) {
                return path;
            }
        }
        return null;
    }

    private static boolean hasEmbeddedMod(Path jar) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            return zip.getEntry(EMBEDDED_MOD) != null;
        } catch (IOException e) {
            return false;
        }
    }
}
