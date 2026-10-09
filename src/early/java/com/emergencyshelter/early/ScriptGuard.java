package com.emergencyshelter.early;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.jetbrains.annotations.Nullable;

/**
 * KubeJS、CraftTweaker 这类"脚本模组"引起的崩溃：先怀疑脚本，而不是禁用模组本身。
 * <ul>
 *     <li>自上次正常启动以来改过的脚本：换回当时的版本（与配置文件一样，见 {@link ConfigGuard}）；</li>
 *     <li>自上次正常启动以来新加的脚本：暂时移到 {@code emergencyshelter/script-quarantine/<时间>/}；</li>
 *     <li>仍然崩溃才禁用这个模组；脚本再有改动时会自动重新启用它（玩家可能修好了脚本）。</li>
 * </ul>
 * 禁用 KubeJS 会让依赖它的附属模组一起失效、整合包的配方全部变回原样，所以放在最后。
 */
final class ScriptGuard {
    /** 脚本模组 → 它的脚本目录（相对游戏目录）。 */
    private static final Map<String, List<String>> SCRIPT_DIRS = Map.of(
            "kubejs", List.of("kubejs/startup_scripts", "kubejs/server_scripts", "kubejs/client_scripts", "kubejs/data"),
            "crafttweaker", List.of("scripts"));
    private static final Set<String> SCRIPT_EXT = Set.of("js", "ts", "zs", "json", "mcfunction");
    static final String QUARANTINE_DIR = "script-quarantine";
    /** KubeJS（server_scripts:recipes.js#12、server_scripts/recipes.js:12）和 CraftTweaker（recipes.zs:12）的脚本位置。 */
    private static final Pattern SCRIPT_REF = Pattern.compile(
            "((?:startup|server|client)_scripts[:/][\\w./\\-]+?\\.js|[\\w./\\-]+?\\.zs)(?:[:#](\\d+))?");

    private ScriptGuard() {
    }

    /** 出错的模组如果是脚本模组（或者是它们的运行库、附属模组），返回脚本的归属：kubejs / crafttweaker。 */
    @Nullable
    static String ownerOf(@Nullable String modId) {
        if (modId == null) {
            return null;
        }
        String id = modId.toLowerCase(Locale.ROOT);
        if (id.startsWith("kubejs") || id.equals("rhino") || id.equals("probejs")) {
            return "kubejs";
        }
        if (id.startsWith("crafttweaker") || id.equals("zenscript")) {
            return "crafttweaker";
        }
        return null;
    }

    /** 这个文件（相对游戏目录）是不是脚本模组的脚本。 */
    static boolean isScript(String rel) {
        for (List<String> dirs : SCRIPT_DIRS.values()) {
            for (String dir : dirs) {
                if (rel.startsWith(dir + "/")) {
                    return true;
                }
            }
        }
        return false;
    }

    static String displayName(String owner) {
        return owner.equals("kubejs") ? "KubeJS" : "CraftTweaker";
    }

    /** 文本（崩溃报告、异常信息）里提到的脚本位置，最多 5 处。 */
    static String scriptRefs(String text) {
        Set<String> refs = new LinkedHashSet<>();
        Matcher m = SCRIPT_REF.matcher(text);
        while (m.find() && refs.size() < 5) {
            refs.add(m.group(1).replace(':', '/') + (m.group(2) == null ? "" : " 第 " + m.group(2) + " 行"));
        }
        return String.join("、", refs);
    }

    /** 所有脚本模组的脚本里，不在给定集合（快照）里的那些。 */
    static List<String> scriptsNotIn(Set<String> known) {
        List<String> out = new ArrayList<>();
        for (String owner : SCRIPT_DIRS.keySet()) {
            for (String rel : scripts(owner)) {
                if (!known.contains(rel)) {
                    out.add(rel);
                }
            }
        }
        return out;
    }

    /**
     * 自上次正常启动以来新加的脚本：暂时移出脚本目录。没有快照时无法判断哪些是新的，什么也不做。
     *
     * @param includeRecent 游戏过程中出的问题：上一次启动之前新加的脚本也算（它们在那次启动成功后已经被记为"正常"）
     */
    static List<String> quarantineNew(String owner, ConfigGuard.State state, boolean includeRecent, LaunchReport report) {
        Set<String> known = ConfigGuard.snapshotFiles();
        List<String> moved = new ArrayList<>();
        if (known == null) {
            return moved;
        }
        Path game = ConfigGuard.gameDir();
        String stamp = ConfigGuard.timestamp();
        for (String rel : scripts(owner)) {
            if (known.contains(rel) && !(includeRecent && state.recentAdded.contains(rel))) {
                continue;
            }
            Path source = game.resolve(rel);
            Path target = ShelterFiles.dir().resolve(QUARANTINE_DIR).resolve(stamp).resolve(rel);
            try {
                Files.createDirectories(target.getParent());
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
                String backup = game.relativize(target).toString().replace('\\', '/');
                moved.add(rel);
                report.configRestored.add(new LaunchReport.ConfigChange(rel, "SCRIPT_NEW", owner, backup));
                EarlyLog.LOG.warn("[紧急避险] 新加的脚本 {} 已暂时移到 {}", rel, backup);
            } catch (IOException e) {
                EarlyLog.LOG.warn("[紧急避险] 无法移走脚本 {}", rel, e);
            }
        }
        return moved;
    }

    /** 脚本目录的指纹（文件名、大小、修改时间）。脚本有任何改动指纹就会变。 */
    static String stamp(String owner) {
        Map<String, String> entries = new TreeMap<>();
        Path game = ConfigGuard.gameDir();
        for (String rel : scripts(owner)) {
            try {
                Path p = game.resolve(rel);
                entries.put(rel, Files.size(p) + "|" + Files.getLastModifiedTime(p).toMillis());
            } catch (IOException ignored) {
            }
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            entries.forEach((k, v) -> digest.update((k + "=" + v + "\n").getBytes(StandardCharsets.UTF_8)));
            return HexFormat.of().formatHex(digest.digest()).substring(0, 16);
        } catch (Exception e) {
            return String.valueOf(entries.hashCode());
        }
    }

    private static List<String> scripts(String owner) {
        List<String> out = new ArrayList<>();
        Path game = ConfigGuard.gameDir();
        for (String dir : SCRIPT_DIRS.getOrDefault(owner, List.of())) {
            Path root = game.resolve(dir);
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(Files::isRegularFile).forEach(p -> {
                    String rel = game.relativize(p).toString().replace('\\', '/');
                    if (SCRIPT_EXT.contains(ConfigGuard.extension(rel))) {
                        out.add(rel);
                    }
                });
            } catch (IOException ignored) {
            }
        }
        return out;
    }

    /** 给玩家看的说明。 */
    static String note(String owner, String cause, List<String> restored, List<String> moved, String retryHint) {
        StringBuilder sb = new StringBuilder(cause).append("。这通常是脚本引起的，所以先处理脚本而不是禁用 ").append(displayName(owner)).append("：");
        if (!restored.isEmpty()) {
            sb.append("自上次正常启动以来改过的 ").append(restored.size()).append(" 个脚本已换回当时的版本（").append(String.join("、", restored))
                    .append("）；");
        }
        if (!moved.isEmpty()) {
            sb.append("新加的 ").append(moved.size()).append(" 个脚本已暂时移到 emergencyshelter/").append(QUARANTINE_DIR).append("/（")
                    .append(String.join("、", moved)).append("）；");
        }
        sb.append("被换下来的版本都有备份。").append(retryHint);
        return sb.toString();
    }
}
