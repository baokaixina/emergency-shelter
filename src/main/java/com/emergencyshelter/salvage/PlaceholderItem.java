package com.emergencyshelter.salvage;

import com.emergencyshelter.registry.ShelterRegistries;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

/**
 * 占位物品：原物品所属的模组不在了，这里完整保存着原物品的数据。模组装回后读取存档时自动变回原物品。
 */
public final class PlaceholderItem extends Item {
    public PlaceholderItem(Properties properties) {
        super(properties);
    }

    public static String originalId(ItemStack stack) {
        CompoundTag original = stack.get(ShelterRegistries.ORIGINAL_ITEM.get());
        if (original == null) {
            return "?";
        }
        String id = original.getString("id");
        return id.isEmpty() ? "?" : id;
    }

    @Override
    public Component getName(ItemStack stack) {
        return Component.translatable("item.emergencyshelter.placeholder.named", originalId(stack));
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        String id = originalId(stack);
        int colon = id.indexOf(':');
        String modId = colon > 0 ? id.substring(0, colon) : id;
        tooltip.add(Component.translatable("item.emergencyshelter.placeholder.tooltip.original", id).withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.emergencyshelter.placeholder.tooltip.mod", modId).withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.emergencyshelter.placeholder.tooltip.restore").withStyle(ChatFormatting.GREEN));
    }

    /** 掉在地上也不会消失，避免数据丢失。 */
    @Override
    public int getEntityLifespan(ItemStack itemStack, Level level) {
        return Integer.MAX_VALUE;
    }
}
