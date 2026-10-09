package com.emergencyshelter.mixin.guard.client;

import com.emergencyshelter.Defense;
import com.emergencyshelter.client.RenderGuard;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;

/** 实体渲染出错（原版：Rendering entity in world 崩溃）：本次运行中不再显示它。 */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin {
    @WrapMethod(method = "render(Lnet/minecraft/world/entity/Entity;DDDFFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V")
    private <E extends Entity> void emergencyshelter$guard(E entity, double x, double y, double z, float rotationYaw, float partialTicks, PoseStack pose,
                                                          MultiBufferSource buffers, int packedLight, Operation<Void> original) {
        if (RenderGuard.isHidden(entity)) {
            return;
        }
        int depth = RenderGuard.depth(pose);
        try {
            original.call(entity, x, y, z, rotationYaw, partialTicks, pose, buffers, packedLight);
        } catch (Throwable t) {
            try {
                RenderGuard.onEntityFailure(entity, t, pose, depth);
            } catch (Throwable own) {
                throw Defense.fallback(t, own);
            }
        }
    }
}
