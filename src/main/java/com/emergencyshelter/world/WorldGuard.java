package com.emergencyshelter.world;

import com.emergencyshelter.EmergencyShelter;
import com.emergencyshelter.report.ShelterReport;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.jetbrains.annotations.Nullable;

/**
 * 存档保护的总管：
 * <ul>
 *     <li>记录"正在进入 / 正在运行 / 崩溃了"的存档会话（{@code emergencyshelter/world-session.json}），下次启动时 early 部分据此判断是否"一进存档就崩溃"；</li>
 *     <li>存档正常关闭时，把关键数据记为"上次正常"（{@link WorldSnapshot}）；</li>
 *     <li>本次运行中被隔离、回滚的东西都记在这里，告诉玩家并写入 {@code <存档>/emergencyshelter/quarantine/log.txt}。</li>
 * </ul>
 */
@EventBusSubscriber(modid = EmergencyShelter.MODID)
public final class WorldGuard {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final String SESSION_FILE = "world-session.json";

    @Nullable
    private static volatile MinecraftServer server;
    @Nullable
    private static volatile Path worldRoot;
    private static volatile boolean crashed;
    private static String sessionStamp = stamp();
    private static long started;
    private static long runningSince;
    private static final List<Event> EVENTS = Collections.synchronizedList(new ArrayList<>());
    /** 一次运行最多保留的记录数（报告和聊天提示用），超出的只计数。 */
    private static final int EVENT_LIMIT = 1000;
    private static volatile int eventsDropped;
    /** 同一类记录每分钟单独写日志、通知管理员的条数上限。 */
    private static final int BURST_LIMIT = 20;
    private static final long BURST_WINDOW_MS = 60_000;
    private static final Map<String, long[]> BURSTS = new ConcurrentHashMap<>();
    private static final ExecutorService LOG_WRITER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "EmergencyShelter-Log");
        t.setDaemon(true);
        return t;
    });
    private static final long QUARANTINE_WARN_BYTES = 2L << 30;
    private static final Set<String> COPIED_REGIONS = Collections.synchronizedSet(new HashSet<>());
    /** 服务端创建之前发生的事（例如 level.dat 回滚），等服务端启动时再记录。 */
    private static final List<String[]> BEFORE_START = Collections.synchronizedList(new ArrayList<>());

    private WorldGuard() {
    }

    /** 一条隔离 / 回滚记录。 */
    public record Event(String kind, String what, @Nullable String where, String detail, long time) {
    }

    /** 与 early 部分 WorldSessionCheck.Session 的字段一致。 */
    private static final class Session {
        String world;
        String state;
        long started;
        long runningSince;
        long crashTime;
    }

    // ------------------------------------------------------------------ 生命周期

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onServerAboutToStart(ServerAboutToStartEvent event) {
        // 事件处理出错不能让游戏崩溃
        com.emergencyshelter.Defense.quietly("WorldGuard.onServerAboutToStart", () -> onServerAboutToStartUnsafe(event));
    }

    private static void onServerAboutToStartUnsafe(ServerAboutToStartEvent event) {
        MinecraftServer s = event.getServer();
        server = s;
        worldRoot = s.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        crashed = false;
        sessionStamp = stamp();
        started = System.currentTimeMillis();
        runningSince = 0;
        EVENTS.clear();
        eventsDropped = 0;
        BURSTS.clear();
        COPIED_REGIONS.clear();
        synchronized (BEFORE_START) {
            for (String[] e : BEFORE_START) {
                record(e[0], e[1], null, e[2]);
            }
            BEFORE_START.clear();
        }
        ShelterWorldData.resetCache();
        writeSession("LOADING", 0);
        HangGuard.start();
        checkQuarantineSize(worldRoot);
    }

    /** 隔离目录里的东西不会自动删除（可能是唯一的副本）；占用太多磁盘时提醒管理员手动清理。在后台线程统计，不拖慢开服。 */
    private static void checkQuarantineSize(Path world) {
        Path dir = world.resolve("emergencyshelter").resolve("quarantine");
        if (!Files.isDirectory(dir)) {
            return;
        }
        try {
            LOG_WRITER.execute(() -> {
                long[] total = new long[1];
                try (var walk = Files.walk(dir)) {
                    walk.filter(Files::isRegularFile).forEach(p -> {
                        try {
                            total[0] += Files.size(p);
                        } catch (IOException ignored) {
                        }
                    });
                } catch (IOException | RuntimeException e) {
                    return;
                }
                // 统计期间已经换了存档（单人游戏退出后马上进另一个存档）：不要把提醒记到新存档里
                if (total[0] > QUARANTINE_WARN_BYTES && world.equals(worldRoot)) {
                    String size = String.format(java.util.Locale.ROOT, "%.1f GB", total[0] / (1024.0 * 1024 * 1024));
                    EmergencyShelter.LOGGER.warn("[紧急避险] 隔离目录 {} 已占用 {}。里面的文件不会自动删除；确认不再需要后，可以删掉较早的 <时间> 子目录",
                            dir, size);
                    record("QUARANTINE_LARGE", size, null, "隔离目录 emergencyshelter/quarantine 已占用 " + size
                            + "，不会自动删除；确认不再需要后可以手动删掉较早的子目录");
                }
            });
        } catch (RuntimeException ignored) {
        }
    }

    /** 主世界创建后、加载任何区块之前：处理上次卡住导致游戏被关闭的东西。 */
    @SubscribeEvent
    public static void onLevelLoad(LevelEvent.Load event) {
        // 事件处理出错不能让游戏崩溃
        com.emergencyshelter.Defense.quietly("WorldGuard.onLevelLoad", () -> onLevelLoadUnsafe(event));
    }

    private static void onLevelLoadUnsafe(LevelEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level && level.dimension() == Level.OVERWORLD && enabled(s -> s.hangGuard)) {
            ShelterWorldData data = ShelterWorldData.get();
            Path root = worldRoot;
            if (data != null && root != null) {
                HangGuard.applyPending(data, root);
            }
        }
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        // 事件处理出错不能让游戏崩溃
        com.emergencyshelter.Defense.quietly("WorldGuard.onServerStarted", () -> onServerStartedUnsafe(event));
    }

    private static void onServerStartedUnsafe(ServerStartedEvent event) {
        runningSince = System.currentTimeMillis();
        writeSession("RUNNING", 0);
        Path root = worldRoot;
        if (root != null && enabled()) {
            DataBloat.checkAsync(root);
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        // 事件处理出错不能让游戏崩溃
        com.emergencyshelter.Defense.quietly("WorldGuard.onServerStopped", () -> onServerStoppedUnsafe(event));
    }

    private static void onServerStoppedUnsafe(ServerStoppedEvent event) {
        HangGuard.stop();
        Path root = worldRoot;
        try {
            if (!crashed && root != null && ShelterReport.settings().worldGuard) {
                WorldSnapshot.update(root);
            }
        } catch (Throwable t) {
            EmergencyShelter.LOGGER.error("[紧急避险] 记录存档快照时出错（不影响存档本身）", t);
        }
        if (!crashed) {
            deleteSession();
        }
        server = null;
        ShelterWorldData.resetCache();
    }

    /** 服务端崩溃（MinecraftServer.onServerCrash）。 */
    public static void onServerCrash() {
        crashed = true;
        writeSession("CRASHED", System.currentTimeMillis());
    }

    private static void writeSession(String state, long crashTime) {
        Path root = worldRoot;
        if (root == null) {
            return;
        }
        Session session = new Session();
        session.world = root.toString();
        session.state = state;
        session.started = started;
        session.runningSince = runningSince;
        session.crashTime = crashTime;
        Path file = FMLPaths.GAMEDIR.get().resolve("emergencyshelter").resolve(SESSION_FILE);
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(SESSION_FILE + ".tmp");
            Files.writeString(tmp, GSON.toJson(session), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            EmergencyShelter.LOGGER.debug("[紧急避险] 无法写入存档会话记录", e);
        }
    }

    private static void deleteSession() {
        try {
            Files.deleteIfExists(FMLPaths.GAMEDIR.get().resolve("emergencyshelter").resolve(SESSION_FILE));
        } catch (IOException ignored) {
        }
    }

    // ------------------------------------------------------------------ 状态

    @Nullable
    public static MinecraftServer server() {
        return server;
    }

    @Nullable
    public static Path worldRoot() {
        return worldRoot;
    }

    /** 服务端已经启动完成（开始正常运行）。 */
    public static boolean running() {
        return server != null && runningSince > 0;
    }

    public static boolean enabled() {
        return ShelterReport.settings().worldGuard;
    }

    /** 存档保护开着，并且这一项功能没有被单独关掉。 */
    public static boolean enabled(java.util.function.Predicate<ShelterReport.Settings> feature) {
        ShelterReport.Settings settings = ShelterReport.settings();
        return settings.worldGuard && feature.test(settings);
    }

    public static List<Event> events() {
        synchronized (EVENTS) {
            return new ArrayList<>(EVENTS);
        }
    }

    /** 本次运行的隔离目录：{@code <存档>/emergencyshelter/quarantine/<时间>/}。 */
    public static Path quarantineDir(Path world) {
        return world.resolve("emergencyshelter").resolve("quarantine").resolve(sessionStamp);
    }

    // ------------------------------------------------------------------ 记录

    /**
     * 记下一件事并通知在线的管理员。
     *
     * @param kind 见语言文件 emergencyshelter.guard.kind.*
     */
    public static void record(String kind, String what, @Nullable String where, String detail) {
        // 记录只是附带工作：出了问题也不能影响正在处理的事
        com.emergencyshelter.Defense.quietly("record " + kind, () -> recordUnsafe(kind, what, where, detail));
    }

    private static void recordUnsafe(String kind, String what, @Nullable String where, String detail) {
        Event event = new Event(kind, what, where, detail.length() > 400 ? detail.substring(0, 400) + "…" : detail, System.currentTimeMillis());
        // 同一类事件短时间内大量出现（例如几千个实体同时出错）：只单独记录前几条，其余合并成一条汇总，避免卡服
        long suppressed = 0;
        long count;
        long[] burst = BURSTS.computeIfAbsent(kind, k -> new long[2]);
        synchronized (burst) {
            if (event.time() - burst[0] > BURST_WINDOW_MS) {
                suppressed = Math.max(0, burst[1] - BURST_LIMIT);
                burst[0] = event.time();
                burst[1] = 0;
            }
            count = ++burst[1];
        }
        boolean firstDropped = false;
        synchronized (EVENTS) {
            if (EVENTS.size() < EVENT_LIMIT) {
                EVENTS.add(event);
            } else {
                firstDropped = eventsDropped++ == 0;
            }
        }
        if (firstDropped) {
            EmergencyShelter.LOGGER.warn("[紧急避险] 本次运行的记录已超过 {} 条，之后的记录不再列入报告（仍写进 quarantine/log.txt）", EVENT_LIMIT);
        }
        if (suppressed > 0) {
            appendLog(kind, "（之前一分钟内还有 " + suppressed + " 条同类记录未单独列出）", null, "");
        }
        if (count > BURST_LIMIT) {
            if (count == BURST_LIMIT + 1) {
                EmergencyShelter.LOGGER.warn("[紧急避险] {} 类记录在一分钟内超过 {} 条，之后的同类记录只汇总计数", kind, BURST_LIMIT);
            }
            return;
        }
        appendLog(kind, what, where, event.detail());
        MinecraftServer s = server;
        // 玩家数据相关的事发生在登录过程中，登录时会一并告诉玩家，不需要再单独广播
        if (s != null && runningSince > 0 && !kind.startsWith("PLAYER_")) {
            Component line = describe(event);
            s.execute(() -> {
                for (ServerPlayer player : s.getPlayerList().getPlayers()) {
                    if (com.emergencyshelter.ServerReportHandler.canViewReport(player)) {
                        player.sendSystemMessage(Component.translatable("emergencyshelter.guard.prefix").withStyle(ChatFormatting.GOLD).append(line));
                    }
                }
            });
        }
    }

    /** 追加到 quarantine/log.txt。在后台线程写，不让文件读写（杀毒软件扫描时可能很慢）拖住服务端线程。 */
    private static void appendLog(String kind, String what, @Nullable String where, String detail) {
        Path root = worldRoot;
        if (root == null) {
            return;
        }
        String line = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + " [" + kind + "] " + what
                + (where == null ? "" : " @ " + where) + (detail.isEmpty() ? "" : " — " + detail) + System.lineSeparator();
        try {
            LOG_WRITER.execute(() -> {
                try {
                    Path log = root.resolve("emergencyshelter").resolve("quarantine").resolve("log.txt");
                    Files.createDirectories(log.getParent());
                    try (Writer w = Files.newBufferedWriter(log, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                        w.write(line);
                    }
                } catch (IOException ignored) {
                }
            });
        } catch (RuntimeException ignored) {
        }
    }

    public static void recordBeforeStart(String kind, String what, String detail) {
        BEFORE_START.add(new String[]{kind, what, detail});
    }

    public static Component describe(Event e) {
        MutableComponent line = Component.translatable("emergencyshelter.guard.kind." + e.kind(), e.what()).withStyle(Style.EMPTY
                .withColor(ChatFormatting.YELLOW).withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(e.detail()))));
        if (e.where() != null) {
            line.append(" ").append(Component.literal("@ " + e.where()).withStyle(Style.EMPTY.withColor(ChatFormatting.AQUA)
                    .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, tpCommand(e.where())))
                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(e.detail())))));
        }
        return line;
    }

    private static String tpCommand(String where) {
        // where 形如 "minecraft:overworld 12, 64, -3"
        int space = where.indexOf(' ');
        if (space < 0) {
            return "/tp @s " + where;
        }
        return "/execute in " + where.substring(0, space) + " run tp @s " + where.substring(space + 1).replace(",", "");
    }

    public static String where(ResourceKey<Level> dim, BlockPos pos) {
        return dim.location() + " " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }

    public static String message(Throwable t) {
        // 有的模组的异常在 getMessage 里还会再出错
        return com.emergencyshelter.Defense.quietly("message", () -> messageUnsafe(t), String.valueOf(t == null ? null : t.getClass().getName()));
    }

    private static String messageUnsafe(Throwable t) {
        Throwable root = t;
        int guard = 0;
        while (root.getCause() != null && root.getCause() != root && guard++ < 16) {
            root = root.getCause();
        }
        String msg = root.getClass().getSimpleName() + (root.getMessage() == null ? "" : ": " + root.getMessage());
        return msg.length() > 300 ? msg.substring(0, 300) + "…" : msg;
    }

    // ------------------------------------------------------------------ "什么时候再试一次"

    /** 隔离时记下的条件：模组组合（启动检查的指纹）和手动重试计数。任一变化都会重新尝试。 */
    public static CompoundTag faultTag(String kind, Throwable error) {
        CompoundTag tag = new CompoundTag();
        tag.putString("kind", kind);
        tag.putString("fp", fingerprint());
        tag.putInt("epoch", ShelterWorldData.epoch());
        tag.putString("error", message(error));
        tag.putLong("time", System.currentTimeMillis());
        return tag;
    }

    public static boolean shouldRetry(@Nullable CompoundTag fault) {
        if (fault == null || fault.isEmpty()) {
            return true;
        }
        return !fault.getString("fp").equals(fingerprint()) || fault.getInt("epoch") < ShelterWorldData.epoch();
    }

    private static String fingerprint() {
        String fp = ShelterReport.get().modsFingerprint;
        return fp == null ? "dev" : fp;
    }

    // ------------------------------------------------------------------ 隔离文件

    /** 把一份 NBT 存进本次的隔离目录。返回相对存档的路径。 */
    @Nullable
    public static String quarantineNbt(String relPath, CompoundTag tag) {
        Path root = worldRoot;
        if (root == null) {
            return null;
        }
        try {
            Path target = quarantineDir(root).resolve(relPath);
            Files.createDirectories(target.getParent());
            NbtIo.writeCompressed(tag, target);
            return root.relativize(target).toString().replace('\\', '/');
        } catch (IOException e) {
            EmergencyShelter.LOGGER.warn("[紧急避险] 无法保存隔离数据 {}", relPath, e);
            return null;
        }
    }

    /** 把存档里的一个文件复制进隔离目录（同一个文件本次只复制一次）。 */
    @Nullable
    public static String quarantineCopy(Path world, Path file) {
        String rel = world.relativize(file).toString().replace('\\', '/');
        if (!Files.isRegularFile(file) || !COPIED_REGIONS.add(rel)) {
            return null;
        }
        try {
            Path target = quarantineDir(world).resolve(rel);
            Files.createDirectories(target.getParent());
            Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
            return world.relativize(target).toString().replace('\\', '/');
        } catch (IOException e) {
            COPIED_REGIONS.remove(rel); // 文件可能只是暂时被占用：下次再出错时重试
            EmergencyShelter.LOGGER.warn("[紧急避险] 无法备份 {}", file, e);
            return null;
        }
    }

    /**
     * 压下一个运行错误之前：把出错位置所在的存档文件复制进隔离目录。
     * 磁盘上的这份是出错之前最后一次保存的版本（内存里的状态要等下次保存才写回），出了问题可以用它回滚。
     *
     * @param folder "region"（方块、方块实体）或 "entities"（实体）
     */
    public static void backupBeforeSuppress(ServerLevel level, String folder, net.minecraft.world.level.ChunkPos pos) {
        Path world = worldRoot;
        if (world == null || !enabled(s -> s.backupBeforeSuppress)) {
            return;
        }
        Path file = net.minecraft.world.level.dimension.DimensionType.getStorageFolder(level.dimension(), world).resolve(folder)
                .resolve("r." + pos.getRegionX() + "." + pos.getRegionZ() + ".mca");
        backupFile(world, file);
    }

    /** 同上：玩家数据文件。 */
    public static void backupBeforeSuppress(ServerPlayer player) {
        Path world = worldRoot;
        if (world == null || !enabled(s -> s.backupBeforeSuppress)) {
            return;
        }
        backupFile(world, world.resolve("playerdata").resolve(player.getStringUUID() + ".dat"));
    }

    private static void backupFile(Path world, Path file) {
        try {
            String copy = quarantineCopy(world, file);
            if (copy != null) {
                EmergencyShelter.LOGGER.warn("[紧急避险] 压下运行错误之前，已把出错之前的存档文件 {} 备份到 {}", world.relativize(file), copy);
                record("SUPPRESS_BACKUP", copy, null, "出错之前最后一次保存的版本，需要回滚时用它替换 " + world.relativize(file).toString().replace('\\', '/'));
            }
        } catch (Throwable t) {
            EmergencyShelter.LOGGER.warn("[紧急避险] 备份 {} 失败", file, t);
        }
    }

    private static String stamp() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
    }

    @SuppressWarnings("unchecked")
    public static <T extends Throwable> RuntimeException sneakyThrow(Throwable t) throws T {
        throw (T) t;
    }
}
