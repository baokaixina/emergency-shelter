package com.emergencyshelter.salvage;

import com.emergencyshelter.EmergencyShelter;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** 统计本次运行中转为占位 / 还原的数量（区块在多个线程上加载，所以要线程安全）。 */
public final class SalvageStats {
    private static final Map<String, AtomicLong> PLACEHOLDERS = new ConcurrentHashMap<>();
    private static final Map<String, AtomicLong> STRIPPED = new ConcurrentHashMap<>();
    private static final Map<String, AtomicLong> RESTORED = new ConcurrentHashMap<>();

    private SalvageStats() {
    }

    public static void placeholder(String kind, String id) {
        if (PLACEHOLDERS.computeIfAbsent(kind + " " + id, k -> new AtomicLong()).getAndIncrement() == 0) {
            EmergencyShelter.LOGGER.warn("[紧急避险] {} {} 所属的模组不存在，已转为占位保存（之后同类不再提示）", kind, id);
        }
    }

    public static void stripped(String id, String component) {
        if (STRIPPED.computeIfAbsent(id + " " + component, k -> new AtomicLong()).getAndIncrement() == 0) {
            EmergencyShelter.LOGGER.warn("[紧急避险] 物品 {} 上的数据 {} 无法读取，已暂存在物品上（之后同类不再提示）", id, component);
        }
    }

    public static void restored(String kind, String id) {
        if (RESTORED.computeIfAbsent(kind + " " + id, k -> new AtomicLong()).getAndIncrement() == 0) {
            EmergencyShelter.LOGGER.info("[紧急避险] {} {} 的模组已经回来，占位已还原", kind, id);
        }
    }

    public static long placeholderTotal() {
        return PLACEHOLDERS.values().stream().mapToLong(AtomicLong::get).sum();
    }

    public static long restoredTotal() {
        return RESTORED.values().stream().mapToLong(AtomicLong::get).sum();
    }

    public static long strippedTotal() {
        return STRIPPED.values().stream().mapToLong(AtomicLong::get).sum();
    }

    public static Map<String, Long> placeholderSnapshot() {
        Map<String, Long> map = new TreeMap<>();
        PLACEHOLDERS.forEach((k, v) -> map.put(k, v.get()));
        return map;
    }

    public static void reset() {
        PLACEHOLDERS.clear();
        STRIPPED.clear();
        RESTORED.clear();
    }
}
