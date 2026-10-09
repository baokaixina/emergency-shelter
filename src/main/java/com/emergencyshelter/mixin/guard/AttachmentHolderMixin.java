package com.emergencyshelter.mixin.guard;

import com.emergencyshelter.world.AttachmentStash;
import com.emergencyshelter.world.WorldGuard;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.neoforged.neoforge.attachment.AttachmentHolder;
import net.neoforged.neoforge.attachment.IAttachmentHolder;
import net.neoforged.neoforge.attachment.IAttachmentSerializer;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 不认识的、读取出错的附加数据原样保留，写出时放回去（NeoForge 会直接丢掉它们）。 */
@Mixin(AttachmentHolder.class)
public abstract class AttachmentHolderMixin implements AttachmentStash.Holder {
    @Unique
    @Nullable
    private CompoundTag emergencyshelter$stash;

    @Override
    @Nullable
    public CompoundTag emergencyshelter$getStash() {
        return emergencyshelter$stash;
    }

    @Override
    public void emergencyshelter$setStash(@Nullable CompoundTag stash) {
        this.emergencyshelter$stash = stash;
    }

    @Inject(method = "deserializeAttachments", at = @At("HEAD"))
    private void emergencyshelter$beforeDeserialize(HolderLookup.Provider provider, CompoundTag tag, CallbackInfo ci) {
        AttachmentStash.beforeDeserialize(this, tag);
    }

    @WrapOperation(method = "deserializeAttachments", at = @At(value = "INVOKE",
            target = "Lnet/neoforged/neoforge/attachment/IAttachmentSerializer;read(Lnet/neoforged/neoforge/attachment/IAttachmentHolder;Lnet/minecraft/nbt/Tag;Lnet/minecraft/core/HolderLookup$Provider;)Ljava/lang/Object;"))
    private Object emergencyshelter$guardRead(IAttachmentSerializer<Tag, ?> serializer, IAttachmentHolder holder, Tag data,
                                              HolderLookup.Provider provider, Operation<Object> original, @Local String key) {
        try {
            return original.call(serializer, holder, data, provider);
        } catch (Throwable t) {
            AttachmentStash.failed(this, key, data, t);
            throw WorldGuard.sneakyThrow(t);
        }
    }

    @ModifyReturnValue(method = "serializeAttachments", at = @At("RETURN"))
    private CompoundTag emergencyshelter$afterSerialize(CompoundTag result) {
        return AttachmentStash.merge(this, result);
    }
}
