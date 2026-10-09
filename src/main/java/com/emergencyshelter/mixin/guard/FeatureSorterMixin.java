package com.emergencyshelter.mixin.guard;

import com.emergencyshelter.Defense;
import com.emergencyshelter.world.WorldGuard;
import com.emergencyshelter.world.WorldgenGuard;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.util.List;
import java.util.function.Function;
import net.minecraft.core.HolderSet;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import org.spongepowered.asm.mixin.Mixin;

/** 多个模组给生物群系加地物的顺序互相矛盾（原版：Feature order cycle found，存档打不开）：改用兼容顺序。 */
@Mixin(FeatureSorter.class)
public abstract class FeatureSorterMixin {
    @WrapMethod(method = "buildFeaturesPerStep")
    private static <T> List<FeatureSorter.StepFeatureData> emergencyshelter$lenient(List<T> sources, Function<T, List<HolderSet<PlacedFeature>>> toFeatures,
                                                                                    boolean topLevel, Operation<List<FeatureSorter.StepFeatureData>> original) {
        try {
            return original.call(sources, toFeatures, topLevel);
        } catch (IllegalStateException e) {
            // 原版在内部递归查找"是哪几个生物群系"时也会抛出这个异常，那时必须原样抛出
            if (!topLevel || e.getMessage() == null || !e.getMessage().startsWith("Feature order cycle") || !WorldGuard.enabled(s -> s.worldgenGuard)) {
                throw e;
            }
            try {
                return WorldgenGuard.lenientOrder(sources, toFeatures, e.getMessage());
            } catch (Throwable own) {
                throw Defense.fallback(e, own);
            }
        }
    }
}
