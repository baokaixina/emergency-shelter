package com.emergencyshelter.world;

import com.emergencyshelter.EmergencyShelter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.jetbrains.annotations.Nullable;

/**
 * 世界生成保护。原版生成新区块时，任何一个模组的地物（矿石、树、装饰）或结构出错，都会让游戏崩溃（"Feature placement"、
 * "Exception generating new chunk"），而且只要玩家再走到那里就会再崩一次。这里改成：
 * <ul>
 *     <li>出错的地物、结构、生物生成跳过，区块其余部分照常生成；</li>
 *     <li>多个模组给生物群系加地物的顺序互相矛盾（"Feature order cycle found"）时，改用一个兼容的顺序，而不是无法打开存档；</li>
 *     <li>卡住导致游戏被关闭的地物或结构，以后在那个区块跳过（见 {@link HangGuard}）。</li>
 * </ul>
 */
public final class WorldgenGuard {
    /** 以后跳过的世界生成步骤：{@link #skipKey}。 */
    static final Set<String> SKIP = ConcurrentHashMap.newKeySet();
    private static final Map<String, AtomicInteger> FAILURES = new ConcurrentHashMap<>();
    private static final int RECORD_PER_ID = 3;
    private static final Pattern BIOME_IN_MESSAGE = Pattern.compile("worldgen/biome / ([^\\]]+)\\]");

    private WorldgenGuard() {
    }

    public static String skipKey(ResourceKey<Level> dim, ChunkPos pos, String id) {
        return dim.location() + "|" + pos.x + "," + pos.z + "|" + id;
    }

    // ------------------------------------------------------------------ 地物

    public interface FeatureCall {
        boolean place();
    }

    /**
     * 只保护原版生成区块的流程（WorldGenRegion 本身）。其它模组用自己的 WorldGenRegion 子类在自己的线程里生成
     * （例如 Distant Horizons 生成远景 LOD），它们有自己的出错处理；如果在这里介入，会把只在远景生成里出现的错误报告给玩家，
     * 还可能把远景线程的等待误记为"卡住"，导致以后真正生成这个区块时跳过地物。
     */
    public static boolean isVanillaRegion(net.minecraft.world.level.WorldGenLevel level) {
        return level.getClass() == net.minecraft.server.level.WorldGenRegion.class;
    }

    public static boolean placeFeature(PlacedFeature feature, ServerLevel level, BlockPos origin, FeatureCall call) {
        if (!WorldGuard.enabled(s -> s.worldgenGuard)) {
            return call.place();
        }
        ChunkPos chunk = new ChunkPos(origin);
        String id = null;
        if (!SKIP.isEmpty()) {
            id = featureId(level, feature);
            if (SKIP.contains(skipKey(level.dimension(), chunk, id))) {
                return false;
            }
        }
        HangGuard.Slot slot = HangGuard.begin(HangGuard.FEATURE, new LazyId(() -> featureId(level, feature)), level, chunk.toLong());
        try {
            return call.place();
        } catch (Throwable t) {
            if (TickGuard.fatal(t)) {
                throw WorldGuard.sneakyThrow(t);
            }
            onFailure("WORLDGEN_FEATURE", id != null ? id : featureId(level, feature), level, chunk, t);
            return false;
        } finally {
            HangGuard.end(slot);
        }
    }

    // ------------------------------------------------------------------ 结构

    public static Consumer<StructureStart> guardStructurePlacement(Consumer<StructureStart> original, ServerLevel level, ChunkPos chunk) {
        if (!WorldGuard.enabled(s -> s.worldgenGuard)) {
            return original;
        }
        return start -> {
            String id = null;
            if (!SKIP.isEmpty()) {
                id = structureId(level, start.getStructure());
                if (SKIP.contains(skipKey(level.dimension(), chunk, id))) {
                    return;
                }
            }
            HangGuard.Slot slot = HangGuard.begin(HangGuard.STRUCTURE, new LazyId(() -> structureId(level, start.getStructure())), level, chunk.toLong());
            try {
                original.accept(start);
            } catch (Throwable t) {
                if (TickGuard.fatal(t)) {
                    throw WorldGuard.sneakyThrow(t);
                }
                onFailure("WORLDGEN_STRUCTURE", id != null ? id : structureId(level, start.getStructure()), level, chunk, t);
            } finally {
                HangGuard.end(slot);
            }
        };
    }

