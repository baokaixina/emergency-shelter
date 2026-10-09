package com.emergencyshelter.client;

import com.emergencyshelter.EmergencyShelter;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.NeoForge;

/** "打开避险箱"按键：默认不绑定（整合包里按键冲突太多），玩家可以在按键设置里自己绑定。 */
public final class ShelterKeys {
    public static final KeyMapping OPEN_BOX = new KeyMapping("key.emergencyshelter.open_box",
            InputConstants.UNKNOWN.getValue(), "key.categories.emergencyshelter");

    private ShelterKeys() {
    }

    @EventBusSubscriber(modid = EmergencyShelter.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        private Registration() {
        }

        @SubscribeEvent
        public static void register(RegisterKeyMappingsEvent event) {
            com.emergencyshelter.Defense.quietly("ShelterKeys.register", () -> {
                event.register(OPEN_BOX);
                NeoForge.EVENT_BUS.addListener(ShelterKeys::onClientTick);
            });
        }
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        com.emergencyshelter.Defense.quietly("ShelterKeys.onClientTick", ShelterKeys::checkKeys);
    }

    private static void checkKeys() {
        Minecraft minecraft = Minecraft.getInstance();
        while (OPEN_BOX.consumeClick()) {
            if (minecraft.player != null && minecraft.screen == null) {
                minecraft.player.connection.sendCommand("emergencyshelter box");
            }
        }
    }
}
