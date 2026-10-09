package com.emergencyshelter.mixin.guard;

import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData$BlockEntityInfo")
public interface BlockEntityInfoAccessor {
    @Accessor("tag")
    CompoundTag emergencyshelter$tag();

    @Mutable
    @Accessor("tag")
    void emergencyshelter$setTag(CompoundTag tag);
}
