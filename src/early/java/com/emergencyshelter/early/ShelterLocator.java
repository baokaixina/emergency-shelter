package com.emergencyshelter.early;

import net.neoforged.neoforgespi.ILaunchContext;
import net.neoforged.neoforgespi.locating.IDiscoveryPipeline;
import net.neoforged.neoforgespi.locating.IModFileCandidateLocator;

/**
 * 优先级高于 NeoForge 自带的 mods 文件夹扫描器（0），因此总是先运行。
 */
public final class ShelterLocator implements IModFileCandidateLocator {
    @Override
    public void findCandidates(ILaunchContext context, IDiscoveryPipeline pipeline) {
        EarlyShelter.locate(context, pipeline);
    }

    @Override
    public int getPriority() {
        return 500;
    }

    @Override
    public String toString() {
        return "emergencyshelter";
    }
}
