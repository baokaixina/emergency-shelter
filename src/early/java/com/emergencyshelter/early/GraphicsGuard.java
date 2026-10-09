package com.emergencyshelter.early;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.jetbrains.annotations.Nullable;

/**
 * 光影、资源包（材质包）导致的崩溃（客户端）。
 * <p>
 * 这类崩溃经常连 Java 崩溃报告都没有（显卡驱动直接崩溃，只留下 hs_err_pid*.log），而且设置保存在文件里，
 * 每次启动都会再崩一次，玩家连进入设置界面关掉它的机会都没有。游戏内部分记下"正在做什么"
 * （emergencyshelter/client-session.json）；如果上次没有正常退出，这里在游戏读取设置之前判断原因：
 * <ul>
 *     <li>开着光影、在世界里崩溃，并且是显卡驱动、渲染相关的崩溃，或者内存 / 显存不足：关闭光影（光影包本身不动）；</li>
 *     <li>加载资源包时崩溃或卡死，并且这次比上次成功时多了资源包：只停用新加的那些，恢复上次成功的组合；</li>
 *     <li>同样的显卡崩溃连续出现、又没开光影：停用所有资源包文件（资源包本身不动）。</li>
 * </ul>
 */
final class GraphicsGuard {
    static final String SESSION_FILE = "client-session.json";
    static final String STATE_FILE = "graphics-state.json";
    private static final Pattern PROBLEMATIC_FRAME = Pattern.compile("(?m)^#\\s+(?:C|j|J|V)\\s+\\[([^+\\]]+)");
    /** 显卡驱动和 OpenGL 相关的动态库。 */
    private static final List<String> GPU_LIBS = List.of("atio6axx", "atioglxx", "amdxc", "amdvlk", "nvoglv", "nvwgf", "ig7icd", "ig75icd", "ig8icd",
            "ig9icd", "ig11icd", "igxelpicd", "igdumd", "igd10", "opengl32", "lwjgl_opengl", "lwjgl.dll", "libgl", "radeonsi", "iris_dri", "libnvidia",
            "dxgi", "d3d", "vulkan");
    private static final List<String> GRAPHICS_DESCRIPTIONS = List.of("Rendering overlay", "Rendering screen", "Rendering Block Entity",
            "Rendering entity in world", "Rendering item", "Rendering Particle", "Rendering level", "Tesselating block", "Batching chunks",
            "Initializing game", "Resource reload", "Stitching", "Uploading", "Compiling");
    private static final List<String> GRAPHICS_MARKERS = List.of("net.irisshaders", "net.coderbot.iris", "oculus", "org.lwjgl.opengl",
            "com.mojang.blaze3d", "ShaderInstance", "StitcherException", "Maybe try a lower resolution", "TextureAtlas", "GL_OUT_OF_MEMORY",
            "GlStateManager", "shaderpack");
    private static final List<String> SHADER_MARKERS = List.of("net.irisshaders", "net.coderbot.iris", "oculus", "shaderpack", "ShaderPack", "Iris");

    private GraphicsGuard() {
    }

    /** 上次客户端没有正常退出的证据。 */
    static final class Evidence {
        @Nullable
        String crashReport;
        @Nullable
        String crashName;
        @Nullable
        String nativeLog;
        @Nullable
        String nativeName;
        @Nullable
        String problematicLib;
        boolean gpu;
        boolean oom;
        boolean graphics;
        boolean shader;

        boolean any() {
            return crashReport != null || nativeLog != null;
        }

        String describe() {
            if (nativeLog != null) {
                return "游戏进程直接崩溃（" + nativeName + (problematicLib == null ? "" : "，出错位置 " + problematicLib) + (gpu ? "，属于显卡驱动" : "") + "）";
            }
            if (crashReport != null) {
                return "崩溃报告 " + crashName;
            }
            return "游戏被强制关闭";
        }
    }

