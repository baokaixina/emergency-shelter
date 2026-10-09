package com.emergencyshelter.mixin.guard;

import com.emergencyshelter.Defense;
import com.emergencyshelter.world.ReloadGuard;
import com.llamalad7.mixinextras.sugar.Local;
import java.util.List;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.SimpleReloadInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * 加载数据包时某一个加载器出错：只跳过它，而不是让存档打不开。
 * <p>
 * 只替换"交给运行器的那一个加载器"，不改动 create 的参数列表：其它模组会在列表里按类型查找、排序原版加载器
 * （例如 Forgified Fabric API 要在列表里找到 RecipeManager，找不到就报 "No RecipeManager found in listeners!"）。
 */
@Mixin(SimpleReloadInstance.class)
public abstract class SimpleReloadInstanceMixin {
    @ModifyArg(method = "<init>", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/packs/resources/SimpleReloadInstance$StateFactory;create(Lnet/minecraft/server/packs/resources/PreparableReloadListener$PreparationBarrier;Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/server/packs/resources/PreparableReloadListener;Ljava/util/concurrent/Executor;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;"),
            index = 2)
    private PreparableReloadListener emergencyshelter$guardListener(PreparableReloadListener listener,
                                                                     @Local(argsOnly = true) List<PreparableReloadListener> listeners) {
        return Defense.quietly("ReloadGuard.guard", () -> ReloadGuard.guard(listener, listeners), listener);
    }
}
