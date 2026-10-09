package com.emergencyshelter.world;

import com.emergencyshelter.EmergencyShelter;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPInputStream;
import net.minecraft.nbt.CollectionTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtAccounterException;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.Nullable;

/**
 * 读取"嵌套过深"或"体积过大"的存档数据。
 * <p>
 * 原版读取存档里的 NBT 时，嵌套超过 512 层就会报错（Tried to read NBT tag with too high complexity, depth &gt; 512），
 * level.dat 超过 100 MB 也会报错。常见原因是某些模组的数据每次保存都多套一层，或者箱子套箱子的物品。
 * 结果是：区块被重新生成（建筑消失）、玩家从零开始、存档打不开。
 * <p>
 * 这里改为：在一个栈空间足够大的线程里不设限制地读出来，把超过 448 层的部分剪掉（原版本来就读不出这部分），
 * 其余数据照常使用；原始文件先完整复制进隔离目录。
 */
public final class DeepNbt {
    /** 剪裁后保留的最大深度，低于原版的 512 层上限，留出余量。 */
    static final int KEEP_DEPTH = 448;
    private static final long STACK_SIZE = 512L * 1024 * 1024;
    private static final ThreadLocal<Boolean> RECOVERED = ThreadLocal.withInitial(() -> false);

    private DeepNbt() {
    }

