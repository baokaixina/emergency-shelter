package com.emergencyshelter.early;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import net.neoforged.fml.loading.FMLPaths;
import org.jetbrains.annotations.Nullable;

/**
 * 紧急避险在游戏目录下的工作文件夹：{@code <游戏目录>/emergencyshelter/}。
 * early 部分与游戏内部分通过这里的 JSON 文件交换信息。
 */
public final class ShelterFiles {
    public static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** 本次启动的检查结果，游戏内部分读取它来提示玩家。 */
    public static final String REPORT = "report.json";
    /** 启动中标记：游戏成功启动后由游戏内部分删除，残留说明上次启动失败。 */
    public static final String LAUNCH_MARKER = "launch.marker";
    /** 自动修复（崩溃后禁用出错模组）的持久状态。 */
    public static final String HEAL_STATE = "heal.json";
    /** 上次启动失败时记录下来的、可以明确归因到某个模组的加载错误。 */
    public static final String PENDING_ISSUES = "pending-issues.json";
    /** 依赖分析缓存：mods 文件夹没有变化时直接复用上次的结论。 */
    public static final String ANALYSIS_CACHE = "analysis-cache.json";
    /** 玩家可编辑的设置。 */
    public static final String SETTINGS = "settings.json";

    private ShelterFiles() {
    }

    public static Path dir() {
        Path dir = FMLPaths.GAMEDIR.get().resolve("emergencyshelter");
        try {
            Files.createDirectories(dir);
        } catch (IOException ignored) {
        }
        return dir;
    }

    public static Path file(String name) {
        return dir().resolve(name);
    }

    @Nullable
    public static <T> T read(String name, Class<T> type) {
        Path path = file(name);
        if (!Files.isRegularFile(path)) {
            return null;
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return GSON.fromJson(reader, type);
        } catch (Exception e) {
            EarlyLog.LOG.warn("[紧急避险] 无法读取 {}，按不存在处理", path, e);
            return null;
        }
    }

    /** 先写临时文件再替换，避免崩溃时留下半个 JSON。 */
    public static void write(String name, Object value) {
        Path path = file(name);
        Path tmp = path.resolveSibling(name + ".tmp");
        try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            GSON.toJson(value, writer);
        } catch (Exception e) {
            // 临时文件没写完整（磁盘满、没有权限）：保留原文件，不能用半个文件覆盖它
            EarlyLog.LOG.error("[紧急避险] 无法写入 {}", path, e);
            try {
                Files.deleteIfExists(tmp);
            } catch (Exception ignored) {
            }
            return;
        }
        try {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            // 有的文件系统不支持原子替换：退回普通替换（临时文件已经完整写好）
            try {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception e2) {
                EarlyLog.LOG.error("[紧急避险] 无法写入 {}", path, e2);
            }
        }
    }

    public static void delete(String name) {
        try {
            Files.deleteIfExists(file(name));
        } catch (IOException e) {
            EarlyLog.LOG.warn("[紧急避险] 无法删除 {}", name, e);
        }
    }
}
