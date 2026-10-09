package com.emergencyshelter.mixin.compat;

import com.emergencyshelter.EmergencyShelter;
import com.emergencyshelter.compat.ItemResourceCodec;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.world.item.Item;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 精致存储 2：磁盘里的物品资源读不出来时会被直接丢弃。这里把物品资源的编解码器包起来，
 * 让来自缺失模组的物品变成占位资源（数量照常保存），模组回来后自动还原。
 * 不直接依赖精致存储的类（通过反射构造 ItemResource），没装精致存储时这个 mixin 不会生效。
 */
@Pseudo
@Mixin(targets = "com.refinedmods.refinedstorage.common.support.resource.ResourceCodecs")
public abstract class RefinedStorageResourceCodecsMixin {
    private static final String ITEM_RESOURCE = "com.refinedmods.refinedstorage.common.support.resource.ItemResource";

    @ModifyExpressionValue(method = "<clinit>", at = @At(value = "INVOKE",
            target = "Lcom/mojang/serialization/codecs/RecordCodecBuilder;mapCodec(Ljava/util/function/Function;)Lcom/mojang/serialization/MapCodec;",
            ordinal = 0), require = 0)
    private static MapCodec<Object> emergencyshelter$wrapItemCodec(MapCodec<Object> original) {
        try {
            Class<?> type = Class.forName(ITEM_RESOURCE, false, RefinedStorageResourceCodecsMixin.class.getClassLoader());
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            MethodHandle item = lookup.findVirtual(type, "item", MethodType.methodType(Item.class));
            MethodHandle components = lookup.findVirtual(type, "components", MethodType.methodType(DataComponentPatch.class));
            MethodHandle constructor = lookup.findConstructor(type, MethodType.methodType(void.class, Item.class, DataComponentPatch.class));
            return new ItemResourceCodec<>(original, "item", "components",
                    r -> (Item) invoke(item, r),
                    r -> (DataComponentPatch) invoke(components, r),
                    (i, c) -> invoke(constructor, i, c));
        } catch (Throwable t) {
            EmergencyShelter.LOGGER.warn("[紧急避险] 精致存储兼容未启用（版本不匹配？）", t);
            return original;
        }
    }

    private static Object invoke(MethodHandle handle, Object... args) {
        try {
            return handle.invokeWithArguments(args);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }
}
