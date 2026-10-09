package com.emergencyshelter.salvage;

import com.emergencyshelter.registry.ShelterRegistries;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import org.jetbrains.annotations.Nullable;

/** 物品的占位、暂存与还原。 */
public final class ItemSalvage {
    private static final String ENCHANTMENTS = "minecraft:enchantments";
    private static final String STORED_ENCHANTMENTS = "minecraft:stored_enchantments";

    private ItemSalvage() {
    }

    /** 本模组的物品和组件注册完成之后才介入。 */
    static boolean ready() {
        return ShelterRegistries.PLACEHOLDER.isBound() && ShelterRegistries.ORIGINAL_ITEM.isBound()
                && ShelterRegistries.STASHED_COMPONENTS.isBound();
    }

    public static boolean isPlaceholder(ItemStack stack) {
        return !stack.isEmpty() && stack.is(ShelterRegistries.PLACEHOLDER.get());
    }

    // ------------------------------------------------------------------ 读取失败：转为占位 / 暂存

    @Nullable
    static <T> ItemStack salvage(CompoundTag raw, DynamicOps<T> ops, boolean single) {
        String idString = raw.getString("id");
        if (idString.isEmpty()) {
            return null; // 不是物品数据，按原版处理
        }
        int count = single ? 1 : (raw.contains("count", Tag.TAG_ANY_NUMERIC) ? raw.getInt("count") : 1);
        if (count <= 0) {
            return null;
        }
        ResourceLocation id = ResourceLocation.tryParse(idString);
        Optional<Holder.Reference<Item>> holder = id == null ? Optional.empty()
                : BuiltInRegistries.ITEM.getHolder(ResourceKey.create(Registries.ITEM, id));

        if (holder.isPresent()) {
            if (holder.get().value() == Items.AIR) {
                return null;
            }
            // 物品还在，只是部分组件读不出来：保留物品，把读不出来的部分暂存
            CompoundTag components = raw.getCompound("components");
            DataComponentPatch.Builder builder = DataComponentPatch.builder();
            CompoundTag stash = new CompoundTag();
            for (String key : components.getAllKeys()) {
                Tag value = components.get(key);
                if (value == null || decodeInto(builder, key, value, ops)) {
                    continue;
                }
                if (isEnchantmentKey(key) && value instanceof CompoundTag enchantments) {
                    splitEnchantments(builder, stash, key, enchantments, ops);
                } else {
                    stash.put(key, value.copy());
                }
            }
            ItemStack stack = new ItemStack(holder.get(), Math.min(count, 99), builder.build());
            if (!stash.isEmpty()) {
                CompoundTag existing = stack.get(ShelterRegistries.STASHED_COMPONENTS.get());
                if (existing != null) {
                    existing = existing.copy();
                    existing.merge(stash);
                    stash = existing;
                }
                stack.set(ShelterRegistries.STASHED_COMPONENTS.get(), stash);
                for (String key : stash.getAllKeys()) {
                    SalvageStats.stripped(idString, key);
                }
            }
            return stack;
        }

        // 物品本身不存在：整个转为占位物品（数量与原物品一致：原版物品数量上限是 99，占位物品也能堆到 99，还原时不会少）
        ItemStack placeholder = new ItemStack(ShelterRegistries.PLACEHOLDER.get(), Math.min(count, 99));
        placeholder.set(ShelterRegistries.ORIGINAL_ITEM.get(), raw.copy());
        SalvageStats.placeholder("物品", idString);
        return placeholder;
    }

    // ------------------------------------------------------------------ 读取成功：检查能否还原

    static <T> ItemStack afterDecode(ItemStack stack, DynamicOps<T> ops, SalvagingItemCodec codec) {
        if (stack.isEmpty()) {
            return stack;
        }
        if (stack.is(ShelterRegistries.PLACEHOLDER.get())) {
            return tryRestorePlaceholder(stack, ops, codec);
        }
        if (stack.has(ShelterRegistries.STASHED_COMPONENTS.get())) {
            tryRestoreStash(stack, ops);
        }
        return stack;
    }

