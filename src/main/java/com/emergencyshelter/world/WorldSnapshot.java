package com.emergencyshelter.world;

import com.emergencyshelter.EmergencyShelter;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import org.jetbrains.annotations.Nullable;

/**
 * 存档的"上次正常"副本：{@code <存档>/emergencyshelter/lastgood/}。
 * 存档正常关闭（没有崩溃）时更新，只复制有变化的文件。包括：
 * level.dat、玩家数据、各维度的 data/（AE2、精致存储等模组的仓库数据都在这里）、serverconfig/。
 * 区块文件不在其中（体积太大），区块出问题时会单独备份出问题的那个区域文件。
 */
public final class WorldSnapshot {
    public static final String DIR = "lastgood";
    private static final String MANIFEST = "manifest.json";
    private static final long MAX_FILE_SIZE = 256L << 20;
    private static final Gson GSON = new Gson();

    private WorldSnapshot() {
    }

    public static Path root(Path world) {
        return world.resolve("emergencyshelter").resolve(DIR);
    }

    /** 快照里对应的文件（不存在则返回 null）。 */
    @Nullable
    public static Path lastGood(Path world, Path file) {
        Path rel = world.relativize(file.toAbsolutePath().normalize());
        if (rel.startsWith("..")) {
            return null;
        }
        Path p = root(world).resolve(rel.toString());
        return Files.isRegularFile(p) ? p : null;
    }

    /** 从某个文件（例如 data 目录下的文件）向上找到存档根目录（有 level.dat 的那一层）。 */
    @Nullable
    public static Path findWorldRoot(Path start) {
        Path known = WorldGuard.worldRoot();
        Path abs = start.toAbsolutePath().normalize();
        if (known != null && abs.startsWith(known)) {
            return known;
        }
        Path p = abs;
        for (int i = 0; i < 6 && p != null; i++) {
            if (Files.isRegularFile(p.resolve("level.dat"))) {
                return p;
            }
            p = p.getParent();
        }
        return null;
    }

    static void update(Path world) throws IOException {
        long start = System.currentTimeMillis();
        Path root = root(world);
        Files.createDirectories(root);
        Path manifestFile = root.resolve(MANIFEST);
        Map<String, long[]> manifest = new TreeMap<>();
        if (Files.isRegularFile(manifestFile)) {
            try {
                Map<String, long[]> m = GSON.fromJson(Files.readString(manifestFile, StandardCharsets.UTF_8),
                        new TypeToken<Map<String, long[]>>() {}.getType());
                if (m != null) {
                    manifest.putAll(m);
                }
            } catch (Exception ignored) {
            }
        }
        List<Path> sources = new ArrayList<>();
        addIfFile(sources, world.resolve("level.dat"));
        collect(sources, world.resolve("playerdata"), false);
        collect(sources, world.resolve("data"), true);
        collect(sources, world.resolve("serverconfig"), true);
        for (String dim : List.of("DIM-1", "DIM1")) {
            collect(sources, world.resolve(dim).resolve("data"), true);
        }
        Path dimensions = world.resolve("dimensions");
        if (Files.isDirectory(dimensions)) {
            try (Stream<Path> walk = Files.walk(dimensions, 3)) {
                for (Path p : walk.filter(p -> p.getFileName().toString().equals("data") && Files.isDirectory(p)).toList()) {
                    collect(sources, p, true);
                }
            }
        }

        Map<String, long[]> next = new TreeMap<>();
        int copied = 0;
        for (Path source : sources) {
            String rel = world.relativize(source).toString().replace('\\', '/');
            try {
                long size = Files.size(source);
                long mtime = Files.getLastModifiedTime(source).toMillis();
                if (size > MAX_FILE_SIZE) {
                    continue;
                }
                long[] old = manifest.get(rel);
                Path target = root.resolve(rel);
                if (old != null && old[0] == size && old[1] == mtime && Files.isRegularFile(target)) {
                    next.put(rel, old);
                    continue;
                }
                if (rel.endsWith(".dat") && !readableNbt(source) && old != null && Files.isRegularFile(target) && readableNbt(target)) {
                    // 现在这份读不出来，而快照里的能读：保留快照里的版本（本来就不是 NBT 格式的文件无法校验，照常复制）
                    next.put(rel, old);
                    EmergencyShelter.LOGGER.warn("[紧急避险] {} 无法读取，快照中保留它上次正常时的版本", rel);
                    continue;
                }
                Files.createDirectories(target.getParent());
                Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
                Files.copy(source, tmp, StandardCopyOption.REPLACE_EXISTING);
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                next.put(rel, new long[]{size, mtime});
                copied++;
            } catch (IOException e) {
                // 文件暂时被占用（杀毒软件、网盘同步）：保留快照里已有的版本，不能因为这次没复制成功就把它删掉
                long[] old = manifest.get(rel);
                if (old != null && Files.isRegularFile(root.resolve(rel))) {
                    next.put(rel, old);
                }
                EmergencyShelter.LOGGER.debug("[紧急避险] 快照时无法复制 {}", rel, e);
            }
        }
        // 已经不存在的文件也从快照里删掉
        for (String rel : manifest.keySet()) {
            if (!next.containsKey(rel)) {
                Files.deleteIfExists(root.resolve(rel));
            }
        }
        Files.writeString(manifestFile, GSON.toJson(next), StandardCharsets.UTF_8);
        EmergencyShelter.LOGGER.info("[紧急避险] 已记录存档的\"上次正常\"副本：{} 个文件，更新 {} 个，用时 {} ms",
                next.size(), copied, System.currentTimeMillis() - start);
    }

    private static void addIfFile(List<Path> out, Path p) {
        if (Files.isRegularFile(p)) {
            out.add(p);
        }
    }

    private static void collect(List<Path> out, Path dir, boolean recursive) throws IOException {
        if (!Files.isDirectory(dir)) {
            return;
        }
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) {
                return recursive || d.equals(dir) ? FileVisitResult.CONTINUE : FileVisitResult.SKIP_SUBTREE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                // 临时文件、原版的 .dat_old、损坏备份都不要
                if (attrs.isRegularFile() && !name.endsWith(".tmp") && !name.endsWith("_old") && !name.contains("_corrupted_")
                        && !name.endsWith(".lock")) {
                    out.add(file);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** 文件能否作为 NBT 读出来（压缩或不压缩都行）。不是 NBT 格式的文件返回 false。 */
    static boolean readableNbt(Path file) {
        try (InputStream raw = Files.newInputStream(file); PushbackInputStream in = new PushbackInputStream(raw, 2)) {
            byte[] head = new byte[2];
            int n = in.read(head, 0, 2);
            if (n > 0) {
                in.unread(head, 0, n);
            }
            if (n == 2 && (head[0] & 0xFF) == 0x1F && (head[1] & 0xFF) == 0x8B) {
                NbtIo.readCompressed(in, NbtAccounter.unlimitedHeap());
            } else {
                NbtIo.read(new DataInputStream(in));
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
