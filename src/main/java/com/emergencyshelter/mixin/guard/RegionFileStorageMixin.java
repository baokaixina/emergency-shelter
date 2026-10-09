package com.emergencyshelter.mixin.guard;

import com.emergencyshelter.world.DeepNbt;
import com.emergencyshelter.world.WorldGuard;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Path;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** 区块数据（地形、实体、兴趣点）嵌套过深：读出能用的部分，而不是把整个区块重新生成。 */
@Mixin(RegionFileStorage.class)
public abstract class RegionFileStorageMixin {
    @Shadow
    @Final
    private Path folder;

    @Shadow
    private RegionFile getRegionFile(ChunkPos chunkPos) throws IOException {
        throw new AssertionError();
    }

    @Shadow
    protected abstract void write(ChunkPos chunkPos, CompoundTag chunkData) throws IOException;

    @WrapMethod(method = "read")
    private CompoundTag emergencyshelter$read(ChunkPos pos, Operation<CompoundTag> original) throws IOException {
        try {
            return original.call(pos);
        } catch (Throwable t) {
            if (!WorldGuard.enabled() || !DeepNbt.isLimitProblem(t)) {
                throw WorldGuard.sneakyThrow(t);
            }
            byte[] raw;
            try (DataInputStream in = getRegionFile(pos).getChunkDataInputStream(pos)) {
                if (in == null) {
                    throw WorldGuard.sneakyThrow(t);
                }
                raw = in.readAllBytes();
            }
            Path region = folder.resolve("r." + pos.getRegionX() + "." + pos.getRegionZ() + ".mca");
            CompoundTag fixed = DeepNbt.recoverChunk(raw, region, pos.x + "," + pos.z, t);
            // 原始数据已经备份：把修好的版本写回去，以后不用每次都再修一遍
            write(pos, fixed);
            return fixed;
        }
    }
}
