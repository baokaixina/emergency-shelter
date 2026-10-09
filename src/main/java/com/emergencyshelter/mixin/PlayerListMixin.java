package com.emergencyshelter.mixin;

import com.emergencyshelter.Defense;
import com.emergencyshelter.salvage.LostDimension;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** 玩家上次下线时所在的维度已经不存在：原版会把他放到主世界的同一坐标（可能在地下或虚空），这里记下来稍后处理。 */
@Mixin(PlayerList.class)
public abstract class PlayerListMixin {
    @WrapOperation(method = "placeNewPlayer", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerPlayer;setServerLevel(Lnet/minecraft/server/level/ServerLevel;)V"))
    private void emergencyshelter$checkDimension(ServerPlayer player, ServerLevel level, Operation<Void> original,
                                                 @Local ResourceKey<Level> savedDimension) {
        original.call(player, level);
        Defense.quietly("LostDimension.markLost", () -> {
            if (savedDimension != null && !level.dimension().equals(savedDimension)) {
                LostDimension.markLost(player, savedDimension);
            }
        });
    }
}
