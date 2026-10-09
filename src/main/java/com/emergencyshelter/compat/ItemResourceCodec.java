package com.emergencyshelter.compat;

import com.emergencyshelter.EmergencyShelter;
import com.emergencyshelter.registry.ShelterRegistries;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.MapLike;
import com.mojang.serialization.RecordBuilder;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Stream;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * 给"物品 + 组件"形式的存储资源（例如精致存储 2 的 ItemResource）用的包装：
 * 读不出来时不再丢弃，而是转为占位资源（数量由外层照常保存）；模组回来后自动还原。
 * 实际的占位 / 还原逻辑复用物品编解码器（ItemStack.CODEC 已被紧急避险包装过）。
 */
public final class ItemResourceCodec<R> extends MapCodec<R> {
    private final MapCodec<R> delegate;
    private final String itemField;
    private final String componentsField;
    private final Function<R, Item> itemGetter;
    private final Function<R, DataComponentPatch> componentsGetter;
    private final BiFunction<Item, DataComponentPatch, R> factory;

    public ItemResourceCodec(MapCodec<R> delegate, String itemField, String componentsField, Function<R, Item> itemGetter,
                             Function<R, DataComponentPatch> componentsGetter, BiFunction<Item, DataComponentPatch, R> factory) {
        this.delegate = delegate;
        this.itemField = itemField;
        this.componentsField = componentsField;
        this.itemGetter = itemGetter;
        this.componentsGetter = componentsGetter;
        this.factory = factory;
    }

    @Override
    public <T> Stream<T> keys(DynamicOps<T> ops) {
        return delegate.keys(ops);
    }

    @Override
    public <T> RecordBuilder<T> encode(R input, DynamicOps<T> ops, RecordBuilder<T> prefix) {
        return delegate.encode(input, ops, prefix);
    }

    @Override
    public <T> DataResult<R> decode(DynamicOps<T> ops, MapLike<T> input) {
        DataResult<R> result = delegate.decode(ops, input);
        if (!(ops.empty() instanceof Tag) || !ShelterRegistries.PLACEHOLDER.isBound()) {
            return result;
        }
        try {
            if (result.error().isEmpty() && result.result().isPresent()) {
                R resource = result.result().get();
                Item item = itemGetter.apply(resource);
                DataComponentPatch components = componentsGetter.apply(resource);
                boolean special = item == ShelterRegistries.PLACEHOLDER.get()
                        || components.get(ShelterRegistries.STASHED_COMPONENTS.get()) != null;
                if (!special) {
                    return result;
                }
                // 占位资源：看看原物品能不能还原
                ItemStack stack = new ItemStack(item.builtInRegistryHolder(), 1, components);
                Tag encoded = ItemStack.CODEC.encodeStart(registryOps(ops), stack).result().orElse(null);
                if (encoded == null) {
                    return result;
                }
                ItemStack restored = ItemStack.CODEC.parse(registryOps(ops), encoded).result().orElse(stack);
                if (restored.isEmpty()) {
                    return result;
                }
                return DataResult.success(factory.apply(restored.getItem(), restored.getComponentsPatch()));
            }
            // 读取失败：按物品数据交给紧急避险处理
            Tag raw = ops.convertMap(NbtOps.INSTANCE, ops.createMap(input.entries()));
            if (!(raw instanceof CompoundTag map) || !map.contains(itemField, Tag.TAG_STRING)) {
                return result;
            }
            CompoundTag itemTag = new CompoundTag();
            itemTag.putString("id", map.getString(itemField));
            itemTag.putInt("count", 1);
            if (map.contains(componentsField, Tag.TAG_COMPOUND)) {
                itemTag.put("components", map.getCompound(componentsField));
            }
            ItemStack salvaged = ItemStack.CODEC.parse(registryOps(ops), itemTag).result().orElse(ItemStack.EMPTY);
            if (salvaged.isEmpty()) {
                return result;
            }
            return DataResult.success(factory.apply(salvaged.getItem(), salvaged.getComponentsPatch()));
        } catch (Throwable t) {
            EmergencyShelter.LOGGER.error("[紧急避险] 处理存储资源数据时出错，按原样处理", t);
            return result;
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> DynamicOps<Tag> registryOps(DynamicOps<T> ops) {
        return (DynamicOps<Tag>) ops;
    }

    @Override
    public String toString() {
        return "EmergencyShelter[" + delegate + "]";
    }
}
