package com.emergencyshelter.mixin.guard.client;

import com.emergencyshelter.Defense;
import com.emergencyshelter.client.RenderGuard;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** 界面渲染出错（原版：Rendering screen 崩溃）：关掉这个界面。 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @WrapOperation(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/neoforged/neoforge/client/ClientHooks;drawScreen(Lnet/minecraft/client/gui/screens/Screen;Lnet/minecraft/client/gui/GuiGraphics;IIF)V"))
    private void emergencyshelter$guardScreen(Screen screen, GuiGraphics graphics, int mouseX, int mouseY, float partialTick, Operation<Void> original) {
        int depth = RenderGuard.depth(graphics.pose());
        try {
            original.call(screen, graphics, mouseX, mouseY, partialTick);
        } catch (Throwable t) {
            try {
                while (RenderGuard.depth(graphics.pose()) > Math.max(1, depth)) {
                    graphics.pose().popPose();
                }
                RenderGuard.onScreenFailure(screen, t);
            } catch (Throwable own) {
                throw Defense.fallback(t, own);
            }
        }
    }
}