    /**
     * 返回 true 表示上次的失败已经按光影 / 资源包问题处理了，本次不再按模组问题处理。
     */
    static boolean checkAtLaunch(ShelterSettings settings, LaunchReport report) {
        Path sessionFile = ShelterFiles.file(SESSION_FILE);
        JsonObject session = readJson(sessionFile);
        long lastAlive = modified(sessionFile); // 游戏运行时每 10 秒更新一次，约等于进程结束的时间
        ShelterFiles.delete(SESSION_FILE);
        if (session == null || !settings.graphicsGuard) {
            return false;
        }
        String phase = str(session, "phase", "");
        long phaseSince = num(session, "phaseSince");
        long started = num(session, "started");
        Evidence ev = collect(ConfigGuard.gameDir(), Math.max(started, phaseSince) - 2000);
        JsonObject state = readJson(ShelterFiles.file(STATE_FILE));
        if (state == null) {
            state = new JsonObject();
        }
        List<String> packs = strings(session, "packs");
        List<String> lastGood = strings(state, "lastGoodPacks");
        long lastReloadMs = num(state, "lastReloadMs");
        boolean handled = false;

        if (ev.oom) {
            long max = Runtime.getRuntime().maxMemory() >> 20;
            report.healNotes.add("上次游戏因为内存或显存不足而崩溃（" + ev.describe() + "）。当前分配给游戏的最大内存约 " + max / 1024.0
                    + " GB；高分辨率材质包和光影会占用大量内存和显存，可以在启动器里调高内存，或换用低分辨率的材质包。");
        }

        // 1. 开着光影、在世界里出现渲染相关的崩溃：关闭光影
        JsonObject shader = session.has("shader") && session.get("shader").isJsonObject() ? session.getAsJsonObject("shader") : null;
        // 开启了光影并且选了光影包，才算真的在用光影（Iris 默认开启，但没选光影包时什么也不做）
        boolean shaderOn = shader != null && shader.has("enabled") && shader.get("enabled").getAsBoolean() && !str(shader, "pack", "").isBlank();
        if (shaderOn && ("WORLD".equals(phase) || "RELOAD".equals(phase)) && (ev.gpu || ev.oom || ev.graphics || ev.shader)) {
            String loader = str(shader, "loader", "iris");
            String pack = str(shader, "pack", "?");
            if (disableShaders(loader, report)) {
                report.healNotes.add("上次使用光影 " + pack + " 时游戏崩溃（" + ev.describe() + "）。已暂时关闭光影，光影包本身没有删除；"
                        + "可以在 选项 → 视频设置 → 光影包 中重新开启，或者换一个光影。");
                EarlyLog.LOG.warn("[紧急避险] 上次开着光影 {} 时崩溃（{}），已关闭光影", pack, ev.describe());
                handled = true;
            }
        }

        // 2. 加载资源包时崩溃或卡住，并且比上次成功时多了资源包：恢复上次成功的组合
        boolean startup = "STARTUP".equals(phase);
        boolean reload = "RELOAD".equals(phase);
        long phaseMs = Math.max(0, lastAlive - phaseSince);
        boolean hung = !ev.any() && (startup || reload) && phaseMs > Math.max(300_000, lastReloadMs * 3);
        boolean packsSuspect = hung || (startup ? (ev.gpu || ev.oom || ev.graphics || ev.nativeLog != null) : ev.any());
        List<String> added = new ArrayList<>(packs);
        added.removeAll(lastGood);
        added.removeIf(id -> !id.startsWith("file/"));
        if (!handled && (startup || reload) && packsSuspect && !lastGood.isEmpty() && !added.isEmpty()) {
            if (setResourcePacks(lastGood, report)) {
                report.healNotes.add("上次" + (startup ? "启动加载" : "加载") + "资源包时游戏" + (hung ? "卡住后被关闭" : "崩溃（" + ev.describe() + "）")
                        + "。已停用自上次成功加载以来新加的资源包：" + names(added) + "，其余资源包保持不变。资源包文件没有删除，可以在 选项 → 资源包 中重新启用。");
                EarlyLog.LOG.warn("[紧急避险] 上次加载资源包时失败，停用新加的资源包 {}", added);
                handled = true;
            }
        }

        // 3. 没开光影，却连续出现显卡驱动崩溃或内存不足，并且启用了资源包文件：第二次时停用所有资源包文件
        List<String> userPacks = new ArrayList<>(packs);
        userPacks.removeIf(id -> !id.startsWith("file/"));
        int streak = (int) num(state, "streak");
        if (!handled && (ev.gpu || ev.oom) && !userPacks.isEmpty()) {
            streak++;
            if (streak >= 2) {
                List<String> keep = new ArrayList<>(packs);
                keep.removeAll(userPacks);
                if (setResourcePacks(keep, report)) {
                    report.healNotes.add("游戏连续 " + streak + " 次在显卡驱动里崩溃或内存不足（" + ev.describe() + "），已停用所有资源包文件：" + names(userPacks)
                            + "。资源包本身没有删除，可以在 选项 → 资源包 中重新启用。");
                    handled = true;
                    streak = 0;
                }
            } else {
                report.healNotes.add("上次游戏在显卡驱动里崩溃或内存不足（" + ev.describe() + "）。如果再次发生，下次启动会暂时停用资源包：" + names(userPacks) + "。");
            }
        } else if (!ev.any()) {
            streak = 0;
        }
        state.addProperty("streak", streak);
        writeJson(ShelterFiles.file(STATE_FILE), state);
        return handled;
    }

