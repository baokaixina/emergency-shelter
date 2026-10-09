package com.emergencyshelter.mixin.guard;

import java.util.List;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ClientboundLevelChunkPacketData.class)
public interface ChunkPacketDataAccessor {
    @Accessor("blockEntitiesData")
    List<Object> emergencyshelter$blockEntities();
}