    /** 决定结构"要不要在这里生成"时出错：这次不生成它。 */
    public static boolean tryGenerateStructure(Structure structure, @Nullable ServerLevel level, ChunkPos chunk, Supplier<Boolean> call) {
        if (!WorldGuard.enabled(s -> s.worldgenGuard)) {
            return call.get();
        }
        try {
            return call.get();
        } catch (Throwable t) {
            if (TickGuard.fatal(t)) {
                throw WorldGuard.sneakyThrow(t);
            }
            String id = level == null ? String.valueOf(structure) : structureId(level, structure);
            onFailure("WORLDGEN_STRUCTURE", id, level, chunk, t);
            return false;
        }
    }

    /** 新区块里生成初始生物时出错：这次不生成。 */
    public static void spawnOriginalMobs(@Nullable ServerLevel level, ChunkPos chunk, Runnable call) {
        if (!WorldGuard.enabled(s -> s.worldgenGuard)) {
            call.run();
            return;
        }
        try {
            call.run();
        } catch (Throwable t) {
            if (TickGuard.fatal(t)) {
                throw WorldGuard.sneakyThrow(t);
            }
            onFailure("WORLDGEN_MOBS", "spawn", level, chunk, t);
        }
    }

    private static void onFailure(String kind, String id, @Nullable ServerLevel level, ChunkPos chunk, Throwable t) {
        // 只是记录：出错也照样跳过这一步，区块照常生成
        com.emergencyshelter.Defense.quietly("WorldgenGuard.onFailure", () -> report(kind, id, level, chunk, t));
    }

    private static void report(String kind, String id, @Nullable ServerLevel level, ChunkPos chunk, Throwable t) {
        int n = FAILURES.computeIfAbsent(kind + " " + id, k -> new AtomicInteger()).incrementAndGet();
        String mod = Culprits.modOf(t);
        String where = level == null ? chunk.toString()
                : WorldGuard.where(level.dimension(), new BlockPos(chunk.getMiddleBlockX(), 64, chunk.getMiddleBlockZ()));
        String detail = WorldGuard.message(t) + (mod == null ? "" : "（" + Culprits.displayName(mod) + "）");
        if (n == 1) {
            EmergencyShelter.LOGGER.error("[紧急避险] 生成区块时 {} 出错，已跳过它，区块其余部分照常生成（原版会崩溃）：{} @ {}", id, detail, where, t);
        } else if (n <= 10 || n % 100 == 0) {
            EmergencyShelter.LOGGER.warn("[紧急避险] 生成区块时 {} 再次出错（第 {} 次），已跳过：{} @ {}", id, n, detail, where);
        }
        if (n <= RECORD_PER_ID) {
            WorldGuard.record(kind, id, where, detail);
        }
    }

    private static String featureId(ServerLevel level, PlacedFeature feature) {
        try {
            return level.registryAccess().registryOrThrow(Registries.PLACED_FEATURE).getResourceKey(feature)
                    .map(k -> k.location().toString()).orElseGet(feature::toString);
        } catch (Throwable t) {
            return String.valueOf(feature);
        }
    }

    private static String structureId(ServerLevel level, Structure structure) {
        try {
            return level.registryAccess().registryOrThrow(Registries.STRUCTURE).getResourceKey(structure)
                    .map(k -> k.location().toString()).orElseGet(structure::toString);
        } catch (Throwable t) {
            return String.valueOf(structure);
        }
    }

    /** 卡死记录需要名字时才去查，平时不花这个时间。 */
    private record LazyId(Supplier<String> id) {
        @Override
        public String toString() {
            return id.get();
        }
    }

    // ------------------------------------------------------------------ 地物顺序冲突

