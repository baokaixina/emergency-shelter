package com.emergencyshelter.client;

import com.emergencyshelter.EmergencyShelter;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;

/** 只在客户端执行的初始化。 */
@Mod(value = EmergencyShelter.MODID, dist = Dist.CLIENT)
public final class EmergencyShelterClient {
    public EmergencyShelterClient(IEventBus modBus, ModContainer container) {
        ClientSession.init();
    }
}
