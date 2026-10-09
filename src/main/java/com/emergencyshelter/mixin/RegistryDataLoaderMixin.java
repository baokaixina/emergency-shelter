package com.emergencyshelter.mixin;

import com.emergencyshelter.salvage.LenientRegistries;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.util.List;
import java.util.Map;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.packs.resources.ResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RegistryDataLoader.class)
public abstract class RegistryDataLoaderMixin {
    /** 世界加载时的数据包注册表（世界生成、维度等）：失败时跳过出问题的条目重试。 */
    @WrapMethod(method = "load(Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/core/RegistryAccess;Ljava/util/List;)Lnet/minecraft/core/RegistryAccess$Frozen;")
    private static RegistryAccess.Frozen emergencyshelter$lenientLoad(ResourceManager resourceManager, RegistryAccess registryAccess,
                                                                     List<RegistryDataLoader.RegistryData<?>> registryData,
                                                                     Operation<RegistryAccess.Frozen> original) {
        return LenientRegistries.load(resourceManager, registryAccess, registryData, original);
    }

    @Inject(method = "logErrors", at = @At("HEAD"))
    private static void emergencyshelter$captureErrors(Map<ResourceKey<?>, Exception> errors, CallbackInfo ci) {
        LenientRegistries.captureErrors(errors);
    }
}
