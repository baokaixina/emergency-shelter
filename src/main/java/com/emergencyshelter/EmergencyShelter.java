package com.emergencyshelter;

import com.emergencyshelter.registry.ShelterRegistries;
import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

@Mod(EmergencyShelter.MODID)
public final class EmergencyShelter {
    public static final String MODID = "emergencyshelter";
    public static final Logger LOGGER = LogUtils.getLogger();

    public EmergencyShelter(IEventBus modBus, ModContainer container) {
        ShelterRegistries.register(modBus);
    }
}
