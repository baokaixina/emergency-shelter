package com.emergencyshelter.mixin.guard;

import com.emergencyshelter.Defense;
import com.emergencyshelter.world.LoadGuard;
import com.emergencyshelter.world.WorldGuard;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;

/** 方块实体的数据读取出错时，原版会丢掉它；这里记下来交给 LoadGuard 隔离保存。 */
@Mixin(BlockEntity.class)
public abstract class BlockEntityMixin {
    @WrapMethod(method = "loadStatic")
    private static BlockEntity emergencyshelter$loadStatic(BlockPos pos, BlockState state, CompoundTag tag, HolderLookup.Provider registries,
                                                          Operation<BlockEntity> original) {
        BlockEntity result = original.call(pos, state, tag, registries);
        Defense.quietly("LoadGuard.afterLoadStatic", () -> LoadGuard.afterLoadStatic(result, pos, state, tag));
        return result;
    }

    @WrapMethod(method = "loadWithComponents")
    private void emergencyshelter$rememberError(CompoundTag tag, HolderLookup.Provider registries, Operation<Void> original) {
        try {
            original.call(tag, registries);
        } catch (Throwable t) {
            Defense.quietly("LoadGuard.rememberError", () -> LoadGuard.rememberError(t));
            throw WorldGuard.sneakyThrow(t);
        }
    }
}
