package com.emergencyshelter.mixin.guard;

import com.emergencyshelter.world.DataRescue;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;

/** 读取玩家数据出错时（原版会崩溃），依次尝试去掉模组数据、换回上次正常的版本。 */
@Mixin(Entity.class)
public abstract class EntityMixin {
    @WrapMethod(method = "load")
    private void emergencyshelter$load(CompoundTag tag, Operation<Void> original) {
        if ((Object) this instanceof ServerPlayer player) {
            DataRescue.loadPlayer(player, tag, original);
        } else {
            original.call(tag);
        }
    }
}