    // ------------------------------------------------------------------ 证据

    static Evidence collect(Path gameDir, long since) {
        Evidence ev = new Evidence();
        Optional<Path> report = newest(gameDir.resolve("crash-reports"), p -> p.getFileName().toString().endsWith(".txt"), since);
        if (report.isPresent()) {
            ev.crashName = report.get().getFileName().toString();
            ev.crashReport = read(report.get());
        }
        Optional<Path> hs = newest(gameDir, p -> {
            String n = p.getFileName().toString();
            return n.startsWith("hs_err_pid") && n.endsWith(".log");
        }, since);
        if (hs.isPresent()) {
            ev.nativeName = hs.get().getFileName().toString();
            ev.nativeLog = read(hs.get());
        }
        if (ev.nativeLog != null) {
            Matcher m = PROBLEMATIC_FRAME.matcher(ev.nativeLog);
            if (m.find()) {
                ev.problematicLib = m.group(1).trim();
                String lib = ev.problematicLib.toLowerCase(Locale.ROOT);
                ev.gpu = GPU_LIBS.stream().anyMatch(lib::contains);
            }
            String head = ev.nativeLog.length() > 20000 ? ev.nativeLog.substring(0, 20000) : ev.nativeLog;
            ev.oom |= head.contains("Out of Memory Error") || head.contains("OutOfMemoryError") || head.contains("insufficient memory");
            ev.shader |= SHADER_MARKERS.stream().anyMatch(head::contains);
            ev.graphics |= ev.gpu;
        }
        if (ev.crashReport != null) {
            // 只看出错原因和堆栈：后面的 System Details 列出了全部模组，装了 Iris/Oculus 的话每份报告都会"提到"光影
            String text = ev.crashReport;
            int details = text.indexOf("-- System Details --");
            if (details >= 0) {
                text = text.substring(0, details);
            }
            text = text.length() > 60000 ? text.substring(0, 60000) : text;
            ev.oom |= text.contains("java.lang.OutOfMemoryError") || text.contains("GL_OUT_OF_MEMORY");
            String description = "";
            for (String line : text.split("\\R")) {
                if (line.startsWith("Description:")) {
                    description = line;
                    break;
                }
            }
            String desc = description;
            ev.graphics |= GRAPHICS_DESCRIPTIONS.stream().anyMatch(desc::contains) || GRAPHICS_MARKERS.stream().anyMatch(text::contains);
            ev.shader |= SHADER_MARKERS.stream().anyMatch(text::contains);
        }
        return ev;
    }

