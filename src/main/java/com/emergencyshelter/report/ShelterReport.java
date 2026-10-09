package com.emergencyshelter.report;

import com.emergencyshelter.EmergencyShelter;
import com.google.gson.Gson;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.neoforged.fml.loading.FMLPaths;
import org.jetbrains.annotations.Nullable;

/**
 * 启动前检查写下的 {@code emergencyshelter/report.json}（字段与 early 部分的 LaunchReport 一致）。
 */
public final class ShelterReport {
    public int schema;
    public long time;
    public String side;
    public List<DisabledFile> disabled = new ArrayList<>();
    public List<MissingMod> missing = new ArrayList<>();
    public List<IgnoredFile> ignored = new ArrayList<>();
    public List<String> healNotes = new ArrayList<>();
    public List<String> notes = new ArrayList<>();
    public String modsFingerprint;
    public List<ConfigChange> configRestored = new ArrayList<>();
    /** 本次启动的耗时统计（来自 early 部分，不在 report.json 里）。 */
    @Nullable
    public transient StartupSummary startup;

    private static ShelterReport current;
    private static volatile Settings settings;

    public static Path dir() {
        return FMLPaths.GAMEDIR.get().resolve("emergencyshelter");
    }

    public static synchronized ShelterReport get() {
        if (current == null) {
            current = load();
        }
        return current;
    }

    private static ShelterReport load() {
        Path file = dir().resolve("report.json");
        if (Files.isRegularFile(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                ShelterReport report = new Gson().fromJson(reader, ShelterReport.class);
                if (report != null) {
                    report.fixNulls();
                    return report;
                }
            } catch (Exception e) {
                EmergencyShelter.LOGGER.warn("无法读取启动报告 {}", file, e);
            }
        }
        return new ShelterReport();
    }

    private void fixNulls() {
        if (disabled == null) disabled = new ArrayList<>();
        if (missing == null) missing = new ArrayList<>();
        if (ignored == null) ignored = new ArrayList<>();
        if (healNotes == null) healNotes = new ArrayList<>();
        if (notes == null) notes = new ArrayList<>();
        if (configRestored == null) configRestored = new ArrayList<>();
    }

    /** emergencyshelter/settings.json 中游戏内部分关心的选项。 */
    public static final class Settings {
        public boolean enabled = true;
        public boolean worldGuard = true;
        public boolean graphicsGuard = true;
        public int entityLimitPerChunk = 2000;
        // 存档保护的各项功能，可以单独关掉（worldGuard 为 false 时全部关闭）
        public boolean freezeBrokenBlockEntities = true;
        public boolean quarantineBrokenEntities = true;
        public boolean moveBrokenItems = true;
        public boolean skipBrokenDataLoaders = true;
        public boolean worldgenGuard = true;
        public boolean hangGuard = true;
        public boolean backupBeforeSuppress = true;
        public boolean safeMode = false;
    }

    public static Settings settings() {
        // 地物放置、数据包编码等热路径每次都会调用：读到之后不再加锁
        Settings s = settings;
        return s != null ? s : loadSettings();
    }

    private static synchronized Settings loadSettings() {
        if (settings != null) {
            return settings;
        }
        Settings loaded = new Settings();
        // 每个钩子都会问这里：这里绝不能抛出异常，读不到就用默认设置
        try {
            Path file = dir().resolve("settings.json");
            if (Files.isRegularFile(file)) {
                try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    Settings s = new Gson().fromJson(reader, Settings.class);
                    if (s != null) {
                        loaded = s;
                    }
                }
            }
        } catch (Throwable e) {
            EmergencyShelter.LOGGER.warn("[紧急避险] 无法读取设置文件 settings.json，使用默认设置", e);
        }
        loaded.worldGuard &= loaded.enabled;
        loaded.graphicsGuard &= loaded.enabled;
        if (!loaded.worldGuard) {
            loaded.entityLimitPerChunk = 0;
            loaded.safeMode = false;
        }
        // 全部处理完再公开，其他线程不会读到一半
        settings = loaded;
        return loaded;
    }

    /** 是否有需要告诉玩家的事情。 */
    public boolean hasNews() {
        return !disabled.isEmpty() || !missing.isEmpty() || !healNotes.isEmpty() || !configRestored.isEmpty()
                || ignored.stream().anyMatch(i -> !"OLDER_DUPLICATE".equals(i.reason))
                || (startup != null && startup.isNotable());
    }

    /** 游戏已经成功启动：删除启动标记，告诉下次启动"上次是成功的"。 */
    public static void markLaunchSucceeded() {
        try {
            Files.deleteIfExists(dir().resolve("launch.marker"));
        } catch (Exception e) {
            EmergencyShelter.LOGGER.warn("无法删除启动标记", e);
        }
    }

    public static final class DisabledFile {
        public String file;
        public List<ModRef> mods = new ArrayList<>();
        public String cause;
        public List<Reason> reasons = new ArrayList<>();

        public String displayName() {
            if (mods != null && !mods.isEmpty() && mods.getFirst().name != null) {
                return mods.getFirst().name;
            }
            return file;
        }
    }

    public static final class ModRef {
        public String id;
        public String name;
        public String version;
    }

    public static final class Reason {
        public String kind;
        public String mod;
        public String dependency;
        public String range;
        public String found;
        public String detail;
    }

    public static final class MissingMod {
        public String id;
        public List<String> requiredBy = new ArrayList<>();
    }

    public static final class IgnoredFile {
        public String file;
        public String reason;
        public String detail;
    }

    public static final class ConfigChange {
        public String file;
        public String reason;
        public String detail;
        public String backup;
    }

    /** 与 early 部分 StartupProfiler.Summary 的字段一致。 */
    public static final class StartupSummary {
        public long totalMs;
        public Long baselineMs;
        public boolean slow;
        public boolean notable;
        public boolean accepted;
        public List<ModTime> mods = new ArrayList<>();

        /** 值得告诉玩家：整体明显变慢，或者某个模组明显变慢。 */
        public boolean isNotable() {
            return notable || slow || (mods != null && mods.stream().anyMatch(m -> m.slow));
        }
    }

    public static final class ModTime {
        public String id;
        public String name;
        public long busyMs;
        public Long baselineMs;
        public boolean slow;
        public List<String> changedConfigs = new ArrayList<>();
    }
}
