package com.emergencyshelter.salvage;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.PushReaction;

/**
 * 占位实体：原生物/实体所属的模组不在了，或者它读取、运行时出错被隔离，这里保存着它的全部数据。不会动、不会受伤，也不会被清除。
 * 模组回来（或模组有更新、手动重试）后，下次加载这个区块时自动变回原实体。创造模式玩家潜行攻击可以删除它。
 */
public final class PlaceholderEntity extends Entity {
    private static final EntityDataAccessor<String> ORIGINAL_ID = SynchedEntityData.defineId(PlaceholderEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Boolean> FAULT = SynchedEntityData.defineId(PlaceholderEntity.class, EntityDataSerializers.BOOLEAN);
    /** 原始数据里的这个字段表示：原实体的模组还在，但它读取或运行时出错，被隔离在这里。 */
    public static final String FAULT_KEY = "emergencyshelter:fault";

    private CompoundTag original = new CompoundTag();

    public PlaceholderEntity(EntityType<?> type, Level level) {
        super(type, level);
        this.noPhysics = true;
        this.setNoGravity(true);
        this.setInvulnerable(true);
    }

    public CompoundTag getOriginal() {
        return original;
    }

    public void setOriginal(CompoundTag original) {
        this.original = original;
        this.entityData.set(ORIGINAL_ID, original.getString("id"));
        this.entityData.set(FAULT, original.contains(FAULT_KEY, Tag.TAG_COMPOUND));
    }

    public boolean isFault() {
        return entityData.get(FAULT);
    }

    public CompoundTag getFault() {
        return original.getCompound(FAULT_KEY);
    }

    public String getOriginalId() {
        return entityData.get(ORIGINAL_ID);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(ORIGINAL_ID, "");
        builder.define(FAULT, false);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        if (tag.contains("Original", Tag.TAG_COMPOUND)) {
            setOriginal(tag.getCompound("Original"));
        }
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.put("Original", original.copy());
    }

    @Override
    public void tick() {
        // 完全静止：不受重力、不移动、不燃烧
    }

    @Override
    public boolean isPickable() {
        return true;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public PushReaction getPistonPushReaction() {
        return PushReaction.IGNORE;
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (!level().isClientSide && source.getEntity() instanceof Player player && player.isCreative() && player.isShiftKeyDown()) {
            discard();
            return true;
        }
        return false;
    }

    /** /kill @e 等命令走这里而不是 hurt：占位实体里保存着原实体的全部数据，不能被顺带清掉。 */
    @Override
    public void kill() {
    }

    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        if (!level().isClientSide && hand == InteractionHand.MAIN_HAND) {
            if (isFault()) {
                player.sendSystemMessage(Component.translatable("entity.emergencyshelter.placeholder_entity.fault_info", getOriginalId(),
                        getFault().getString("error")).withStyle(ChatFormatting.GOLD));
            } else {
                player.sendSystemMessage(Component.translatable("entity.emergencyshelter.placeholder_entity.info", getOriginalId())
                        .withStyle(ChatFormatting.GOLD));
            }
        }
        return InteractionResult.sidedSuccess(level().isClientSide);
    }

    @Override
    public boolean shouldShowName() {
        return true;
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable(isFault() ? "entity.emergencyshelter.placeholder_entity.fault_named"
                : "entity.emergencyshelter.placeholder_entity.named", getOriginalId());
    }
}
