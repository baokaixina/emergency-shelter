package com.emergencyshelter.world;

import com.emergencyshelter.EmergencyShelter;
import com.emergencyshelter.mixin.guard.BlockEntityInfoAccessor;
import com.emergencyshelter.mixin.guard.ChunkPacketDataAccessor;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.EncoderException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.zip.Deflater;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.SkipPacketException;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;

/**
 * 联机时"数据太大被踢出"的保护（只在服务端，单人游戏不经过这里）。
 * <p>
 * 原版里，某个方块实体的数据过大（例如塞满物品的管道、蜂箱、存了大量数据的机器），客户端读取时超过 2 MB 上限，
 * 玩家一靠近那里就被踢出（Tried to read NBT tag that was too big / Packet too big），而且每次进服都一样。这里改为：
 * <ul>
 *     <li>同步给客户端的方块实体数据超过上限时不发送（服务端的数据完整保留，只是客户端看不到它的显示细节）；</li>
 *     <li>整个区块的数据包仍然过大时，从最大的方块实体开始去掉同步数据，直到能发送；</li>
 *     <li>其它过大的数据包丢弃这一个，而不是断开玩家的连接。</li>
 * </ul>
 */
public final class PacketGuard {
    /** 客户端读取单个方块实体同步数据的上限是 2 MB（2097152 字节），留出一点余量。 */
    static final long BLOCK_ENTITY_LIMIT = 2_000_000;
    /** 压缩后单个数据包的上限（3 字节长度前缀）。 */
    private static final int FRAME_LIMIT = 2_097_151 - 64;
    /** 客户端解压后单个数据包的上限。 */
    private static final int UNCOMPRESSED_LIMIT = 8_388_608 - 64;
    private static final int CHECK_ABOVE = 1_900_000;
    private static final Set<Long> REPORTED_POSITIONS = ConcurrentHashMap.newKeySet();
    private static final Map<String, AtomicInteger> SKIPPED = new ConcurrentHashMap<>();
    /** 这些模组会提高联机数据包和同步数据的上限：装了它们时再按原版上限判断，会把它们本来允许的包丢掉。 */
    private static final List<String> LIMIT_RAISING_MODS = List.of("packetfixer", "xlpackets", "connectivity");
    @Nullable
    private static volatile Boolean limitsRaised;

    private PacketGuard() {
    }

    // ------------------------------------------------------------------ 方块实体同步数据

    /** 生成方块实体的同步数据：出错或过大时改为空数据。 */
    public static CompoundTag updateTag(BlockEntity be, Supplier<CompoundTag> getter) {
        if (!WorldGuard.enabled() || !(be.getLevel() instanceof ServerLevel level)) {
            return getter.get();
        }
        CompoundTag tag;
        try {
            tag = getter.get();
        } catch (Throwable t) {
            if (TickGuard.fatal(t)) {
                throw WorldGuard.sneakyThrow(t);
            }
            com.emergencyshelter.Defense.quietly("PacketGuard.report", () -> report(level, be, "生成同步数据时出错，本次不发送：" + WorldGuard.message(t), t));
            return new CompoundTag();
        }
        if (tag == null) {
            return null;
        }
        try {
            long size = tag.sizeInBytes();
            if (size <= BLOCK_ENTITY_LIMIT || limitsRaised()) {
                return tag;
            }
            report(level, be, "同步数据 " + mb(size) + "，超过客户端 2 MB 的读取上限（原版会让靠近这里的玩家被踢出），没有发送给客户端（服务端的数据完整保留）", null);
            return new CompoundTag();
        } catch (Throwable own) {
            com.emergencyshelter.Defense.log("PacketGuard.updateTag", own);
            return tag; // 照原版发送
        }
    }

