package com.emergencyshelter.mixin.guard.client;

import com.emergencyshelter.Defense;
import com.emergencyshelter.client.RenderGuard;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;
import net.neoforged.neoforge.client.gui.GuiLayerManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** 某一层 HUD（模组加的血条、小地图、提示等）渲染出错：本次运行中停用这一层，而不是让游戏崩溃。 */
@Mixin(GuiLayerManager.class)
public abstract class GuiLayerManagerMixin {
    @WrapOperation(method = "renderInner", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/LayeredDraw$Layer;render(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/DeltaTracker;)V"))
    private void emergencyshelter$guardLayer(LayeredDraw.Layer layer, GuiGraphics graphics, DeltaTracker partialTick, Operation<Void> original,
                                             @Local GuiLayerManager.NamedLayer named) {
        if (RenderGuard.isLayerDisabled(layer)) {
            return;
        }
        int depth = RenderGuard.depth(graphics.pose());
        try {
            original.call(layer, graphics, partialTick);
        } catch (Throwable t) {
            try {
                RenderGuard.onLayerFailure(layer, String.valueOf(named.name()), t, graphics.pose(), depth);
            } catch (Throwable own) {
                throw Defense.fallback(t, own);
            }
        }
    }
}
