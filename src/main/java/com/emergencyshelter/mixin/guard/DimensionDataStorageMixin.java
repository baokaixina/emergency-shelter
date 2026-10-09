package com.emergencyshelter.mixin.guard;

import com.emergencyshelter.Defense;
import com.emergencyshelter.world.DataRescue;
import com.emergencyshelter.world.DeepNbt;
import com.emergencyshelter.world.WorldGuard;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.datafixers.DataFixer;
import java.io.File;
import java.io.IOException;
import java.util.function.BiFunction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * 模组的存档数据：
 * <ul>
 *     <li>嵌套过深：读出能用的部分；</li>
 *     <li>读不出来（原版会当成空数据）：换回上次正常退出时的版本。</li>
 * </ul>
 */
@Mixin(DimensionDataStorage.class)
public abstract class DimensionDataStorageMixin {
    @Shadow
    @Final
    private File dataFolder;

    @Shadow
    @Final
    private DataFixer fixerUpper;

    @WrapMethod(method = "readSavedData")
    private SavedData emergencyshelter$readSavedData(BiFunction<CompoundTag, HolderLookup.Provider, SavedData> reader, DataFixTypes dataFixType,
                                                     String filename, Operation<SavedData> original) {
        SavedData result = original.call(reader, dataFixType, filename);
        Defense.quietly("DeepNbt.takeRecovered", () -> {
            if (DeepNbt.takeRecovered() && result != null) {
                result.setDirty(); // 读出的是修好的版本：下次保存时写回去
            }
        });
        if (result != null) {
            return result;
        }
        return Defense.quietly("DataRescue.onSavedDataNull",
                () -> DataRescue.onSavedDataNull(dataFolder, filename, () -> original.call(reader, dataFixType, filename)), null);
    }

    @WrapMethod(method = "readTagFromDisk")
    private CompoundTag emergencyshelter$readTagFromDisk(String filename, @Nullable DataFixTypes dataFixType, int version,
                                                         Operation<CompoundTag> original) throws IOException {
        try {
            return original.call(filename, dataFixType, version);
        } catch (Throwable t) {
            try {
                if (!WorldGuard.enabled() || !DeepNbt.isLimitProblem(t)) {
                    throw WorldGuard.sneakyThrow(t);
                }
                CompoundTag tag = DeepNbt.recoverFile(new File(dataFolder, filename + ".dat").toPath(), t);
                return dataFixType == null ? tag : dataFixType.update(fixerUpper, tag, NbtUtils.getDataVersion(tag, 1343), version);
            } catch (Throwable own) {
                throw Defense.fallback(t, own);
            }
        }
    }
}
