package com.emergencyshelter.mixin.guard;

import com.emergencyshelter.Defense;
import com.emergencyshelter.world.WorldGuard;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 记下服务端崩溃（单人游戏的内置服务端也一样），这次就不把存档记为"正常"。 */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {
    @Inject(method = "runServer", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/MinecraftServer;onServerCrash(Lnet/minecraft/CrashReport;)V"))
    private void emergencyshelter$onCrash(CallbackInfo ci) {
        Defense.quietly("WorldGuard.onServerCrash", WorldGuard::onServerCrash);
    }
}
