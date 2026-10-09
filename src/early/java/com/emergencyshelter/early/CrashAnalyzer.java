package com.emergencyshelter.early;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.jetbrains.annotations.Nullable;

/**
 * 读取上次启动失败时留下的崩溃报告，尽量找出"是哪个模组的代码出的错"。
 * 只在证据明确时给出结论；找不到就什么也不做，绝不瞎猜。
 */
final class CrashAnalyzer {
    private static final Pattern MOD_LOADING_ISSUE = Pattern.compile("Mod loading issue for: ([a-z][a-z0-9_]{1,63})");
    private static final Pattern MIXIN_FROM_MOD = Pattern.compile("from mod ([a-z][a-z0-9_]{1,63})\\]");
    private static final Pattern MIXIN_CONFIG = Pattern.compile("in config \\[([^\\]\\s]+)\\]");
    private static final Pattern FRAME_MODULE = Pattern.compile("^\\s*at\\s+(?:[A-Za-z0-9_-]+/)?([A-Za-z0-9_.$-]+)@[^/\\s]*/");
    private static final Pattern FRAME_JAR = Pattern.compile("~\\[([^\\]%!]+?\\.jar)");
    /** 这些模块出现在堆栈里不代表它们有错 */
    private static final Set<String> NEVER_BLAME = Set.of("minecraft", "neoforge", "emergencyshelter", "emergencyshelter.early");

    private CrashAnalyzer() {
    }

