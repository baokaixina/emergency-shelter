package com.emergencyshelter.mixin.guard;

import com.emergencyshelter.Defense;
import com.emergencyshelter.world.LoadGuard;
import com.emergencyshelter.world.TickGuard;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin {
    @Shadow
    @Final
    Level level;

    /** 停止运行的方块实体：不给它创建运行器。 */
    @WrapOperation(method = "updateBlockEntityTicker", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/state/BlockState;getTicker(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/level/block/entity/BlockEntityType;)Lnet/minecraft/world/level/block/entity/BlockEntityTicker;"))
    private <T extends BlockEntity> BlockEntityTicker<T> emergencyshelter$skipFrozen(BlockState state, Level level, BlockEntityType<T> type,
                                                                                    Operation<BlockEntityTicker<T>> original,
                                                                                    @Local(argsOnly = true) BlockEntity blockEntity) {
        boolean frozen;
        try {
            frozen = TickGuard.isFrozen(level, blockEntity);
        } catch (Throwable own) {
            Defense.log("TickGuard.isFrozen", own);
            frozen = false;
        }
        if (frozen) {
            return null;
        }
        return original.call(state, level, type);
    }

    /** 方块被拆除或替换：停止运行的记录随之作废。 */
    @Inject(method = "removeBlockEntity", at = @At("HEAD"))
    private void emergencyshelter$onRemove(BlockPos pos, CallbackInfo ci) {
        try {
            TickGuard.onBlockEntityRemoved(level, pos);
        } catch (Throwable own) {
            Defense.log("TickGuard.onBlockEntityRemoved", own);
        }
    }

    /** 按需加载方块实体时，让读取出错的处理知道是哪个区块。 */
    @WrapMethod(method = "promotePendingBlockEntity")
    private BlockEntity emergencyshelter$promote(BlockPos pos, CompoundTag tag, Operation<BlockEntity> original) {
        LevelChunk previous = LoadGuard.current();
        LoadGuard.enter((LevelChunk) (Object) this);
        try {
            return original.call(pos, tag);
        } finally {
            LoadGuard.exit(previous);
        }
    }
}
