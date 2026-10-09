package com.emergencyshelter.world;

import com.emergencyshelter.EmergencyShelter;
import com.emergencyshelter.report.ShelterReport;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

/**
 * 实体数量爆炸（刷怪塔、坏掉的农场、复制漏洞产生成千上万的掉落物或生物）：一靠近那个区块服务端就卡死或崩溃，存档等于废了。
 * 加载区块时，同一种实体超过上限的部分不放进世界，而是原样保存到
 * {@code <存档>/emergencyshelter/quarantine/entities/}，以后可以用命令放回来。
 */
public final class EntityOverflow {
    private static final ThreadLocal<Boolean> BYPASS = ThreadLocal.withInitial(() -> false);

    private EntityOverflow() {
    }

    public static Path dir(Path world) {
        return world.resolve("emergencyshelter").resolve("quarantine").resolve("entities");
    }

    /** 从区块读出的实体列表：超过上限的那部分移进隔离区，返回留下的部分。 */
    public static List<? extends Tag> limit(List<? extends Tag> tags, Level level) {
        int limit = ShelterReport.settings().entityLimitPerChunk;
        Path world = WorldGuard.worldRoot();
        if (limit <= 0 || tags.size() <= limit || BYPASS.get() || world == null || !(level instanceof ServerLevel serverLevel)) {
            return tags;
        }
        Map<String, Integer> counts = new HashMap<>();
        for (Tag t : tags) {
            if (t instanceof CompoundTag c) {
                counts.merge(c.getString("id"), 1, Integer::sum);
            }
        }
        if (counts.values().stream().noneMatch(n -> n > limit)) {
            return tags;
        }
        // 每种实体：有名字、被驯服、被拴住、不会自然消失的优先留下，其余按原顺序
        Map<String, Integer> keptCount = new HashMap<>();
        List<Tag> kept = new ArrayList<>();
        List<Tag> moved = new ArrayList<>();
        for (int pass = 0; pass < 2; pass++) {
            for (Tag t : tags) {
                if (!(t instanceof CompoundTag c)) {
                    if (pass == 0) {
                        kept.add(t);
                    }
                    continue;
                }
                boolean important = isImportant(c);
                if ((pass == 0) != important) {
                    continue;
                }
                String id = c.getString("id");
                if (counts.get(id) <= limit || keptCount.getOrDefault(id, 0) < limit) {
                    keptCount.merge(id, 1, Integer::sum);
                    kept.add(t);
                } else {
                    moved.add(t);
                }
            }
        }
        // 保存失败就照常全部加载，绝不丢弃
        return save(serverLevel, world, moved, counts, limit) ? kept : tags;
    }

    private static boolean isImportant(CompoundTag c) {
        return c.contains("CustomName") || c.hasUUID("Owner") || c.contains("leash") || c.getBoolean("PersistenceRequired");
    }

    private static boolean save(ServerLevel level, Path world, List<Tag> moved, Map<String, Integer> counts, int limit) {
        BlockPos pos = posOf(moved.getFirst());
        ChunkPos chunk = new ChunkPos(pos);
        StringBuilder what = new StringBuilder();
        counts.forEach((id, n) -> {
            if (n > limit) {
                what.append(what.isEmpty() ? "" : "、").append(id).append(" ×").append(n);
            }
        });
        String where = WorldGuard.where(level.dimension(), pos);
        String base = level.dimension().location().toString().replace(':', '_') + "/" + chunk.x + "_" + chunk.z + "-"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS"));
        Path file = dir(world).resolve(base + ".dat");
        // 同一区块可能在同一毫秒内再次超限（例如反复加载）：不能覆盖上一份
        for (int n = 2; Files.exists(file); n++) {
            file = dir(world).resolve(base + "-" + n + ".dat");
        }
        CompoundTag root = new CompoundTag();
        ListTag list = new ListTag();
        list.addAll(moved);
        root.put("Entities", list);
        root.putString("Dimension", level.dimension().location().toString());
        root.putInt("ChunkX", chunk.x);
        root.putInt("ChunkZ", chunk.z);
        try {
            Files.createDirectories(file.getParent());
            NbtIo.writeCompressed(root, file);
        } catch (IOException e) {
            EmergencyShelter.LOGGER.error("[紧急避险] 无法保存多出来的实体，本次照常全部加载", e);
            return false;
        }
        String detail = "同一种实体超过 " + limit + " 个，多出的 " + moved.size() + " 个已移出世界并完整保存到 "
                + world.relativize(file).toString().replace('\\', '/') + "，可以用 /emergencyshelter quarantine entities 放回";
        EmergencyShelter.LOGGER.warn("[紧急避险] 区块 {} 的实体过多（{}）。{}", where, what, detail);
        WorldGuard.record("ENTITY_OVERFLOW", what.toString(), where, detail);
        return true;
    }

    private static BlockPos posOf(Tag tag) {
        if (tag instanceof CompoundTag c) {
            ListTag pos = c.getList("Pos", Tag.TAG_DOUBLE);
            if (pos.size() == 3) {
                return BlockPos.containing(pos.getDouble(0), pos.getDouble(1), pos.getDouble(2));
            }
        }
        return BlockPos.ZERO;
    }

    // ------------------------------------------------------------------ 放回

    /** 隔离区里的实体文件（相对 entities 目录的路径）。 */
    public static List<String> list(Path world) {
        Path dir = dir(world);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(dir, 2)) {
            return walk.filter(p -> p.getFileName().toString().endsWith(".dat"))
                    .map(p -> dir.relativize(p).toString().replace('\\', '/')).sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /** 把一个文件里的实体放回原来的位置（不受数量上限限制）。返回放回的数量，文件改名为 .restored。 */
    public static int restore(Level anyLevel, Path world, String name) throws IOException {
        Path file = dir(world).resolve(name).normalize();
        if (!file.startsWith(dir(world)) || !Files.isRegularFile(file)) {
            throw new IOException("找不到 " + name);
        }
        CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
        ServerLevel level = null;
        if (anyLevel.getServer() != null) {
            for (ServerLevel l : anyLevel.getServer().getAllLevels()) {
                if (l.dimension().location().toString().equals(root.getString("Dimension"))) {
                    level = l;
                }
            }
        }
        if (level == null) {
            throw new IOException("维度 " + root.getString("Dimension") + " 不存在");
        }
        ListTag list = root.getList("Entities", Tag.TAG_COMPOUND);
        int added = 0;
        BYPASS.set(true);
        try {
            level.getChunk(root.getInt("ChunkX"), root.getInt("ChunkZ"));
            for (Entity entity : EntityType.loadEntitiesRecursive(list, level).toList()) {
                if (level.tryAddFreshEntityWithPassengers(entity)) {
                    added++;
                }
            }
        } finally {
            BYPASS.set(false);
        }
        Files.move(file, file.resolveSibling(file.getFileName() + ".restored"));
        return added;
    }
}
