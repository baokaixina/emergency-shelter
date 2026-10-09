package com.emergencyshelter.mixin;

import com.emergencyshelter.Defense;
import com.emergencyshelter.salvage.SalvagingItemCodec;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.serialization.Codec;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 在 ItemStack.CODEC / SINGLE_ITEM_CODEC 刚创建、还没被其它编解码器引用之前把它们包起来，
 * 这样 OPTIONAL_CODEC、容器组件、潜影盒内容等所有派生的编解码器都会经过紧急避险。
 */
@Mixin(ItemStack.class)
public abstract class ItemStackMixin {
    @ModifyExpressionValue(method = "<clinit>", at = @At(value = "INVOKE",
            target = "Lcom/mojang/serialization/Codec;lazyInitialized(Ljava/util/function/Supplier;)Lcom/mojang/serialization/Codec;",
            ordinal = 0))
    private static Codec<ItemStack> emergencyshelter$wrapCodec(Codec<ItemStack> original) {
        // 这里在 ItemStack 类初始化时运行：出错会让整个游戏无法启动，必须退回原版编解码器
        return Defense.quietly("SalvagingItemCodec.wrap", () -> SalvagingItemCodec.wrap(original, false), original);
    }

    @ModifyExpressionValue(method = "<clinit>", at = @At(value = "INVOKE",
            target = "Lcom/mojang/serialization/Codec;lazyInitialized(Ljava/util/function/Supplier;)Lcom/mojang/serialization/Codec;",
            ordinal = 1))
    private static Codec<ItemStack> emergencyshelter$wrapSingleCodec(Codec<ItemStack> original) {
        return Defense.quietly("SalvagingItemCodec.wrapSingle", () -> SalvagingItemCodec.wrap(original, true), original);
    }
}
