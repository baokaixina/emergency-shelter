package com.emergencyshelter.salvage;

import com.emergencyshelter.EmergencyShelter;
import com.emergencyshelter.registry.ShelterRegistries;
import java.util.Optional;
import java.util.Spliterator;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

/**
 * 实体的占位与还原。只在"从存档加载区块里的实体"时创建占位，
 * 刷怪笼、/summon 等其它途径遇到未知实体时保持原版行为（什么都不生成）。
 */
public final class EntitySalvage {
    private static final ThreadLocal<Boolean> LOADING_WORLD = ThreadLocal.withInitial(() -> false);

    private EntitySalvage() {
    }

    /** 给加载存档实体用的流打上标记：只有在消费这个流（真正创建实体）时标记才生效。 */
    public static Stream<Entity> markWorldLoading(Stream<Entity> stream) {
        Spliterator<Entity> inner = stream.spliterator();
        Spliterator<Entity> marked = new Spliterator<>() {
            @Override
            public boolean tryAdvance(Consumer<? super Entity> action) {
                boolean previous = LOADING_WORLD.get();
                LOADING_WORLD.set(true);
                try {
                    return inner.tryAdvance(action);
                } finally {
                    LOADING_WORLD.set(previous);
                }
            }

            @Override
            public Spliterator<Entity> trySplit() {
                return null;
            }

            @Override
            public long estimateSize() {
                return inner.estimateSize();
            }

            @Override
            public int characteristics() {
                return inner.characteristics();
            }
        };
        return StreamSupport.stream(marked, false).onClose(stream::close);
    }

    public static Optional<Entity> afterCreate(CompoundTag tag, Level level, Optional<Entity> result) {
        if (!ShelterRegistries.PLACEHOLDER_ENTITY.isBound()) {
            return result;
        }
        try {
            if (result.isEmpty()) {
                return LOADING_WORLD.get() ? createPlaceholder(tag, level, result) : result;
            }
            if (result.get() instanceof PlaceholderEntity placeholder) {
                return tryRestore(placeholder, level, result);
            }
        } catch (Throwable t) {
            EmergencyShelter.LOGGER.error("[紧急避险] 处理实体数据时出错，按原版方式处理", t);
        }
        return result;
    }

    private static Optional<Entity> createPlaceholder(CompoundTag tag, Level level, Optional<Entity> result) {
        String id = tag.getString("id");
        ResourceLocation location = ResourceLocation.tryParse(id);
        if (id.isEmpty() || location == null || BuiltInRegistries.ENTITY_TYPE.containsKey(location)) {
            return result; // 类型存在但创建失败（例如实验性功能未开启），保持原版行为
        }
        PlaceholderEntity placeholder = ShelterRegistries.PLACEHOLDER_ENTITY.get().create(level);
        if (placeholder == null) {
            return result;
        }
        CompoundTag original = tag.copy();
        // 乘客会作为独立实体加载并骑在占位实体上，不在这里重复保存
        original.remove("Passengers");
        try {
            // 只读位置、朝向、UUID：原实体的附加数据、模组数据都已经在 original 里，
            // 如果整份读进占位实体，存盘时会在占位实体自己身上再写一遍，数据翻倍
            placeholder.load(baseFields(tag));
        } catch (Throwable t) {
            // 通用字段读不出来也没关系，原始数据另外完整保存
        }
        placeholder.setOriginal(original);
        placeholder.setNoGravity(true);
        placeholder.setInvulnerable(true);
        SalvageStats.placeholder("实体", id);
        return Optional.of(placeholder);
    }

    private static CompoundTag baseFields(CompoundTag tag) {
        CompoundTag base = new CompoundTag();
        for (String key : new String[]{"Pos", "Rotation", "UUID"}) {
            Tag value = tag.get(key);
            if (value != null) {
                base.put(key, value.copy());
            }
        }
        return base;
    }

