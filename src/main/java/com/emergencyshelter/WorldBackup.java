package com.emergencyshelter;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import org.jetbrains.annotations.Nullable;

/**
 * 存档在"少了模组"的情况下第一次打开之前，自动备份 level.dat、玩家数据和 data 目录。
 * 区块数据不需要备份：缺失的方块、实体都会以占位形式完整保存，不会丢。
 * data 目录里有 AE2、精致存储等模组的仓库数据，它们不经过原版读取流程，备份一份最稳妥。
 */
@EventBusSubscriber(modid = EmergencyShelter.MODID)
public final class WorldBackup {
    private static final int KEEP = 5;
    private static final Gson GSON = new Gson();

    @Nullable
    private static String lastBackupNote;

    private WorldBackup() {
    }

    @Nullable
    public static String lastBackupNote() {
        return lastBackupNote;
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onServerAboutToStart(ServerAboutToStartEvent event) {
        lastBackupNote = null;
        try {
            check(event.getServer());
        } catch (Throwable t) {
            EmergencyShelter.LOGGER.error("[紧急避险] 自动备份失败（不影响进入存档）", t);
        }
    }

    private static void check(MinecraftServer server) throws IOException {
        Path world = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        Path shelterDir = world.resolve("emergencyshelter");
        Files.createDirectories(shelterDir);
        Path modsFile = shelterDir.resolve("mods.json");

        Map<String, String> current = new TreeMap<>();
        ModList.get().getMods().forEach(mod -> current.put(mod.getModId(), mod.getVersion().toString()));

        Map<String, String> previous = null;
        if (Files.isRegularFile(modsFile)) {
            try {
                previous = GSON.fromJson(Files.readString(modsFile, StandardCharsets.UTF_8), new TypeToken<Map<String, String>>() {}.getType());
            } catch (Exception e) {
                EmergencyShelter.LOGGER.warn("[紧急避险] 无法读取 {}", modsFile, e);
            }
        }
        if (previous == null && com.emergencyshelter.report.ShelterReport.get().hasNews()) {
            // 第一次在装了紧急避险的情况下打开这个存档，而这次启动恰好有模组被跳过
            Path target = backup(world, shelterDir);
            lastBackupNote = world.relativize(target).toString();
            EmergencyShelter.LOGGER.warn("[紧急避险] 本次启动有模组被跳过，已在加载前备份存档的关键数据到 {}", target);
        } else if (previous != null) {
            List<String> gone = new ArrayList<>();
            for (String id : previous.keySet()) {
                if (!current.containsKey(id)) {
                    gone.add(id);
                }
            }
            if (!gone.isEmpty()) {
                Path target = backup(world, shelterDir);
                lastBackupNote = world.relativize(target).toString();
                EmergencyShelter.LOGGER.warn("[紧急避险] 与上次打开相比少了 {} 个模组（{}），已在加载前备份存档的关键数据到 {}",
                        gone.size(), String.join(", ", gone.subList(0, Math.min(gone.size(), 20))), target);
            }
        }
        Files.writeString(modsFile, GSON.toJson(current), StandardCharsets.UTF_8);
    }

    private static Path backup(Path world, Path shelterDir) throws IOException {
        Path backups = shelterDir.resolve("backups");
        Path target = backups.resolve(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")));
        Files.createDirectories(target);
        for (String file : List.of("level.dat", "level.dat_old")) {
            Path source = world.resolve(file);
            if (Files.isRegularFile(source)) {
                Files.copy(source, target.resolve(file), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        for (String dir : List.of("playerdata", "data")) {
            copyTree(world.resolve(dir), target.resolve(dir));
        }
        // 各维度自己的 data 目录（例如 DIM-1/data、dimensions/<模组>/<维度>/data）
        for (Path dimData : findDimensionDataDirs(world)) {
            copyTree(dimData, target.resolve("dimension-data").resolve(world.relativize(dimData).toString().replace('\\', '_').replace('/', '_')));
        }
        pruneOld(backups);
        return target;
    }

    private static List<Path> findDimensionDataDirs(Path world) throws IOException {
        List<Path> result = new ArrayList<>();
        for (String name : List.of("DIM-1", "DIM1")) {
            Path data = world.resolve(name).resolve("data");
            if (Files.isDirectory(data)) {
                result.add(data);
            }
        }
        Path dimensions = world.resolve("dimensions");
        if (Files.isDirectory(dimensions)) {
            try (Stream<Path> walk = Files.walk(dimensions, 3)) {
                walk.filter(p -> p.getFileName().toString().equals("data") && Files.isDirectory(p)).forEach(result::add);
            }
        }
        return result;
    }

    private static void copyTree(Path source, Path target) throws IOException {
        if (!Files.isDirectory(source)) {
            return;
        }
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(target.resolve(source.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                try {
                    Files.copy(file, target.resolve(source.relativize(file).toString()), StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    EmergencyShelter.LOGGER.warn("[紧急避险] 备份时无法复制 {}", file, e);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void pruneOld(Path backups) throws IOException {
        List<Path> all;
        try (Stream<Path> list = Files.list(backups)) {
            all = list.filter(Files::isDirectory).sorted(Comparator.comparing(Path::getFileName)).toList();
        }
        for (int i = 0; i < all.size() - KEEP; i++) {
            try (Stream<Path> walk = Files.walk(all.get(i))) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                    }
                });
            }
        }
    }
}
