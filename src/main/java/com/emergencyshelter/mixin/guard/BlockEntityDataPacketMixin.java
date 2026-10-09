package com.emergencyshelter.mixin.guard;

import com.emergencyshelter.world.PacketGuard;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.function.BiFunction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** 方块实体的同步数据包：出错或过大时改为空数据，而不是让玩家被踢出或服务端崩溃。 */
@Mixin(ClientboundBlockEntityDataPacket.class)
public abstract class BlockEntityDataPacketMixin {
    @WrapOperation(method = "create(Lnet/minecraft/world/level/block/entity/BlockEntity;Ljava/util/function/BiFunction;)Lnet/minecraft/network/protocol/game/ClientboundBlockEntityDataPacket;",
            at = @At(value = "INVOKE", target = "Ljava/util/function/BiFunction;apply(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"))
    private static Object emergencyshelter$limit(BiFunction<Object, Object, Object> getter, Object be, Object registries, Operation<Object> original) {
        return PacketGuard.updateTag((BlockEntity) be, () -> (CompoundTag) original.call(getter, be, registries));
    }
}
