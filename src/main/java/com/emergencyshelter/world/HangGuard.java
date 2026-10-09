package com.emergencyshelter.world;

import com.emergencyshelter.EmergencyShelter;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;

/**
 * 卡死保护。记录服务端主线程和世界生成线程"正在处理什么"：
 * <ul>
 *     <li>某个方块实体、实体或世界生成步骤卡住超过 15 秒，就把它写进 {@code <存档>/emergencyshelter/hang.json}；</li>
 *     <li>它自己恢复了，记录随之删除；</li>
 *     <li>如果游戏因此被强制关闭（专用服务端的看门狗在 60 秒时关闭服务端，单人游戏通常是玩家自己关掉），
 *         下次打开存档时隔离它：方块实体停止运行，实体转为占位实体，世界生成步骤以后跳过。</li>
 * </ul>
 * 原版遇到这种情况，每次进存档都会卡在同一个地方。
 */
public final class HangGuard {
    public static final int BLOCK_ENTITY = 1;
    public static final int ENTITY = 2;
    public static final int FEATURE = 3;
    public static final int STRUCTURE = 4;
    private static final long THRESHOLD_MS = 15_000;
    /** 整个服务端刻：大型整合包刚开服时的头几刻本来就可能要十几秒，判定时间放宽一些。 */
    private static final long TICK_THRESHOLD_MS = 30_000;
    private static final String FILE = "hang.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private static final Set<Slot> SLOTS = ConcurrentHashMap.newKeySet();
    private static final ThreadLocal<Slot> SLOT = ThreadLocal.withInitial(() -> {
        Slot slot = new Slot(Thread.currentThread());
        SLOTS.add(slot);
        return slot;
    });
    /** 卡住过的实体（UUID → 类型），下次加载时转为占位实体。 */
    private static final Map<UUID, String> HANG_ENTITIES = new ConcurrentHashMap<>();
    private static final List<Hang> REPORTED = new ArrayList<>();
    @Nullable
    private static volatile Thread monitor;
    private static volatile boolean active;
    // 整个服务端刻卡住（只由监视线程使用）
    @Nullable
    private static Hang tickReported;
    private static boolean tickSkipLogged;

    private HangGuard() {
    }

    /** 每个线程一份，只由所属线程写入，监视线程读取。 */
    public static final class Slot {
        final Thread thread;
        volatile long seq;
        volatile int kind;
        @Nullable
        volatile Object subject;
        @Nullable
        volatile Object level;
        volatile long pos;
        // 以下只由监视线程使用
        long seenSeq = -1;
        long stuckSince;
        @Nullable
        Hang reported;

        Slot(Thread thread) {
            this.thread = thread;
        }
    }

    /** 写进 hang.json 的一条记录。 */
    static final class Hang {
        String kind;
        String dim;
        int x;
        int y;
        int z;
        String type;
        String uuid;
        String mod;
        String thread;
        long seconds;
        long time;
        String stack;
    }

    // ------------------------------------------------------------------ 热路径

    @Nullable
    public static Slot begin(int kind, Object subject, Object level, long pos) {
        if (!active) {
            return null;
        }
        Slot slot = SLOT.get();
        slot.kind = kind;
        slot.level = level;
        slot.pos = pos;
        slot.subject = subject;
        slot.seq++;
        return slot;
    }

    public static void end(@Nullable Slot slot) {
        if (slot != null) {
            slot.subject = null;
            slot.seq++;
        }
    }


    // ------------------------------------------------------------------ 生命周期

    static void start() {
        active = WorldGuard.enabled(s -> s.hangGuard);
        synchronized (REPORTED) {
            REPORTED.clear();
        }
        if (!active || monitor != null) {
            return;
        }
        Thread t = new Thread(HangGuard::loop, "EmergencyShelter-HangGuard");
        t.setDaemon(true);
        t.setPriority(Thread.MAX_PRIORITY);
        monitor = t;
        t.start();
    }