    private static Optional<Path> newest(Path dir, Predicate<Path> filter, long since) {
        if (!Files.isDirectory(dir)) {
            return Optional.empty();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(filter).filter(p -> modified(p) >= since).max(Comparator.comparingLong(GraphicsGuard::modified));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    // ------------------------------------------------------------------ 修改设置

    /** 关闭 Iris / Oculus 的光影（只改 enableShaders 这一行，原文件先备份）。 */
    private static boolean disableShaders(String loader, LaunchReport report) {
        Path props = ConfigGuard.gameDir().resolve("config").resolve(loader + ".properties");
        if (!Files.isRegularFile(props)) {
            return false;
        }
        try {
            List<String> lines = Files.readAllLines(props, StandardCharsets.UTF_8);
            boolean changed = false;
            boolean present = false;
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line.trim().startsWith("enableShaders")) {
                    present = true;
                    if (line.contains("true")) {
                        lines.set(i, "enableShaders=false");
                        changed = true;
                    }
                }
            }
            // Iris 把缺少这一项当成"开启"（!"false".equals(...)），只改已有的行关不掉
            if (!present) {
                lines.add("enableShaders=false");
                changed = true;
            }
            if (!changed) {
                return false;
            }
            String backup = backup(props);
            Files.write(props, lines, StandardCharsets.UTF_8);
            report.configRestored.add(new LaunchReport.ConfigChange("config/" + loader + ".properties", "SHADER_OFF", null, backup));
            return true;
        } catch (IOException e) {
            EarlyLog.LOG.warn("[紧急避险] 无法修改光影设置", e);
            return false;
        }
    }

    /** 改 options.txt 里启用的资源包（原文件先备份）。 */
    private static boolean setResourcePacks(List<String> packs, LaunchReport report) {
        Path options = ConfigGuard.gameDir().resolve("options.txt");
        if (!Files.isRegularFile(options)) {
            return false;
        }
        try {
            List<String> lines = Files.readAllLines(options, StandardCharsets.UTF_8);
            JsonArray array = new JsonArray();
            packs.forEach(array::add);
            boolean found = false;
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line.startsWith("resourcePacks:")) {
                    lines.set(i, "resourcePacks:" + array);
                    found = true;
                } else if (line.startsWith("incompatibleResourcePacks:")) {
                    JsonArray kept = new JsonArray();
                    for (String id : parseArray(line.substring("incompatibleResourcePacks:".length()))) {
                        if (packs.contains(id)) {
                            kept.add(id);
                        }
                    }
                    lines.set(i, "incompatibleResourcePacks:" + kept);
                }
            }
            if (!found) {
                return false;
            }
            String backup = backup(options);
            Files.write(options, lines, StandardCharsets.UTF_8);
            report.configRestored.add(new LaunchReport.ConfigChange("options.txt", "PACKS_OFF", null, backup));
            return true;
        } catch (IOException e) {
            EarlyLog.LOG.warn("[紧急避险] 无法修改资源包设置", e);
            return false;
        }
    }

    private static String backup(Path file) throws IOException {
        Path game = ConfigGuard.gameDir();
        Path target = ShelterFiles.dir().resolve("config-backups").resolve(ConfigGuard.timestamp()).resolve(game.relativize(file).toString());
        Files.createDirectories(target.getParent());
        Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
        return game.relativize(target).toString().replace('\\', '/');
    }

    // ------------------------------------------------------------------ 工具

    private static String names(List<String> ids) {
        List<String> out = new ArrayList<>();
        for (String id : ids) {
            out.add(id.startsWith("file/") ? id.substring(5) : id);
        }
        return String.join("、", out);
    }

    private static List<String> parseArray(String json) {
        List<String> out = new ArrayList<>();
        try {
            for (JsonElement e : JsonParser.parseString(json).getAsJsonArray()) {
                out.add(e.getAsString());
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    @Nullable
    private static JsonObject readJson(Path file) {
        try {
            if (Files.isRegularFile(file)) {
                return JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static void writeJson(Path file, JsonObject json) {
        try {
            Files.writeString(file, ShelterFiles.GSON.toJson(json), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }

    private static String str(JsonObject o, String key, String def) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : def;
    }

    private static long num(JsonObject o, String key) {
        try {
            return o.has(key) ? o.get(key).getAsLong() : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }

    private static List<String> strings(JsonObject o, String key) {
        List<String> out = new ArrayList<>();
        if (o.has(key) && o.get(key).isJsonArray()) {
            o.getAsJsonArray(key).forEach(e -> out.add(e.getAsString()));
        }
        return out;
    }

    @Nullable
    private static String read(Path p) {
        try {
            byte[] bytes = Files.readAllBytes(p);
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    private static long modified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }
}
