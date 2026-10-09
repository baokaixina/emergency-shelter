package com.emergencyshelter.mixin.guard.client;

import com.emergencyshelter.client.RenderGuard;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** 粒子运行或渲染出错（原版：Ticking Particle / Rendering Particle 崩溃）：移除这个粒子。 */
@Mixin(ParticleEngine.class)
public abstract class ParticleEngineMixin {
    @WrapMethod(method = "tickParticle")
    private void emergencyshelter$guardTick(Particle particle, Operation<Void> original) {
        try {
            original.call(particle);
        } catch (Throwable t) {
            RenderGuard.onParticleFailure(particle, t);
        }
    }

    @WrapOperation(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/particle/Particle;render(Lcom/mojang/blaze3d/vertex/VertexConsumer;Lnet/minecraft/client/Camera;F)V"))
    private void emergencyshelter$guardRender(Particle particle, VertexConsumer buffer, Camera camera, float partialTick, Operation<Void> original) {
        try {
            original.call(particle, buffer, camera, partialTick);
        } catch (Throwable t) {
            RenderGuard.onParticleFailure(particle, t);
        }
    }
}
