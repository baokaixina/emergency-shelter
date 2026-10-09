package com.emergencyshelter.mixin.guard.client;

import com.emergencyshelter.Defense;
import com.emergencyshelter.client.RenderGuard;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;

/** 方块实体渲染出错（原版：Rendering Block Entity 崩溃）：本次运行中不再显示它。 */
@Mixin(BlockEntityRenderDispatcher.class)
public abstract class BlockEntityRenderDispatcherMixin {
    @WrapMethod(method = "render(Lnet/minecraft/world/level/block/entity/BlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;)V")
    private <E extends BlockEntity> void emergencyshelter$guard(E blockEntity, float partialTick, PoseStack pose, MultiBufferSource buffers,
                                                               Operation<Void> original) {
        if (RenderGuard.isHidden(blockEntity)) {
            return;
        }
        int depth = RenderGuard.depth(pose);
        try {
            original.call(blockEntity, partialTick, pose, buffers);
        } catch (Throwable t) {
            try {
                RenderGuard.onBlockEntityFailure(blockEntity, t, pose, depth);
            } catch (Throwable own) {
                throw Defense.fallback(t, own);
            }
        }
    }
}
