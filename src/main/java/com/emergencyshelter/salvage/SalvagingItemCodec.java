package com.emergencyshelter.salvage;

import com.emergencyshelter.EmergencyShelter;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

/**
 * 包在原版 {@code ItemStack.CODEC} 外面：只在读取 NBT（存档数据）时介入。
 * <ul>
 *     <li>原版读取失败（物品或组件来自缺失的模组）时，不再丢弃，而是转为占位物品 / 暂存读不出的组件；</li>
 *     <li>读到占位物品时，如果原模组已经回来，直接还原成原物品。</li>
 * </ul>
 * 读取 JSON（配方、战利品表等）时完全不介入，保持原版行为。
 */
public final class SalvagingItemCodec implements Codec<ItemStack> {
    private final Codec<ItemStack> delegate;
    private final boolean single;

    private SalvagingItemCodec(Codec<ItemStack> delegate, boolean single) {
        this.delegate = delegate;
        this.single = single;
    }

    public static Codec<ItemStack> wrap(Codec<ItemStack> delegate, boolean single) {
        return new SalvagingItemCodec(delegate, single);
    }

    Codec<ItemStack> delegate() {
        return delegate;
    }

    @Override
    public <T> DataResult<T> encode(ItemStack input, DynamicOps<T> ops, T prefix) {
        return delegate.encode(input, ops, prefix);
    }

    @Override
    public <T> DataResult<Pair<ItemStack, T>> decode(DynamicOps<T> ops, T input) {
        DataResult<Pair<ItemStack, T>> result = delegate.decode(ops, input);
        if (!(input instanceof CompoundTag raw) || !(ops.empty() instanceof Tag) || !ItemSalvage.ready()) {
            return result;
        }
        try {
            if (result.error().isEmpty() && result.result().isPresent()) {
                ItemStack stack = result.result().get().getFirst();
                ItemStack fixed = ItemSalvage.afterDecode(stack, ops, this);
                return fixed == stack ? result : DataResult.success(Pair.of(fixed, input));
            }
            ItemStack salvaged = ItemSalvage.salvage(raw, ops, single);
            if (salvaged == null) {
                return result;
            }
            return DataResult.success(Pair.of(salvaged, input));
        } catch (Throwable t) {
            EmergencyShelter.LOGGER.error("[紧急避险] 处理物品数据时出错，按原版方式处理：{}", raw, t);
            return result;
        }
    }

    @Override
    public String toString() {
        return "EmergencyShelter[" + delegate + "]";
    }
}
