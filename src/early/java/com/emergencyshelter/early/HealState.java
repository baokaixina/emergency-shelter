package com.emergencyshelter.early;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 自动修复状态：上次启动在加载阶段出错时，把出错的模组记下来，之后的启动跳过它。
 * 只要 mods 文件夹里出现了新的或更新过的文件（玩家可能补上了缺的东西），这些记录就会失效并重新尝试。
 */
final class HealState {
    List<Entry> entries = new ArrayList<>();
    /** 连续多少次启动失败并触发了自动修复；到达上限后停止自动禁用，避免越修越多。 */
    int consecutiveHeals;
    /** 已经先恢复过配置的模组（模组 id → 时间）。同一个模组恢复配置后仍然出错，才禁用它。 */
    Map<String, Long> configRolledBack = new HashMap<>();
    /** 无法归因的启动失败后，是否已经把所有改动过的配置恢复过一次。 */
    boolean configRolledBackAll;
    /** 启动时卡在某个模组里的次数。 */
    Map<String, Integer> hangs = new HashMap<>();
    /** 进入存档时崩溃、已经处理过的次数（模组 id → 次数）。 */
    Map<String, Integer> worldRollbacks = new HashMap<>();

    void fixNulls() {
        if (entries == null) entries = new ArrayList<>();
        if (configRolledBack == null) configRolledBack = new HashMap<>();
        if (hangs == null) hangs = new HashMap<>();
        if (worldRollbacks == null) worldRollbacks = new HashMap<>();
    }

    static final class Entry {
        String file;
        List<String> modIds = new ArrayList<>();
        String reason;
        long time;
        /** 记录时 mods 文件夹的快照（文件名|大小|修改时间）。 */
        List<String> filesAtHeal = new ArrayList<>();
        /** 脚本模组（kubejs / crafttweaker）：禁用时脚本目录的指纹。脚本改过之后重新启用。 */
        String scriptOwner;
        String scriptStamp;
    }
}
