package com.emergencyshelter.mixin.guard;

import com.emergencyshelter.world.ChunkRescue;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 区块读取失败、原版准备重新生成它之前：备份区域文件。 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapMixin {
    @Shadow
    @Final
    ServerLevel level;

    @Inject(method = "handleChunkLoadFailure", at = @At("HEAD"))
    private void emergencyshelter$beforeRegenerate(Throwable exception, ChunkPos chunkPos, CallbackInfoReturnable<ChunkAccess> cir) {
        ChunkRescue.beforeRegenerate(level, chunkPos, exception);
    }
}