    private static Optional<Entity> tryRestore(PlaceholderEntity placeholder, Level level, Optional<Entity> result) {
        CompoundTag original = placeholder.getOriginal();
        String id = original.getString("id");
        ResourceLocation location = ResourceLocation.tryParse(id);
        if (location == null || !BuiltInRegistries.ENTITY_TYPE.containsKey(location)) {
            return result;
        }
        boolean fault = placeholder.isFault();
        if (fault && !com.emergencyshelter.world.WorldGuard.shouldRetry(placeholder.getFault())) {
            return result; // 出错隔离的：等模组有变化或手动重试时再试
        }
        CompoundTag attempt = original.copy();
        attempt.remove(PlaceholderEntity.FAULT_KEY);
        Optional<Entity> restored = net.minecraft.world.entity.EntityType.create(attempt, level);
        if (restored.isPresent() && !(restored.get() instanceof PlaceholderEntity)) {
            if (fault) {
                com.emergencyshelter.world.WorldGuard.record("RESTORED", id, null, "之前隔离的实体已恢复");
            } else {
                SalvageStats.restored("实体", id);
            }
            return restored;
        }
        if (restored.isPresent() && restored.get() instanceof PlaceholderEntity again && again.isFault()) {
            return restored; // 仍然出错：用新的隔离记录（记下这次的模组组合，避免每次加载都重试）
        }
        return result;
    }

    /**
     * 实体类型存在、但读取它的数据时出错（原版会直接崩溃）。只在加载存档区块时处理：转为带着原始数据的占位实体。
     */
    public static Optional<Entity> onLoadFailure(CompoundTag tag, Level level, Throwable error) {
        return onLoadFailure(tag, level, error, "ENTITY_LOAD");
    }

    /** 是否正在从存档加载区块里的实体。 */
    public static boolean loadingWorld() {
        return LOADING_WORLD.get();
    }

    /** @param kind 隔离原因，同时也是记录的类型（见语言文件 emergencyshelter.guard.kind.*） */
    public static Optional<Entity> onLoadFailure(CompoundTag tag, Level level, Throwable error, String kind) {
        if (!LOADING_WORLD.get() || !ShelterRegistries.PLACEHOLDER_ENTITY.isBound() || error instanceof OutOfMemoryError
                || !com.emergencyshelter.world.WorldGuard.enabled()) {
            throw com.emergencyshelter.world.WorldGuard.sneakyThrow(error);
        }
        String id = tag.getString("id");
        PlaceholderEntity placeholder = ShelterRegistries.PLACEHOLDER_ENTITY.get().create(level);
        if (placeholder == null) {
            throw com.emergencyshelter.world.WorldGuard.sneakyThrow(error);
        }
        net.minecraft.nbt.ListTag pos = tag.getList("Pos", net.minecraft.nbt.Tag.TAG_DOUBLE);
        net.minecraft.nbt.ListTag rot = tag.getList("Rotation", net.minecraft.nbt.Tag.TAG_FLOAT);
        if (pos.size() == 3) {
            placeholder.moveTo(pos.getDouble(0), pos.getDouble(1), pos.getDouble(2), rot.getFloat(0), rot.getFloat(1));
        }
        if (tag.hasUUID("UUID")) {
            placeholder.setUUID(tag.getUUID("UUID"));
        }
        CompoundTag original = tag.copy();
        original.remove("Passengers"); // 乘客会作为独立实体加载
        original.put(PlaceholderEntity.FAULT_KEY, com.emergencyshelter.world.WorldGuard.faultTag(kind, error));
        placeholder.setOriginal(original);
        placeholder.setNoGravity(true);
        placeholder.setInvulnerable(true);
        String where = pos.size() == 3 ? level.dimension().location() + " " + (int) Math.floor(pos.getDouble(0)) + ", "
                + (int) Math.floor(pos.getDouble(1)) + ", " + (int) Math.floor(pos.getDouble(2)) : null;
        EmergencyShelter.LOGGER.error("[紧急避险] 读取实体 {} 的数据时出错（原版会崩溃），已转为占位实体保存全部数据", id, error);
        com.emergencyshelter.world.WorldGuard.record(kind, id, where, com.emergencyshelter.world.WorldGuard.message(error));
        return Optional.of(placeholder);
    }
}
