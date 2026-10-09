package com.emergencyshelter.mixin.guard;

import com.emergencyshelter.Defense;
import com.emergencyshelter.world.DataRescue;
import com.emergencyshelter.world.WorldGuard;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.serialization.Dynamic;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** level.dat 和 level.dat_old 都读不出来时（原版无法打开存档），改用上次正常退出时的副本。 */
@Mixin(LevelStorageSource.LevelStorageAccess.class)
public abstract class LevelStorageAccessMixin {
    @Shadow
    @Final
    LevelStorageSource.LevelDirectory levelDirectory;

    @WrapMethod(method = "getDataTag(Z)Lcom/mojang/serialization/Dynamic;")
    private Dynamic<?> emergencyshelter$getDataTag(boolean useFallback, Operation<Dynamic<?>> original) {
        try {
            return original.call(useFallback);
        } catch (Throwable t) {
            boolean retry;
            try {
                retry = useFallback && WorldGuard.enabled() && DataRescue.prepareLevelDatFallback(levelDirectory.path());
            } catch (Throwable own) {
                throw Defense.fallback(t, own);
            }
            if (retry) {
                try {
                    return original.call(true);
                } catch (Throwable again) {
                    t.addSuppressed(again); // 正常副本也读不出来：照原版抛出最初的错误
                }
            }
            throw WorldGuard.sneakyThrow(t);
        }
    }
}
