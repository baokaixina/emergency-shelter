package com.emergencyshelter.world;

import com.emergencyshelter.EmergencyShelter;
import com.emergencyshelter.registry.ShelterRegistries;
import com.emergencyshelter.salvage.ChunkSalvage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.Nullable;

/**
 * 方块实体（箱子、机器）的数据读取出错时，原版只记一条日志就把它丢掉，下次存盘时里面的东西就永久消失了。
 * 这里改成：把方块换成占位方块，原方块和全部数据存进区块记录，模组有变化或手动重试后自动换回去再试。
 */
public final class LoadGuard {
    /** 正在为哪个区块加载方块实体（区块加载完成时、或之后按需加载时设置）。 */
    private static final ThreadLocal<LevelChunk> CHUNK = new ThreadLocal<>();
    private static final ThreadLocal<Throwable> LAST_ERROR = new ThreadLocal<>();

    private LoadGuard() {
    }

    @Nullable
    public static LevelChunk.PostLoadProcessor wrapPostLoad(@Nullable LevelChunk.PostLoadProcessor processor) {
        if (processor == null) {
            return null;
        }
        return chunk -> {
            LevelChunk previous = CHUNK.get();
            CHUNK.set(chunk);
            try {
                processor.run(chunk);
            } finally {
                CHUNK.set(previous);
            }
        };
    }

    public static void enter(LevelChunk chunk) {
        CHUNK.set(chunk);
    }

    public static void exit(@Nullable LevelChunk previous) {
        CHUNK.set(previous);
    }

    @Nullable
    public static LevelChunk current() {
        return CHUNK.get();
    }

    /** BlockEntity.loadWithComponents 抛出的异常（原版在外面接住并丢弃，这里先记下来用于提示）。 */
    public static void rememberError(Throwable error) {
        LAST_ERROR.set(error);
    }

    /** BlockEntity.loadStatic 返回之后。 */
    public static void afterLoadStatic(@Nullable BlockEntity result, BlockPos pos, BlockState state, CompoundTag tag) {
        Throwable error = LAST_ERROR.get();
        LAST_ERROR.remove();
        if (result != null) {
            return;
        }
        LevelChunk chunk = CHUNK.get();
        if (chunk == null || !(chunk.getLevel() instanceof ServerLevel level) || !WorldGuard.enabled() || !ShelterRegistries.PLACEHOLDER_BLOCK.isBound()) {
            return;
        }
        String id = tag.getString("id");
        ResourceLocation type = ResourceLocation.tryParse(id);
        if (type == null || !BuiltInRegistries.BLOCK_ENTITY_TYPE.containsKey(type) || state.is(ShelterRegistries.PLACEHOLDER_BLOCK.get())
                || !state.hasBlockEntity()) {
            return; // 类型不存在（由占位机制处理）或格式不合法：保持原版行为
        }
        if (error == null) {
            error = new IllegalStateException("创建方块实体失败（详见日志中 Failed to create block entity）");
        }
        try {
            ChunkSalvage.quarantineBlock(chunk, pos, state, tag, WorldGuard.faultTag("BLOCK_ENTITY_LOAD", error));
            EmergencyShelter.LOGGER.error("[紧急避险] 方块实体 {} @ {} 的数据读取出错（原版会丢掉它的数据），已转为占位方块完整保存", id, pos);
            WorldGuard.record("BLOCK_ENTITY_LOAD", id, WorldGuard.where(level.dimension(), pos), WorldGuard.message(error));
        } catch (Throwable t) {
            EmergencyShelter.LOGGER.error("[紧急避险] 隔离方块实体 {} @ {} 失败", id, pos, t);
        }
    }
}
