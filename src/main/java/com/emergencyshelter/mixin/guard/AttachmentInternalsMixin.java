package com.emergencyshelter.mixin.guard;

import com.emergencyshelter.world.AttachmentStash;
import java.util.function.Predicate;
import net.minecraft.core.HolderLookup;
import net.neoforged.neoforge.attachment.AttachmentHolder;
import net.neoforged.neoforge.attachment.AttachmentInternals;
import net.neoforged.neoforge.attachment.AttachmentType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 复制附加数据时（区块升级、玩家重生或换维度），暂存的数据也一起复制。 */
@Mixin(AttachmentInternals.class)
public abstract class AttachmentInternalsMixin {
    @Inject(method = "copyAttachments", at = @At("TAIL"))
    private static void emergencyshelter$copyStash(HolderLookup.Provider provider, AttachmentHolder from, AttachmentHolder to,
                                                   Predicate<AttachmentType<?>> filter, CallbackInfo ci) {
        AttachmentStash.copy(from, to);
    }
}