    /**
     * 原版要求所有生物群系里地物的先后顺序互不矛盾，否则抛出 "Feature order cycle found" 导致存档打不开。
     * 这里按生物群系的先后，逐条加入"A 在 B 之前"的约束，跳过会和已有约束矛盾的那几条，再排出一个顺序。
     * 绝大多数地物的相对顺序与原来一致，只有互相矛盾的那几个会有一处先后不同。
     */
    public static <T> List<FeatureSorter.StepFeatureData> lenientOrder(List<T> sources, Function<T, List<HolderSet<PlacedFeature>>> toFeatures,
                                                                       String cycleMessage) {
        int steps = 0;
        for (T source : sources) {
            steps = Math.max(steps, toFeatures.apply(source).size());
        }
        List<FeatureSorter.StepFeatureData> result = new ArrayList<>();
        int skipped = 0;
        for (int step = 0; step < steps; step++) {
            Map<PlacedFeature, Integer> firstSeen = new LinkedHashMap<>();
            Map<PlacedFeature, Set<PlacedFeature>> edges = new HashMap<>();
            for (T source : sources) {
                List<HolderSet<PlacedFeature>> sets = toFeatures.apply(source);
                if (step >= sets.size()) {
                    continue;
                }
                PlacedFeature previous = null;
                for (Holder<PlacedFeature> holder : sets.get(step)) {
                    PlacedFeature feature = holder.value();
                    firstSeen.putIfAbsent(feature, firstSeen.size());
                    edges.computeIfAbsent(feature, k -> new HashSet<>());
                    if (previous != null && previous != feature && !edges.get(previous).contains(feature)) {
                        if (reaches(edges, feature, previous)) {
                            skipped++;
                        } else {
                            edges.get(previous).add(feature);
                        }
                    }
                    previous = feature;
                }
            }
            // 拓扑排序；没有先后约束的按第一次出现的顺序
            Map<PlacedFeature, Integer> indegree = new HashMap<>();
            firstSeen.keySet().forEach(f -> indegree.put(f, 0));
            edges.values().forEach(targets -> targets.forEach(t -> indegree.merge(t, 1, Integer::sum)));
            PriorityQueue<PlacedFeature> ready = new PriorityQueue<>(Comparator.comparingInt(firstSeen::get));
            indegree.forEach((f, d) -> {
                if (d == 0) {
                    ready.add(f);
                }
            });
            List<PlacedFeature> ordered = new ArrayList<>();
            while (!ready.isEmpty()) {
                PlacedFeature f = ready.poll();
                ordered.add(f);
                for (PlacedFeature t : edges.getOrDefault(f, Set.of())) {
                    if (indegree.merge(t, -1, Integer::sum) == 0) {
                        ready.add(t);
                    }
                }
            }
            result.add(new FeatureSorter.StepFeatureData(ordered, Util.createIndexIdentityLookup(ordered)));
        }
        // 原版的信息里是一长串对象描述，只取出生物群系的名字
        List<String> biomes = new ArrayList<>();
        Matcher m = BIOME_IN_MESSAGE.matcher(cycleMessage);
        while (m.find() && biomes.size() < 20) {
            biomes.add(m.group(1));
        }
        String detail = biomes.isEmpty() ? cycleMessage : "互相矛盾的生物群系：" + String.join("、", biomes);
        EmergencyShelter.LOGGER.warn("[紧急避险] 生物群系的地物顺序互相矛盾（{}）。原版会无法打开存档；已改用兼容顺序，忽略了 {} 条矛盾的先后约束。", detail, skipped);
        String what = String.valueOf(skipped);
        if (WorldGuard.server() != null) {
            WorldGuard.record("FEATURE_ORDER", what, null, detail);
        } else {
            WorldGuard.recordBeforeStart("FEATURE_ORDER", what, detail);
        }
        return result;
    }

    private static boolean reaches(Map<PlacedFeature, Set<PlacedFeature>> edges, PlacedFeature from, PlacedFeature to) {
        Deque<PlacedFeature> stack = new ArrayDeque<>();
        Set<PlacedFeature> seen = new HashSet<>();
        stack.push(from);
        while (!stack.isEmpty()) {
            PlacedFeature f = stack.pop();
            if (f == to) {
                return true;
            }
            if (seen.add(f)) {
                for (PlacedFeature next : edges.getOrDefault(f, Set.of())) {
                    stack.push(next);
                }
            }
        }
        return false;
    }
}
