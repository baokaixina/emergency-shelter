package com.emergencyshelter.mixin;

import com.emergencyshelter.salvage.ChunkSalvage;
import com.emergencyshelter.world.ChunkRescue;
import com.emergencyshelter.world.LoadGuard;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.IEventBus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChunkSerializer.class)
public abstract class ChunkSerializerMixin {
    /** 原版解析区块之前，先把缺失的方块换成占位（或把占位还原）。 */
    @Inject(method = "read", at = @At("HEAD"))
    private static void emergencyshelter$preprocess(ServerLevel level, PoiManager poiManager, RegionStorageInfo regionStorageInfo,
                                                    ChunkPos pos, CompoundTag tag, CallbackInfoReturnable<ProtoChunk> cir) {
        ChunkSalvage.preprocess(pos, tag);
    }

    /** 区块读取出错（原版会重新生成这个区块）：去掉模组附加的数据再试。 */
    @WrapMethod(method = "read")
    private static ProtoChunk emergencyshelter$rescue(ServerLevel level, PoiManager poiManager, RegionStorageInfo regionStorageInfo,
                                                     ChunkPos pos, CompoundTag tag, Operation<ProtoChunk> original) {
        return ChunkRescue.read(level, poiManager, regionStorageInfo, pos, tag, original);
    }

    /**
     * 某个模组处理区块加载事件时出错：第二次尝试时跳过它，而不是放弃整个区块。
     * 这个调用是 NeoForge 补丁加进来的，不同版本可能不一样：找不到时只少这一项保护，不能让游戏因此无法启动（require = 0）。
     */
    @WrapOperation(method = "read", at = @At(value = "INVOKE", target = "Lnet/neoforged/bus/api/IEventBus;post(Lnet/neoforged/bus/api/Event;)Lnet/neoforged/bus/api/Event;"),
            require = 0)
    private static Event emergencyshelter$guardLoadEvent(IEventBus bus, Event event, Operation<Event> original) {
        // 不能用 @Local 取 read 的参数：C2ME 也包装了这个调用，会把它推迟到 read 返回之后在主线程执行，
        // 那时 @Local 的引用已经失效，会直接抛异常，导致所有区块的加载事件都发不出去。世界和坐标从事件本身取。
        try {
            return original.call(bus, event);
        } catch (Throwable t) {
            ChunkRescue.onLoadEventFailure(event, t);
            return event;
        }
    }

    /** 区块里的方块实体加载时，让读取出错的处理知道是哪个区块。 */
    @ModifyReturnValue(method = "postLoadChunk", at = @At("RETURN"))
    private static LevelChunk.PostLoadProcessor emergencyshelter$wrapPostLoad(LevelChunk.PostLoadProcessor processor) {
        return LoadGuard.wrapPostLoad(processor);
    }
}