    @SuppressWarnings("unchecked")
    private static <T> ItemStack tryRestorePlaceholder(ItemStack placeholder, DynamicOps<T> ops, SalvagingItemCodec codec) {
        CompoundTag original = placeholder.get(ShelterRegistries.ORIGINAL_ITEM.get());
        if (original == null) {
            return placeholder;
        }
        String id = original.getString("id");
        ResourceLocation location = ResourceLocation.tryParse(id);
        if (location == null || !BuiltInRegistries.ITEM.containsKey(location)) {
            return placeholder; // 模组还没回来
        }
        DataResult<Pair<ItemStack, T>> result = codec.decode(ops, (T) original.copy());
        if (result.error().isPresent() || result.result().isEmpty()) {
            return placeholder;
        }
        ItemStack restored = result.result().get().getFirst();
        if (restored.isEmpty() || restored.is(ShelterRegistries.PLACEHOLDER.get())) {
            return placeholder;
        }
        restored.setCount(placeholder.getCount());
        SalvageStats.restored("物品", id);
        return restored;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T> void tryRestoreStash(ItemStack stack, DynamicOps<T> ops) {
        CompoundTag stash = stack.get(ShelterRegistries.STASHED_COMPONENTS.get());
        if (stash == null) {
            return;
        }
        stash = stash.copy();
        boolean changed = false;
        for (String key : new ArrayList<>(stash.getAllKeys())) {
            Tag value = stash.get(key);
            if (value == null) {
                continue;
            }
            if (isEnchantmentKey(key) && value instanceof CompoundTag enchantmentsTag) {
                DataComponentPatch.Builder probe = DataComponentPatch.builder();
                CompoundTag stillMissing = new CompoundTag();
                splitEnchantments(probe, stillMissing, key, enchantmentsTag, ops);
                DataComponentPatch patch = probe.build();
                for (Map.Entry<DataComponentType<?>, Optional<?>> e : patch.entrySet()) {
                    if (e.getValue().isPresent() && e.getValue().get() instanceof ItemEnchantments recovered && !recovered.isEmpty()) {
                        DataComponentType<ItemEnchantments> type = (DataComponentType<ItemEnchantments>) e.getKey();
                        ItemEnchantments.Mutable merged = new ItemEnchantments.Mutable(stack.getOrDefault(type, ItemEnchantments.EMPTY));
                        for (Object2IntMap.Entry<Holder<Enchantment>> level : recovered.entrySet()) {
                            merged.upgrade(level.getKey(), level.getIntValue());
                        }
                        stack.set(type, merged.toImmutable());
                        changed = true;
                    }
                }
                if (stillMissing.contains(key)) {
                    stash.put(key, stillMissing.get(key));
                } else {
                    stash.remove(key);
                    changed = true;
                }
                continue;
            }
            DataComponentPatch.Builder builder = DataComponentPatch.builder();
            if (decodeInto(builder, key, value, ops)) {
                stack.applyComponents(builder.build());
                stash.remove(key);
                changed = true;
            }
        }
        if (changed) {
            if (stash.isEmpty()) {
                stack.remove(ShelterRegistries.STASHED_COMPONENTS.get());
            } else {
                stack.set(ShelterRegistries.STASHED_COMPONENTS.get(), stash);
            }
            SalvageStats.restored("物品数据", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        }
    }

    // ------------------------------------------------------------------ 工具

    /** 单独解析一个组件条目，成功则放进 builder。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T> boolean decodeInto(DataComponentPatch.Builder builder, String key, Tag value, DynamicOps<T> ops) {
        CompoundTag single = new CompoundTag();
        single.put(key, value.copy());
        DataResult<DataComponentPatch> result = DataComponentPatch.CODEC.parse(ops, (T) single);
        if (result.error().isPresent() || result.result().isEmpty()) {
            return false;
        }
        for (Map.Entry<DataComponentType<?>, Optional<?>> e : result.result().get().entrySet()) {
            if (e.getValue().isPresent()) {
                builder.set((DataComponentType) e.getKey(), e.getValue().get());
            } else {
                builder.remove(e.getKey());
            }
        }
        return true;
    }

    private static boolean isEnchantmentKey(String key) {
        return key.equals(ENCHANTMENTS) || key.equals(STORED_ENCHANTMENTS);
    }

    /** 附魔逐条检查：认识的保留在物品上，来自缺失模组的单独暂存。 */
    private static <T> void splitEnchantments(DataComponentPatch.Builder builder, CompoundTag stash, String key,
                                              CompoundTag value, DynamicOps<T> ops) {
        boolean full = value.contains("levels", Tag.TAG_COMPOUND);
        CompoundTag levels = full ? value.getCompound("levels") : value;
        CompoundTag known = new CompoundTag();
        CompoundTag unknown = new CompoundTag();
        for (String enchantment : levels.getAllKeys()) {
            Tag level = levels.get(enchantment);
            CompoundTag probeLevels = new CompoundTag();
            probeLevels.put(enchantment, level.copy());
            if (decodeInto(DataComponentPatch.builder(), key, probeLevels, ops)) {
                known.put(enchantment, level.copy());
            } else {
                unknown.put(enchantment, level.copy());
            }
        }
        if (!known.isEmpty()) {
            CompoundTag knownValue = full ? value.copy() : known;
            if (full) {
                knownValue.put("levels", known);
            }
            if (!decodeInto(builder, key, knownValue, ops)) {
                // 理论上不会发生：整体暂存，不丢数据
                stash.put(key, value.copy());
                return;
            }
        }
        if (!unknown.isEmpty()) {
            CompoundTag unknownValue = full ? value.copy() : unknown;
            if (full) {
                unknownValue.put("levels", unknown);
            }
            stash.put(key, unknownValue);
        }
    }
}
