package com.emergencyshelter.client;

import com.emergencyshelter.registry.ShelterRegistries;
import com.emergencyshelter.salvage.PlaceholderEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/** 占位实体显示为一个缓慢旋转的问号方块，头顶显示原实体 id。 */
public final class PlaceholderEntityRenderer extends EntityRenderer<PlaceholderEntity> {
    private final ItemRenderer itemRenderer;
    private ItemStack display;

    public PlaceholderEntityRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.itemRenderer = context.getItemRenderer();
    }

    @Override
    public void render(PlaceholderEntity entity, float entityYaw, float partialTick, PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
        if (display == null) {
            display = new ItemStack(ShelterRegistries.PLACEHOLDER.get());
        }
        float time = entity.tickCount + partialTick + entity.getId() * 7;
        poseStack.pushPose();
        poseStack.translate(0.0, 0.3 + Mth.sin(time / 10.0F) * 0.05, 0.0);
        poseStack.mulPose(Axis.YP.rotationDegrees(time * 2.0F));
        poseStack.scale(0.9F, 0.9F, 0.9F);
        itemRenderer.renderStatic(display, ItemDisplayContext.GROUND, packedLight, OverlayTexture.NO_OVERLAY, poseStack, buffer,
                entity.level(), entity.getId());
        poseStack.popPose();
        super.render(entity, entityYaw, partialTick, poseStack, buffer, packedLight);
    }

    @Override
    public ResourceLocation getTextureLocation(PlaceholderEntity entity) {
        return TextureAtlas.LOCATION_BLOCKS;
    }
}
