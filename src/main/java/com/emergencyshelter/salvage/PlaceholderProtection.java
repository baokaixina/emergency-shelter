package com.emergencyshelter.salvage;

import com.emergencyshelter.EmergencyShelter;
import com.emergencyshelter.registry.ShelterRegistries;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockEvent;

/** 防止误拆占位方块（创造模式会瞬间破坏方块，所以同样要求潜行）。 */
@EventBusSubscriber(modid = EmergencyShelter.MODID)
public final class PlaceholderProtection {
    private PlaceholderProtection() {
    }

    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (event.getState().is(ShelterRegistries.PLACEHOLDER_BLOCK.get()) && !event.getPlayer().isShiftKeyDown()) {
            event.setCanceled(true);
            event.getPlayer().displayClientMessage(Component.translatable("block.emergencyshelter.placeholder_block.protected")
                    .withStyle(ChatFormatting.YELLOW), true);
        }
    }
}
