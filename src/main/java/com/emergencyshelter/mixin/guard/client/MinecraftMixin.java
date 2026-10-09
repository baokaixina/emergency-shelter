package com.emergencyshelter.mixin.guard.client;

import com.emergencyshelter.Defense;
import com.emergencyshelter.client.ClientSession;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 记下资源包加载的开始、完成和失败（见 {@link ClientSession}）。 */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @Inject(method = "reloadResourcePacks(ZLnet/minecraft/client/Minecraft$GameLoadCookie;)Ljava/util/concurrent/CompletableFuture;", at = @At("HEAD"))
    private void emergencyshelter$reloadStarted(CallbackInfoReturnable<CompletableFuture<Void>> cir) {
        Defense.quietly("ClientSession.reloadStarted", ClientSession::reloadStarted);
    }

    @Inject(method = "onResourceLoadFinished", at = @At("HEAD"))
    private void emergencyshelter$reloadFinished(CallbackInfo ci) {
        Defense.quietly("ClientSession.reloadFinished", ClientSession::reloadFinished);
    }

    @Inject(method = "clearResourcePacksOnError", at = @At("HEAD"))
    private void emergencyshelter$reloadFailed(CallbackInfo ci) {
        Defense.quietly("ClientSession.reloadFailed", ClientSession::reloadFailed);
    }
}
