package com.emergencyshelter.mixin.guard.client;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.Deque;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(PoseStack.class)
public interface PoseStackAccessor {
    @Accessor("poseStack")
    Deque<PoseStack.Pose> emergencyshelter$poses();
}
