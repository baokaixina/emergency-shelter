package com.emergencyshelter.mixin.guard;

import com.emergencyshelter.world.HangGuard;
import com.emergencyshelter.world.TickGuard;
import com.emergencyshelter.world.WorldGuard;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.function.Consumer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** 实体运行时出错：转为占位实体保存，而不是让整个游戏崩溃；卡住时记下是哪个实体。 */
@Mixin(Level.class)
public abstract class LevelMixin {
    @WrapOperation(method = "guardEntityTick", at = @At(value = "INVOKE", target = "Ljava/util/function/Consumer;accept(Ljava/lang/Object;)V"))
    private void emergencyshelter$guardEntityTick(Consumer<Object> consumer, Object entity, Operation<Void> original) {
        Level level = (Level) (Object) this;
        HangGuard.Slot slot = level.isClientSide || entity instanceof Player ? null : HangGuard.begin(HangGuard.ENTITY, entity, level, 0L);
        try {
            original.call(consumer, entity);
        } catch (Throwable t) {
            if (!(entity instanceof Entity e) || !TickGuard.onEntityTickFailure(level, e, t)) {
                throw WorldGuard.sneakyThrow(t);
            }
        } finally {
            HangGuard.end(slot);
        }
    }
}
