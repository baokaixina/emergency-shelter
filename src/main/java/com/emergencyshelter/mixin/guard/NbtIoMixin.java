package com.emergencyshelter.mixin.guard;

import com.emergencyshelter.world.DeepNbt;
import com.emergencyshelter.world.WorldGuard;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.io.IOException;
import java.nio.file.Path;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import org.spongepowered.asm.mixin.Mixin;

/** 按文件读取的存档数据（level.dat、玩家数据等）嵌套过深或体积过大：读出能用的部分，而不是丢弃或打不开。 */
@Mixin(NbtIo.class)
public abstract class NbtIoMixin {
    @WrapMethod(method = "readCompressed(Ljava/nio/file/Path;Lnet/minecraft/nbt/NbtAccounter;)Lnet/minecraft/nbt/CompoundTag;")
    private static CompoundTag emergencyshelter$readCompressed(Path path, NbtAccounter accounter, Operation<CompoundTag> original) throws IOException {
        try {
            return original.call(path, accounter);
        } catch (Throwable t) {
            if (!WorldGuard.enabled() || !DeepNbt.isLimitProblem(t)) {
                throw WorldGuard.sneakyThrow(t);
            }
            return DeepNbt.recoverFile(path, t);
        }
    }
}