    static void stop() {
        active = false;
        Thread t = monitor;
        monitor = null;
        if (t != null) {
            t.interrupt();
        }
    }

    private static void loop() {
        while (monitor == Thread.currentThread()) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                return;
            }
            try {
                check(System.currentTimeMillis());
            } catch (Throwable t) {
                EmergencyShelter.LOGGER.debug("[紧急避险] 卡死检查出错", t);
            }
        }
    }

    private static void check(long now) {
        for (Slot slot : SLOTS) {
            if (!slot.thread.isAlive()) {
                SLOTS.remove(slot);
                if (slot.reported != null) {
                    resolved(slot);
                }
                continue;
            }
            long seq = slot.seq;
            Object subject = slot.subject;
            if (subject == null || seq != slot.seenSeq) {
                if (slot.reported != null) {
                    resolved(slot);
                }
                slot.seenSeq = seq;
                slot.stuckSince = now;
                continue;
            }
            if (slot.reported == null && now - slot.stuckSince >= THRESHOLD_MS) {
                Hang hang = describe(slot, subject, (now - slot.stuckSince) / 1000);
                if (hang != null) {
                    slot.reported = hang;
                    synchronized (REPORTED) {
                        REPORTED.add(hang);
                        writeFile();
                    }
                    EmergencyShelter.LOGGER.error("[紧急避险] {} 已经卡住 {} 秒（{} @ {} {}, {}, {}，模组：{}）。如果游戏因此被关闭，下次打开存档时会自动隔离它。{}",
                            hang.thread, hang.seconds, hang.type, hang.dim, hang.x, hang.y, hang.z, hang.mod, hang.stack);
                }
            } else if (slot.reported != null) {
                slot.reported.seconds = (now - slot.stuckSince) / 1000;
            }
        }
        checkTick();
    }

    /**
     * 整个服务端刻卡住，而且不是卡在某个方块实体 / 实体里（例如模组或脚本在每刻事件里死循环）。
     * 判断方法与原版的看门狗相同：下一刻本该开始的时间已经过去很久。
     */
    private static void checkTick() {
        MinecraftServer server = WorldGuard.server();
        if (server == null || !server.isRunning() || !WorldGuard.running()) {
            clearTick();
            return;
        }
        long lagMs = (Util.getNanos() - server.getNextTickTime()) / 1_000_000L;
        if (lagMs < TICK_THRESHOLD_MS) {
            clearTick();
            return;
        }
        if (tickReported != null) {
            tickReported.seconds = lagMs / 1000;
            return;
        }
        Thread thread = server.getRunningThread();
        for (Slot slot : SLOTS) {
            if (slot.thread == thread && (slot.subject != null || slot.reported != null)) {
                if (!tickSkipLogged) {
                    tickSkipLogged = true;
                    EmergencyShelter.LOGGER.warn("[紧急避险] 服务端已经 {} 秒没有完成一刻，正停在 {} 上", lagMs / 1000, slot.subject);
                }
                return; // 卡在某个具体的东西里，由它自己的记录处理
            }
        }
        Hang hang = new Hang();
        hang.kind = "TICK";
        hang.thread = thread.getName();
        hang.seconds = lagMs / 1000;
        hang.time = System.currentTimeMillis();
        StackTraceElement[] frames = thread.getStackTrace();
        hang.stack = Culprits.brief(frames, 24);
        hang.mod = Culprits.modOf(frames);
        hang.type = hang.mod == null ? "?" : hang.mod;
        hang.dim = "";
        tickReported = hang;
        boolean waitingForOther;
        synchronized (REPORTED) {
            // 主线程只是在等另一个已经记下的卡住（例如在等卡住的世界生成），或者看不出是哪个模组：只写日志
            waitingForOther = !REPORTED.isEmpty();
            if (!waitingForOther && hang.mod != null) {
                REPORTED.add(hang);
                writeFile();
            }
        }
        EmergencyShelter.LOGGER.error("[紧急避险] 服务端已经 {} 秒没有完成一刻（模组：{}）。如果游戏因此被关闭，下次启动时会先恢复这个模组改动过的配置或脚本。{}",
                hang.seconds, hang.mod == null ? "未知" : Culprits.displayName(hang.mod), hang.stack);
    }

    private static void clearTick() {
        tickSkipLogged = false;
        if (tickReported == null) {
            return;
        }
        Hang hang = tickReported;
        tickReported = null;
        synchronized (REPORTED) {
            if (REPORTED.remove(hang)) {
                writeFile();
            }
        }
        EmergencyShelter.LOGGER.warn("[紧急避险] 服务端卡住一段时间后自己恢复了，不做处理");
    }

    private static void resolved(Slot slot) {
        Hang hang = slot.reported;
        slot.reported = null;
        synchronized (REPORTED) {
            REPORTED.remove(hang);
            writeFile();
        }
        EmergencyShelter.LOGGER.warn("[紧急避险] {} 卡住一段时间后自己恢复了，不做处理", hang.type);
    }

    @Nullable
    private static Hang describe(Slot slot, Object subject, long seconds) {
        Hang hang = new Hang();
        hang.thread = slot.thread.getName();
        hang.seconds = seconds;
        hang.time = System.currentTimeMillis();
        StackTraceElement[] frames = slot.thread.getStackTrace();
        hang.stack = Culprits.brief(frames, 24);
        hang.mod = Culprits.modOf(frames);
        Object level = slot.level;
        try {
            switch (slot.kind) {
                case BLOCK_ENTITY -> {
                    BlockEntity be = (BlockEntity) subject;
                    hang.kind = "BLOCK_ENTITY";
                    hang.type = String.valueOf(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType()));
                    setPos(hang, be.getBlockPos());
                    hang.dim = ((Level) level).dimension().location().toString();
                }
                case ENTITY -> {
                    Entity entity = (Entity) subject;
                    hang.kind = "ENTITY";
                    hang.type = String.valueOf(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()));
                    hang.uuid = entity.getUUID().toString();
                    setPos(hang, entity.blockPosition());
                    hang.dim = ((Level) level).dimension().location().toString();
                }
                case FEATURE, STRUCTURE -> {
                    hang.kind = slot.kind == FEATURE ? "FEATURE" : "STRUCTURE";
                    hang.type = String.valueOf(subject);
                    ChunkPos chunk = new ChunkPos(slot.pos);
                    hang.x = chunk.getMiddleBlockX();
                    hang.z = chunk.getMiddleBlockZ();
                    hang.y = 64;
                    hang.dim = ((ServerLevel) level).dimension().location().toString();
                }
                default -> {
                    return null;
                }
            }
        } catch (Throwable t) {
            return null;
        }
        return hang;
    }

    private static void setPos(Hang hang, BlockPos pos) {
        hang.x = pos.getX();
        hang.y = pos.getY();
        hang.z = pos.getZ();
    }

    private static void writeFile() {
        Path root = WorldGuard.worldRoot();
        if (root == null) {
            return;
        }
        Path file = root.resolve("emergencyshelter").resolve(FILE);
        try {
            if (REPORTED.isEmpty()) {
                Files.deleteIfExists(file);
                return;
            }
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(FILE + ".tmp");
            Files.writeString(tmp, GSON.toJson(REPORTED), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            EmergencyShelter.LOGGER.warn("[紧急避险] 无法写入卡死记录", e);
        }
    }

    // ------------------------------------------------------------------ 下次打开存档时

    /** 上次卡住之后游戏被关闭了：隔离卡住的东西。在主世界加载时调用（区块加载之前）。 */
    static void applyPending(ShelterWorldData data, Path worldRoot) {
        Path file = worldRoot.resolve("emergencyshelter").resolve(FILE);
        if (!Files.isRegularFile(file)) {
            return;
        }
        List<Hang> hangs;
        try {
            hangs = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), new TypeToken<List<Hang>>() {
            }.getType());
        } catch (Exception e) {
            hangs = null;
        }
        try {
            Path keep = WorldGuard.quarantineDir(worldRoot).resolve(FILE);
            Files.createDirectories(keep.getParent());
            Files.move(file, keep, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignored) {
            }
        }
        if (hangs == null) {
            return;
        }
        for (Hang hang : hangs) {
            if (hang == null || hang.kind == null || hang.dim == null) {
                continue;
            }
            if (hang.kind.equals("TICK")) {
                if (hang.mod == null) {
                    continue;
                }
                // 配置和脚本的恢复由启动前检查完成（见 early 部分的 WorldSessionCheck），这里只记录
                String why = "上次服务端卡住 " + hang.seconds + " 秒后被关闭" + (hang.mod == null ? "，没能确定是哪个模组" : "，卡在 " + Culprits.displayName(hang.mod));
                WorldGuard.record("HANG_TICK", hang.mod == null ? "?" : hang.mod, null, why);
                continue;
            }
            ResourceLocation dimId = ResourceLocation.tryParse(hang.dim);
            if (dimId == null) {
                continue;
            }
            ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION, dimId);
            String why = "卡住 " + hang.seconds + " 秒后游戏被关闭" + (hang.mod == null ? "" : "（" + Culprits.displayName(hang.mod) + "）");
            String where = WorldGuard.where(dim, new BlockPos(hang.x, hang.y, hang.z));
            switch (hang.kind) {
                case "BLOCK_ENTITY" -> {
                    data.freeze(dim, new BlockPos(hang.x, hang.y, hang.z), hang.type, why);
                    WorldGuard.record("HANG_FROZEN", hang.type, where, why);
                }
                case "ENTITY" -> {
                    try {
                        data.markHangEntity(UUID.fromString(hang.uuid), hang.type, why);
                        WorldGuard.record("HANG_ENTITY", hang.type, where, why);
                    } catch (IllegalArgumentException ignored) {
                    }
                }
                case "FEATURE", "STRUCTURE" -> {
                    data.skipWorldgen(WorldgenGuard.skipKey(dim, new ChunkPos(new BlockPos(hang.x, 0, hang.z)), hang.type), hang.type, why);
                    WorldGuard.record("HANG_WORLDGEN", hang.type, where, why);
                }
                default -> {
                }
            }
            EmergencyShelter.LOGGER.warn("[紧急避险] 上次 {} {} @ {} {}，已隔离", hang.kind, hang.type, where, why);
        }
        mirror(data);
    }

    /** 把存档里的记录同步到各线程都能读的集合。 */
    static void mirror(ShelterWorldData data) {
        HANG_ENTITIES.clear();
        data.hangEntitiesView().forEach((uuid, m) -> HANG_ENTITIES.put(uuid, m.what()));
        WorldgenGuard.SKIP.clear();
        WorldgenGuard.SKIP.addAll(data.skipWorldgenView().keySet());
    }

    /** 读取实体时：它上次卡住导致游戏被关闭了吗？是的话返回原因。 */
    @Nullable
    public static String hangReason(CompoundTag tag) {
        if (HANG_ENTITIES.isEmpty() || !tag.hasUUID("UUID")) {
            return null;
        }
        UUID uuid = tag.getUUID("UUID");
        if (HANG_ENTITIES.remove(uuid) == null) {
            return null;
        }
        ShelterWorldData data = ShelterWorldData.get();
        String why = "上次运行时卡住，导致游戏被关闭";
        if (data != null) {
            ShelterWorldData.Marked m = data.hangEntitiesView().get(uuid);
            if (m != null) {
                why = m.error();
            }
            data.clearHangEntity(uuid);
        }
        return why;
    }
}
