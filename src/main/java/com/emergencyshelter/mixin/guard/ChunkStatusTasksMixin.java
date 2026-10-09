package com.emergencyshelter.mixin.guard;

import com.emergencyshelter.world.WorldgenGuard;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.chunk.ChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** 新区块里生成初始生物时出错：这次不生成，而不是让游戏崩溃。 */
@Mixin(targets = "net.minecraft.world.level.chunk.status.ChunkStatusTasks")
public abstract class ChunkStatusTasksMixin {
    @WrapOperation(method = "generateSpawn", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/chunk/ChunkGenerator;spawnOriginalMobs(Lnet/minecraft/server/level/WorldGenRegion;)V"))
    private static void emergencyshelter$guardSpawn(ChunkGenerator generator, WorldGenRegion region, Operation<Void> original) {
        WorldgenGuard.spawnOriginalMobs(region.getLevel(), region.getCenter(), () -> original.call(generator, region));
    }
}