    /** 这个错误是不是"深度或体积超过限制"造成的。 */
    public static boolean isLimitProblem(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof NbtAccounterException || t instanceof StackOverflowError) {
                return true;
            }
            String msg = t.getMessage();
            if (msg != null && (msg.contains("Tried to read NBT tag") || msg.contains("too high complexity"))) {
                return true;
            }
        }
        return false;
    }

    public record Result(CompoundTag tag, int removed, int maxDepth) {
    }

    /** 读取一个文件（自动识别是否压缩）。 */
    public static Result readFile(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        return read(bytes);
    }

    /** 读取一段 NBT（自动识别是否 gzip 压缩）。 */
    public static Result read(byte[] bytes) throws IOException {
        AtomicReference<Object> out = new AtomicReference<>();
        Thread thread = new Thread(null, () -> {
            try {
                InputStream in = new ByteArrayInputStream(bytes);
                if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0x1F && (bytes[1] & 0xFF) == 0x8B) {
                    in = new GZIPInputStream(in);
                }
                CompoundTag tag;
                try (DataInputStream data = new DataInputStream(new BufferedInputStream(in))) {
                    tag = NbtIo.read(data, new NbtAccounter(Long.MAX_VALUE, Integer.MAX_VALUE));
                }
                int[] stats = new int[2];
                trim(tag, 1, stats);
                out.set(new Result(tag, stats[0], stats[1]));
            } catch (Throwable t) {
                out.set(t);
            }
        }, "EmergencyShelter-DeepNbt", STACK_SIZE);
        thread.start();
        try {
            thread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
        Object result = out.get();
        if (result instanceof Result r) {
            return r;
        }
        if (result instanceof IOException e) {
            throw e;
        }
        throw new IOException("无法读取数据", (Throwable) result);
    }

    /** 递归剪掉超过 {@link #KEEP_DEPTH} 层的部分（在大栈线程里执行）。stats[0] = 剪掉的数量，stats[1] = 原始最大深度。 */
    private static void trim(Tag tag, int depth, int[] stats) {
        stats[1] = Math.max(stats[1], depth);
        if (tag instanceof CompoundTag compound) {
            for (String key : new ArrayList<>(compound.getAllKeys())) {
                Tag child = compound.get(key);
                if (isContainer(child)) {
                    if (depth + 1 > KEEP_DEPTH) {
                        stats[1] = Math.max(stats[1], depth + 1 + depthOf(child));
                        compound.remove(key);
                        stats[0]++;
                    } else {
                        trim(child, depth + 1, stats);
                    }
                }
            }
        } else if (tag instanceof ListTag list) {
            for (int i = list.size() - 1; i >= 0; i--) {
                Tag child = list.get(i);
                if (isContainer(child)) {
                    if (depth + 1 > KEEP_DEPTH) {
                        stats[1] = Math.max(stats[1], depth + 1 + depthOf(child));
                        list.remove(i);
                        stats[0]++;
                    } else {
                        trim(child, depth + 1, stats);
                    }
                }
            }
        }
    }

    private static boolean isContainer(@Nullable Tag tag) {
        return tag instanceof CompoundTag || tag instanceof ListTag;
    }

    private static int depthOf(Tag tag) {
        int max = 0;
        if (tag instanceof CompoundTag compound) {
            for (String key : compound.getAllKeys()) {
                Tag child = compound.get(key);
                if (isContainer(child)) {
                    max = Math.max(max, 1 + depthOf(child));
                }
            }
        } else if (tag instanceof CollectionTag<?> list) {
            for (Tag child : list) {
                if (isContainer(child)) {
                    max = Math.max(max, 1 + depthOf(child));
                }
            }
        }
        return max;
    }

    // ------------------------------------------------------------------ 各个读取位置

    /** 按文件读取（level.dat、玩家数据、模组存档数据）出错时调用：读出能用的部分，原文件备份进隔离目录。 */
    public static CompoundTag recoverFile(Path file, Throwable error) throws IOException {
        file = file.toAbsolutePath().normalize();
        Result result = readFile(file);
        String backup = backup(file);
        report(file.getFileName().toString(), file, result, backup, error);
        RECOVERED.set(true);
        return result.tag();
    }

    /** 当前线程刚才是否读出了修好的数据（读取一次后清除）。 */
    public static boolean takeRecovered() {
        boolean r = RECOVERED.get();
        RECOVERED.set(false);
        return r;
    }

    /** 区块（地形、实体、兴趣点）数据读取出错时调用。 */
    public static CompoundTag recoverChunk(byte[] raw, Path regionFile, String chunk, Throwable error) throws IOException {
        regionFile = regionFile.toAbsolutePath().normalize();
        Result result = read(raw);
        String backup = null;
        Path world = WorldSnapshot.findWorldRoot(regionFile);
        if (world != null) {
            String rel = world.relativize(regionFile).toString().replace('\\', '/') + "-" + chunk + ".nbt";
            try {
                Path target = WorldGuard.quarantineDir(world).resolve(rel);
                Files.createDirectories(target.getParent());
                Files.write(target, raw);
                backup = world.relativize(target).toString().replace('\\', '/');
            } catch (Throwable e) {
                EmergencyShelter.LOGGER.warn("[紧急避险] 无法备份区块原始数据", e);
            }
        }
        report(regionFile.getFileName() + " " + chunk, regionFile, result, backup, error);
        return result.tag();
    }

    private static void report(String what, Path file, Result result, @Nullable String backup, Throwable error) {
        String detail = (result.removed() > 0
                ? "嵌套深度 " + result.maxDepth() + " 层，超过 " + KEEP_DEPTH + " 层的 " + result.removed() + " 处已剪掉"
                : "数据体积超过原版上限，已不设上限读取")
                + (backup == null ? "" : "；原始数据已备份到 " + backup);
        EmergencyShelter.LOGGER.warn("[紧急避险] {} 读取出错（{}），原版会丢弃或无法打开它。{}", file, WorldGuard.message(error), detail);
        if (WorldGuard.server() != null) {
            WorldGuard.record("NBT_DEEP", what, null, detail);
        } else {
            WorldGuard.recordBeforeStart("NBT_DEEP", what, detail);
        }
    }

    /** 把原文件完整复制一份：存档里的文件放进存档的隔离目录，其它的放进游戏目录的隔离目录。 */
    @Nullable
    private static String backup(Path file) {
        try {
            Path world = WorldSnapshot.findWorldRoot(file);
            Path target;
            String rel;
            if (world != null) {
                rel = world.relativize(file).toString().replace('\\', '/');
                target = (WorldGuard.worldRoot() != null && WorldGuard.worldRoot().equals(world) ? WorldGuard.quarantineDir(world)
                        : world.resolve("emergencyshelter").resolve("quarantine").resolve(stamp())).resolve(rel);
            } else {
                rel = file.getFileName().toString();
                target = file.toAbsolutePath().getParent().resolve(rel + "." + stamp() + ".bak");
            }
            Files.createDirectories(target.getParent());
            Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
            return world != null ? world.relativize(target).toString().replace('\\', '/') : target.toString();
        } catch (Throwable e) {
            // 备份失败不能影响读取本身
            EmergencyShelter.LOGGER.warn("[紧急避险] 无法备份 {}", file, e);
            return null;
        }
    }

    private static String stamp() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
    }
}
