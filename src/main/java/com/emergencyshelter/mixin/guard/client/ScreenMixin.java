package com.emergencyshelter.mixin.guard.client;

import com.emergencyshelter.client.RenderGuard;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;

/**
 * 界面处理输入、每刻更新时出错（原版：mouseClicked event handler、Ticking screen 等崩溃）：关掉这个界面；
 * 生成物品提示文字时出错：只显示物品名和一行提示。
 */
@Mixin(Screen.class)
public abstract class ScreenMixin {
    @WrapMethod(method = "wrapScreenError")
    private static void emergencyshelter$guard(Runnable action, String errorDesc, String screenName, Operation<Void> original) {
        try {
            original.call(action, errorDesc, screenName);
        } catch (Throwable t) {
            RenderGuard.onScreenFailure(Minecraft.getInstance().screen, t);
        }
    }

    @WrapMethod(method = "getTooltipFromItem")
    private static List<Component> emergencyshelter$guardTooltip(Minecraft minecraft, ItemStack stack, Operation<List<Component>> original) {
        try {
            return original.call(minecraft, stack);
        } catch (Throwable t) {
            return RenderGuard.onTooltipFailure(stack, t);
        }
    }
}
