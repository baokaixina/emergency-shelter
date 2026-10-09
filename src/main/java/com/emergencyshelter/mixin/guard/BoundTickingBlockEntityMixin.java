package com.emergencyshelter.mixin.guard;

import com.emergencyshelter.world.HangGuard;
import com.emergencyshelter.world.TickGuard;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** 方块实体运行时出错：停止它的运行，而不是让整个游戏崩溃；卡住时记下是哪个方块实体。 */
@Mixin(targets = "net.minecraft.world.level.chunk.LevelChunk$BoundTickingBlockEntity")
public abstract class BoundTickingBlockEntityMixin {
    @WrapOperation(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/entity/BlockEntityTicker;tick(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/entity/BlockEntity;)V"))
    private void emergencyshelter$guardTick(BlockEntityTicker<BlockEntity> ticker, Level level, BlockPos pos, BlockState state,
                                            BlockEntity blockEntity, Operation<Void> original) {
        HangGuard.Slot slot = level.isClientSide ? null : HangGuard.begin(HangGuard.BLOCK_ENTITY, blockEntity, level, 0L);
        try {
            original.call(ticker, level, pos, state, blockEntity);
        } catch (Throwable t) {
            TickGuard.onBlockEntityTickFailure(level, pos, blockEntity, t);
        } finally {
            HangGuard.end(slot);
        }
    }
}