    /** 某个时间之后是否生成过崩溃报告（或 JVM 崩溃日志）。 */
    static boolean hasCrashSince(Path gameDir, long sinceMillis) {
        for (Path dir : List.of(gameDir.resolve("crash-reports"), gameDir)) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> files = Files.list(dir)) {
                if (files.filter(p -> {
                    String name = p.getFileName().toString();
                    return (dir.endsWith("crash-reports") && name.endsWith(".txt")) || (name.startsWith("hs_err_pid") && name.endsWith(".log"));
                }).anyMatch(p -> lastModified(p) >= sinceMillis - 2000)) {
                    return true;
                }
            } catch (IOException ignored) {
            }
        }
        return false;
    }

    static List<PendingIssues.Culprit> analyze(Path gameDir, long sinceMillis, List<ModCandidate> candidates) {
        Path dir = gameDir.resolve("crash-reports");
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        Optional<Path> newest;
        try (Stream<Path> files = Files.list(dir)) {
            newest = files.filter(p -> p.getFileName().toString().endsWith(".txt"))
                    .filter(p -> lastModified(p) >= sinceMillis - 2000)
                    .max(Comparator.comparingLong(CrashAnalyzer::lastModified));
        } catch (IOException e) {
            return List.of();
        }
        if (newest.isEmpty()) {
            return List.of();
        }
        String text;
        try {
            text = Files.readString(newest.get(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            try {
                text = new String(Files.readAllBytes(newest.get()), StandardCharsets.ISO_8859_1);
            } catch (IOException e2) {
                return List.of();
            }
        }
        String reportName = newest.get().getFileName().toString();
        ModCandidate culprit = findCulprit(text, candidates);
        if (culprit == null) {
            EarlyLog.LOG.info("[紧急避险] 崩溃报告 {} 中没有找到可以明确归因的模组", reportName);
            return List.of();
        }
        ModCandidate.ModDecl first = culprit.firstMod();
        String description = firstLine(text);
        String modId = first == null ? null : first.modId();
        String scripts = ScriptGuard.ownerOf(modId) != null ? ScriptGuard.scriptRefs(mainStackTrace(text)) : "";
        return List.of(new PendingIssues.Culprit(culprit.fileName, modId,
                "崩溃报告 " + reportName + (description == null ? "" : "：" + description) + (scripts.isEmpty() ? "" : "；出错的脚本：" + scripts)));
    }

    @Nullable
    static ModCandidate findCulprit(String text, List<ModCandidate> candidates) {
        Map<String, ModCandidate> byModId = new HashMap<>();
        Map<String, ModCandidate> byModule = new HashMap<>();
        Map<String, ModCandidate> byFile = new HashMap<>();
        Map<String, ModCandidate> byMixin = new HashMap<>();
        for (ModCandidate c : candidates) {
            for (ModCandidate.ModDecl mod : c.topLevelMods()) {
                byModId.putIfAbsent(mod.modId(), c);
            }
            if (c.moduleName != null) {
                byModule.putIfAbsent(c.moduleName, c);
            }
            byFile.putIfAbsent(c.fileName, c);
            for (String mixin : c.mixinConfigs) {
                byMixin.putIfAbsent(mixin, c);
            }
        }

        // 1. NeoForge 明确写出的"某模组加载出错"
        ModCandidate hit = firstMatch(MOD_LOADING_ISSUE, text, byModId);
        if (hit != null) {
            return hit;
        }
        // 2. Mixin 应用失败：消息里带有模组 id 或 mixin 配置文件名
        hit = firstMatch(MIXIN_FROM_MOD, text, byModId);
        if (hit != null) {
            return hit;
        }
        hit = firstMatch(MIXIN_CONFIG, text, byMixin);
        if (hit != null) {
            return hit;
        }
        // 3. 主异常堆栈：从最深的 Caused by 开始，找第一个属于 mods 文件夹中模组的栈帧
        String mainTrace = mainStackTrace(text);
        List<List<String>> blocks = splitCauses(mainTrace);
        for (int i = blocks.size() - 1; i >= 0; i--) {
            for (String line : blocks.get(i)) {
                ModCandidate c = frameOwner(line, byModule, byFile);
                if (c != null) {
                    return c;
                }
            }
        }
        return null;
    }

    @Nullable
    private static ModCandidate firstMatch(Pattern pattern, String text, Map<String, ModCandidate> lookup) {
        Matcher m = pattern.matcher(text);
        while (m.find()) {
            String key = m.group(1);
            if (NEVER_BLAME.contains(key)) {
                continue;
            }
            ModCandidate c = lookup.get(key);
            if (c != null && !isNeverBlame(c)) {
                return c;
            }
        }
        return null;
    }

    @Nullable
    private static ModCandidate frameOwner(String line, Map<String, ModCandidate> byModule, Map<String, ModCandidate> byFile) {
        Matcher module = FRAME_MODULE.matcher(line);
        if (module.find()) {
            String name = module.group(1);
            if (!NEVER_BLAME.contains(name)) {
                ModCandidate c = byModule.get(name);
                if (c != null && !isNeverBlame(c)) {
                    return c;
                }
            }
        }
        Matcher jar = FRAME_JAR.matcher(line);
        if (jar.find()) {
            ModCandidate c = byFile.get(jar.group(1));
            if (c != null && !isNeverBlame(c)) {
                return c;
            }
        }
        return null;
    }

    private static boolean isNeverBlame(ModCandidate c) {
        return c.topLevelMods().stream().anyMatch(m -> NEVER_BLAME.contains(m.modId()));
    }

    /** 崩溃报告开头 Description 之后、"A detailed walkthrough" 之前的那段就是主异常。 */
    private static String mainStackTrace(String text) {
        int start = text.indexOf("Description:");
        int end = text.indexOf("A detailed walkthrough of the error");
        if (start < 0) {
            start = 0;
        }
        if (end < 0 || end < start) {
            end = Math.min(text.length(), start + 20000);
        }
        return text.substring(start, end);
    }

    private static List<List<String>> splitCauses(String trace) {
        List<List<String>> blocks = new ArrayList<>();
        List<String> current = new ArrayList<>();
        for (String line : trace.split("\\R")) {
            if (line.trim().startsWith("Caused by:")) {
                blocks.add(current);
                current = new ArrayList<>();
            }
            if (line.trim().startsWith("at ")) {
                current.add(line);
            }
        }
        blocks.add(current);
        return blocks;
    }

    @Nullable
    private static String firstLine(String text) {
        for (String line : text.split("\\R")) {
            if (line.startsWith("Description:")) {
                return line.substring("Description:".length()).trim();
            }
        }
        return null;
    }

    private static long lastModified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }
}
