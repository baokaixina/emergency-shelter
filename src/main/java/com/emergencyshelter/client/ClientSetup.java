package com.emergencyshelter.client;

import com.emergencyshelter.EmergencyShelter;
import com.emergencyshelter.registry.ShelterRegistries;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

@EventBusSubscriber(modid = EmergencyShelter.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class ClientSetup {
    private ClientSetup() {
    }

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        // 事件处理出错不能让游戏崩溃
        com.emergencyshelter.Defense.quietly("ClientSetup.registerRenderers", () -> registerRenderersUnsafe(event));
    }

    private static void registerRenderersUnsafe(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ShelterRegistries.PLACEHOLDER_ENTITY.get(), PlaceholderEntityRenderer::new);
    }
}
