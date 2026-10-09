package com.emergencyshelter.world;

import com.emergencyshelter.EmergencyShelter;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 存档"膨胀"检查（只报告，不改动任何文件）。
 * <ul>
 *     <li>某个数据文件异常地大（level.dat、玩家数据、模组数据、统计、进度）：读写都慢，容易超出上限、读取失败；</li>
 *     <li>某个数据文件比上次打开存档时涨了好几倍：多半是某个模组的数据在不停累积；</li>
 *     <li>某个区块大到要单独存放（.mcc 文件）：塞满物品的管道、箱子或大量实体，联机时靠近那里的玩家容易被踢出。</li>
 * </ul>
 * 每次打开存档时在后台检查一次，同一个问题只提示一次。
 */
public final class DataBloat {
    private static final long LARGE = 64L << 20;
    private static final long GROWTH_MIN = 16L << 20;
    private static final String FILE = "sizes.json";
    private static final Pattern MCC = Pattern.compile("c\\.(-?\\d+)\\.(-?\\d+)\\.mcc");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private DataBloat() {
    }

    static final class Record {
        Map<String, Long> sizes = new TreeMap<>();
        Set<String> reported = new HashSet<>();
    }

    static void checkAsync(Path world) {
        Thread t = new Thread(() -> {
            try {
                check(world);
            } catch (Throwable e) {
                EmergencyShelter.LOGGER.debug("[紧急避险] 检查存档体积时出错", e);
            }
        }, "EmergencyShelter-DataBloat");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    static void check(Path world) throws IOException {
        Path recordFile = world.resolve("emergencyshelter").resolve(FILE);
        Record previous = null;
        if (Files.isRegularFile(recordFile)) {
            try {
                previous = GSON.fromJson(Files.readString(recordFile, StandardCharsets.UTF_8), Record.class);
            } catch (Exception ignored) {
            }
        }
        Record next = new Record();
        if (previous != null && previous.reported != null) {
            next.reported.addAll(previous.reported);
        }
        List<Path> files = new ArrayList<>();
        List<Path> oversizedChunks = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(world, 6)) {
            walk.filter(Files::isRegularFile).forEach(p -> {
                String rel = rel(world, p);
                if (rel.startsWith("emergencyshelter/")) {
                    return;
                }
                String name = p.getFileName().toString();
                if (MCC.matcher(name).matches()) {
                    oversizedChunks.add(p);
                } else if (rel.equals("level.dat") || (name.endsWith(".dat") && (rel.contains("/data/") || rel.startsWith("data/") || rel.startsWith("playerdata/")))
                        || ((rel.startsWith("stats/") || rel.startsWith("advancements/")) && name.endsWith(".json"))) {
                    files.add(p);
                }
            });
        }
        for (Path p : files) {
            String rel = rel(world, p);
            long size = Files.size(p);
            next.sizes.put(rel, size);
            Long before = previous == null || previous.sizes == null ? null : previous.sizes.get(rel);
            if (size >= LARGE && next.reported.add("large " + rel + " " + (size >> 26))) {
                report("DATA_LARGE", rel, null, mb(size) + "，读写都会变慢，并且更容易读取失败" + owner(rel));
            } else if (before != null && size >= before * 3 && size - before >= GROWTH_MIN && next.reported.add("growth " + rel + " " + (size >> 24))) {
                report("DATA_GROWTH", rel, null, "从 " + mb(before) + " 涨到 " + mb(size) + "，可能是某个模组的数据在不停累积" + owner(rel));
            }
        }
        int shown = 0;
        for (Path p : oversizedChunks) {
            String rel = rel(world, p);
            if (!next.reported.add("mcc " + rel + " " + (Files.size(p) >> 22))) {
                continue;
            }
            Matcher m = MCC.matcher(p.getFileName().toString());
            if (!m.matches() || shown++ >= 10) {
                continue;
            }
            int cx = Integer.parseInt(m.group(1));
            int cz = Integer.parseInt(m.group(2));
            String dim = dimensionOf(rel);
            String where = dim + " " + (cx * 16 + 8) + ", 64, " + (cz * 16 + 8);
            report("CHUNK_OVERSIZED", rel, where, "区块数据 " + mb(Files.size(p)) + "，超过了常规存放上限" + (rel.contains("entities/") ? "（实体过多）" : "（多半是存了大量物品的方块）")
                    + "，联机时靠近这里的玩家可能被踢出");
        }
        Files.createDirectories(recordFile.getParent());
        Files.writeString(recordFile, GSON.toJson(next), StandardCharsets.UTF_8);
    }

    private static void report(String kind, String what, String where, String detail) {
        EmergencyShelter.LOGGER.warn("[紧急避险] {}：{}", what, detail);
        WorldGuard.record(kind, what, where, detail);
    }

    private static String owner(String rel) {
        String name = rel.substring(rel.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        if (!rel.contains("data/") || name.startsWith("map_") || name.startsWith("raids") || name.startsWith("scoreboard")
                || name.startsWith("random_sequences") || name.startsWith("command_storage")) {
            return name.startsWith("scoreboard") || name.startsWith("command_storage") ? "（通常来自数据包或命令方块）" : "";
        }
        String token = name.split("[-_.]")[0];
        return token.isEmpty() ? "" : "（可能属于模组 " + token + "）";
    }

    private static String dimensionOf(String rel) {
        if (rel.startsWith("DIM-1/")) {
            return "minecraft:the_nether";
        }
        if (rel.startsWith("DIM1/")) {
            return "minecraft:the_end";
        }
        if (rel.startsWith("dimensions/")) {
            String[] parts = rel.split("/");
            if (parts.length > 3) {
                return parts[1] + ":" + parts[2];
            }
        }
        return "minecraft:overworld";
    }

    private static String rel(Path world, Path p) {
        return world.relativize(p).toString().replace('\\', '/');
    }

    private static String mb(long bytes) {
        return String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0);
    }
}
