package com.emergencyshelter.mixin.guard;

import com.emergencyshelter.Defense;
import com.emergencyshelter.world.RecipeGuard;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.RecipeManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 某一条配方读取时抛出原版不认识的异常（原版：整个配方加载中断，存档打不开）：只跳过这一条。
 * 单独放一个类：这里用到了 @Local，版本变化时只会少这一项保护，不影响其它保护。
 */
@Mixin(RecipeManager.class)
public abstract class RecipeManagerMixin {
    @SuppressWarnings({"rawtypes", "unchecked"})
    @WrapOperation(method = "apply(Ljava/util/Map;Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/util/profiling/ProfilerFiller;)V",
            at = @At(value = "INVOKE", target = "Lcom/mojang/serialization/Codec;parse(Lcom/mojang/serialization/DynamicOps;Ljava/lang/Object;)Lcom/mojang/serialization/DataResult;"))
    private DataResult emergencyshelter$guardRecipe(Codec codec, DynamicOps ops, Object json, Operation<DataResult> original,
                                                    @Local ResourceLocation id) {
        try {
            return original.call(codec, ops, json);
        } catch (Throwable t) {
            try {
                return RecipeGuard.onRecipeFailure(id, t);
            } catch (Throwable own) {
                throw Defense.fallback(t, own);
            }
        }
    }
}
