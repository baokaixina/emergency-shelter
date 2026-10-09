package com.emergencyshelter.mixin;

import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(LevelChunk.class)
public interface LevelChunkInvoker {
    @Invoker("updateBlockEntityTicker")
    <T extends BlockEntity> void emergencyshelter$updateBlockEntityTicker(T blockEntity);
}
