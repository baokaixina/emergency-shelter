package com.emergencyshelter.mixin.guard;

import com.emergencyshelter.Defense;
import com.emergencyshelter.world.DeepNbt;
import com.emergencyshelter.world.WorldGuard;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.io.IOException;
import java.nio.file.Path;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Mixin;

/**
 * 存档列表、NeoForge 的附加存档数据读取 level.dat 时用的是另一条"只读一部分"的路径：
 * 数据嵌套过深时同样读出能用的部分（否则存档列表里会显示无法读取）。
 */
@Mixin(LevelStorageSource.class)
public abstract class LevelStorageSourceMixin {
    @WrapMethod(method = "readLightweightData")
    private static Tag emergencyshelter$readLightweightData(Path file, Operation<Tag> original) throws IOException {
        try {
            return original.call(file);
        } catch (Throwable t) {
            try {
                if (!WorldGuard.enabled() || !DeepNbt.isLimitProblem(t)) {
                    throw WorldGuard.sneakyThrow(t);
                }
                // 完整读取时会记录并备份，这里只取需要的部分
                CompoundTag tag = DeepNbt.readFile(file).tag();
                CompoundTag data = tag.getCompound("Data");
                data.remove("Player");
                data.remove("WorldGenSettings");
                return tag;
            } catch (Throwable own) {
                throw Defense.fallback(t, own);
            }
        }
    }
}
