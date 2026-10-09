package com.emergencyshelter.mixin.guard.client;

import com.emergencyshelter.client.RenderGuard;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** 物品图标、物品提示框渲染出错（原版：Rendering item 等崩溃）：这个物品以后不画图标 / 这次不画提示框。 */
@Mixin(GuiGraphics.class)
public abstract class GuiGraphicsMixin {
    @Shadow
    public abstract PoseStack pose();

    @WrapOperation(method = "renderItem(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;IIII)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/entity/ItemRenderer;getModel(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/LivingEntity;I)Lnet/minecraft/client/resources/model/BakedModel;"))
    private BakedModel emergencyshelter$guardModel(ItemRenderer renderer, ItemStack stack, Level level, LivingEntity entity, int seed,
                                                   Operation<BakedModel> original) {
        if (!RenderGuard.isBroken(stack)) {
            int depth = RenderGuard.depth(pose());
            try {
                return original.call(renderer, stack, level, entity, seed);
            } catch (Throwable t) {
                RenderGuard.onItemFailure(stack, t, pose(), depth);
            }
        }
        return Minecraft.getInstance().getModelManager().getMissingModel();
    }

    @WrapOperation(method = "renderItem(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;IIII)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/entity/ItemRenderer;render(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;ZLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;IILnet/minecraft/client/resources/model/BakedModel;)V"))
    private void emergencyshelter$guardRender(ItemRenderer renderer, ItemStack stack, ItemDisplayContext context, boolean leftHand, PoseStack pose,
                                              MultiBufferSource buffers, int light, int overlay, BakedModel model, Operation<Void> original) {
        if (RenderGuard.isBroken(stack)) {
            return;
        }
        int depth = RenderGuard.depth(pose);
        try {
            original.call(renderer, stack, context, leftHand, pose, buffers, light, overlay, model);
        } catch (Throwable t) {
            RenderGuard.onItemFailure(stack, t, pose, depth);
        }
    }

    @WrapMethod(method = "renderTooltipInternal")
    private void emergencyshelter$guardTooltip(Font font, List<ClientTooltipComponent> components, int mouseX, int mouseY,
                                               ClientTooltipPositioner positioner, Operation<Void> original) {
        int depth = RenderGuard.depth(pose());
        try {
            original.call(font, components, mouseX, mouseY, positioner);
        } catch (Throwable t) {
            RenderGuard.onTooltipRenderFailure(t, pose(), depth);
        }
    }
}
