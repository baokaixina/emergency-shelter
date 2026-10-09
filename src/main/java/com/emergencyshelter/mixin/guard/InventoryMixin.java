package com.emergencyshelter.mixin.guard;

import com.emergencyshelter.world.TickGuard;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** 背包里的物品运行时出错：移进避险箱，而不是让玩家一进存档就崩溃。 */
@Mixin(Inventory.class)
public abstract class InventoryMixin {
    @WrapOperation(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/item/ItemStack;inventoryTick(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/Entity;IZ)V"))
    private void emergencyshelter$guardItemTick(ItemStack stack, Level level, Entity entity, int slot, boolean selected, Operation<Void> original) {
        try {
            original.call(stack, level, entity, slot, selected);
        } catch (Throwable t) {
            TickGuard.onItemTickFailure((Inventory) (Object) this, stack, t);
        }
    }
}
