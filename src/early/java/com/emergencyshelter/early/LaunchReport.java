package com.emergencyshelter.early;

import java.util.ArrayList;
import java.util.List;

/**
 * 写入 {@code report.json} 的启动检查结果。游戏内部分按同样的字段名读取。
 */
public final class LaunchReport {
    public int schema = 1;
    public long time = System.currentTimeMillis();
    public String side;
    public String modsFingerprint;
    /** 被跳过（禁用）的模组文件。 */
    public List<DisabledFile> disabled = new ArrayList<>();
    /** 被需要、但整个 mods 文件夹里都找不到的模组（通常就是被删掉的前置）。 */
    public List<MissingMod> missing = new ArrayList<>();
    /** 无法读取、已忽略的文件（损坏的 jar 等）。 */
    public List<IgnoredFile> ignored = new ArrayList<>();
    /** 上次启动失败、本次自动修复的说明。 */
    public List<String> healNotes = new ArrayList<>();
    /** 其他提示。 */
    public List<String> notes = new ArrayList<>();
    /** 本次启动前被换回"上次正常"版本的配置文件。 */
    public List<ConfigChange> configRestored = new ArrayList<>();

    public boolean isEmpty() {
        return disabled.isEmpty() && missing.isEmpty() && ignored.isEmpty() && healNotes.isEmpty() && configRestored.isEmpty();
    }

    public static final class DisabledFile {
        public String file;
        public List<ModRef> mods = new ArrayList<>();
        /** DEPENDENCY：依赖问题；HEAL：上次启动时出错被自动禁用。 */
        public String cause;
        public List<Reason> reasons = new ArrayList<>();
    }

    public static final class ModRef {
        public String id;
        public String name;
        public String version;

        public ModRef() {
        }

        public ModRef(String id, String name, String version) {
            this.id = id;
            this.name = name;
            this.version = version;
        }
    }

    public static final class Reason {
        /** MISSING / VERSION / DISABLED_DEP / INCOMPATIBLE / LANGUAGE / DUPLICATE / HEALED */
        public String kind;
        /** 提出这条要求的模组 */
        public String mod;
        /** 被要求的模组 */
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
        /** DELETED：被删掉，已补回；CORRUPT：写坏了，已替换；HEAL / HANG / WORLD：启动出错、卡住、进存档崩溃后恢复；MANUAL：玩家要求恢复。 */
        public String reason;
        /** 损坏的原因，或者相关的模组 id。 */
        public String detail;
        /** 被换下来的版本备份在哪里（相对游戏目录）。 */
        public String backup;

        public ConfigChange() {
        }

        public ConfigChange(String file, String reason, String detail, String backup) {
            this.file = file;
            this.reason = reason;
            this.detail = detail;
            this.backup = backup;
        }
    }
}