    private static void report(ServerLevel level, BlockEntity be, String detail, @Nullable Throwable error) {
        if (!REPORTED_POSITIONS.add(be.getBlockPos().asLong() ^ level.dimension().location().hashCode())) {
            return;
        }
        String type = String.valueOf(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType()));
        String where = WorldGuard.where(level.dimension(), be.getBlockPos());
        if (error != null) {
            EmergencyShelter.LOGGER.error("[紧急避险] 方块实体 {} @ {}：{}", type, where, detail, error);
        } else {
            EmergencyShelter.LOGGER.warn("[紧急避险] 方块实体 {} @ {}：{}", type, where, detail);
        }
        WorldGuard.record("SYNC_TRIMMED", type, where, detail);
    }

    /** 是否装了提高数据包上限的模组（此时不按原版上限处理，交给那个模组）。 */
    static boolean limitsRaised() {
        Boolean raised = limitsRaised;
        if (raised == null) {
            raised = false;
            try {
                ModList mods = ModList.get();
                if (mods != null) {
                    for (String id : LIMIT_RAISING_MODS) {
                        if (mods.isLoaded(id)) {
                            raised = true;
                            EmergencyShelter.LOGGER.info("[紧急避险] 检测到 {} 提高了数据包上限，数据包大小检查交给它处理", id);
                            break;
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            limitsRaised = raised;
        }
        return raised;
    }

    // ------------------------------------------------------------------ 编码后的数据包

    public interface Encode {
        void run() throws Exception;
    }

    /** 数据包已经编码进 out（从 start 开始）。太大时设法缩小，实在不行就丢弃这一个包。 */
    public static void checkEncoded(ChannelHandlerContext ctx, Packet<?> packet, ByteBuf out, int start, Encode reencode) throws Exception {
        int size = out.writerIndex() - start;
        if (size < CHECK_ABOVE || !WorldGuard.enabled() || limitsRaised()) {
            return;
        }
        boolean compressed = ctx.pipeline().get("compress") != null;
        if (fits(out, start, compressed)) {
            return;
        }
        String type = String.valueOf(packet.type().id());
        if (packet instanceof ClientboundLevelChunkWithLightPacket chunk) {
            List<Object> infos = ((ChunkPacketDataAccessor) chunk.getChunkData()).emergencyshelter$blockEntities();
            List<Object> withTag = new ArrayList<>();
            for (Object info : infos) {
                if (((BlockEntityInfoAccessor) info).emergencyshelter$tag() != null) {
                    withTag.add(info);
                }
            }
            withTag.sort(Comparator.comparingLong((Object info) -> ((BlockEntityInfoAccessor) info).emergencyshelter$tag().sizeInBytes()).reversed());
            // 每次去掉一半，直到能发送
            int stripped = 0;
            int step = Math.max(1, withTag.size() / 8);
            while (stripped < withTag.size()) {
                for (int i = 0; i < step && stripped < withTag.size(); i++, stripped++) {
                    ((BlockEntityInfoAccessor) withTag.get(stripped)).emergencyshelter$setTag(null);
                }
                out.writerIndex(start);
                reencode.run();
                if (fits(out, start, compressed)) {
                    record("CHUNK_TRIMMED", "区块 " + chunk.getX() + ", " + chunk.getZ(), ctx,
                            "区块数据包 " + mb(size) + " 超过联机上限，已去掉其中 " + stripped + " 个方块实体的显示数据后发送（服务端数据完整保留）");
                    return;
                }
                step = Math.max(1, step * 2);
            }
        }
        out.writerIndex(start);
        record("PACKET_SKIPPED", type, ctx, "数据包 " + mb(size) + " 超过联机上限，原版会让玩家断开连接；已丢弃这一个包");
        throw new SkipPacketException(new EncoderException("Packet too large: " + type + " " + size));
    }

    private static boolean fits(ByteBuf out, int start, boolean compressed) {
        int size = out.writerIndex() - start;
        if (!compressed) {
            return size <= FRAME_LIMIT;
        }
        if (size > UNCOMPRESSED_LIMIT) {
            return false;
        }
        return deflatedSize(out, start, size) <= FRAME_LIMIT;
    }

    private static long deflatedSize(ByteBuf out, int start, int size) {
        Deflater deflater = new Deflater();
        try {
            byte[] input = new byte[size];
            out.getBytes(start, input);
            deflater.setInput(input);
            deflater.finish();
            byte[] scratch = new byte[65536];
            long total = 0;
            while (!deflater.finished()) {
                total += deflater.deflate(scratch);
            }
            return total;
        } finally {
            deflater.end();
        }
    }

    private static void record(String kind, String what, ChannelHandlerContext ctx, String detail) {
        String player = null;
        try {
            if (ctx.pipeline().get("packet_handler") instanceof Connection connection
                    && connection.getPacketListener() instanceof ServerGamePacketListenerImpl listener) {
                player = listener.player.getName().getString();
            }
        } catch (Throwable ignored) {
        }
        int n = SKIPPED.computeIfAbsent(kind + " " + what, k -> new AtomicInteger()).incrementAndGet();
        String full = detail + (player == null ? "" : "（玩家 " + player + "）");
        if (n == 1) {
            EmergencyShelter.LOGGER.warn("[紧急避险] {}：{}", what, full);
        }
        if (n <= 3) {
            WorldGuard.record(kind, what, null, full);
        }
    }

    private static String mb(long bytes) {
        return String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0);
    }
}
