package com.emergencyshelter.mixin;

import com.emergencyshelter.salvage.EntitySalvage;
import com.emergencyshelter.world.EntityOverflow;
import com.emergencyshelter.world.HangGuard;
import com.emergencyshelter.world.WorldGuard;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(EntityType.class)
public abstract class EntityTypeMixin {
    /** 未知实体 → 占位实体；占位实体的模组已回来 → 还原。 */
    @ModifyReturnValue(method = "create(Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/world/level/Level;)Ljava/util/Optional;", at = @At("RETURN"))
    private static Optional<Entity> emergencyshelter$create(Optional<Entity> result, CompoundTag tag, Level level) {
        return EntitySalvage.afterCreate(tag, level, result);
    }

    /** 实体类型存在、但读取它的数据时出错（原版会崩溃）：转为占位实体保存。 */
    @WrapMethod(method = "create(Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/world/level/Level;)Ljava/util/Optional;")
    private static Optional<Entity> emergencyshelter(CompoundTag tag, Level level, Operation<Optional<Entity>> original) {
        if (EntitySalvage.loadingWorld() && WorldGuard.enabled(s -> s.hangGuard)) {
            // 上次运行时卡住、导致游戏被关闭的实体：转为占位实体，不再运行
            String hang = HangGuard.hangReason(tag);
            if (hang != null) {
                try {
                    return EntitySalvage.onLoadFailure(tag, level, new IllegalStateException(hang), "ENTITY_HANG");
                } catch (Throwable ignored) {
                    // 无法创建占位实体：按原样加载
                }
            }
        }
        try {
            return original.call(tag, level);
        } catch (Throwable t) {
            return EntitySalvage.onLoadFailure(tag, level, t);
        }
    }

    /** 区块里同一种实体多得离谱（实体爆炸）：超出上限的部分移进隔离区，不放进世界。 */
    @WrapMethod(method = "loadEntitiesRecursive")
    private static Stream<Entity> emergencyshelter$limit(List<? extends Tag> tags, Level level, Operation<Stream<Entity>> original) {
        return original.call(EntityOverflow.limit(tags, level), level);
    }

    /** 只有从存档加载区块实体时才创建占位（刷怪笼、/summon 等保持原版行为）。 */
    @ModifyReturnValue(method = "loadEntitiesRecursive", at = @At("RETURN"))
    private static Stream<Entity> emergencyshelter$markWorldLoading(Stream<Entity> result, List<? extends Tag> tags, Level level) {
        return EntitySalvage.markWorldLoading(result);
    }
}
