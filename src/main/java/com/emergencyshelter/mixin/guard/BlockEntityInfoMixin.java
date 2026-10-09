package com.emergencyshelter.mixin.guard;

import com.emergencyshelter.world.PacketGuard;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** 区块数据包里方块实体的同步数据：出错或超过客户端上限时不发送，而不是让玩家被踢出。 */
@Mixin(targets = "net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData$BlockEntityInfo")
public abstract class BlockEntityInfoMixin {
    @WrapOperation(method = "create", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/entity/BlockEntity;getUpdateTag(Lnet/minecraft/core/HolderLookup$Provider;)Lnet/minecraft/nbt/CompoundTag;"))
    private static CompoundTag emergencyshelter$limit(BlockEntity be, HolderLookup.Provider registries, Operation<CompoundTag> original) {
        return PacketGuard.updateTag(be, () -> original.call(be, registries));
    }
}
