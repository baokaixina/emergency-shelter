package com.emergencyshelter.early;

import cpw.mods.modlauncher.api.IEnvironment;
import cpw.mods.modlauncher.api.ITransformationService;
import cpw.mods.modlauncher.api.ITransformer;
import java.util.List;
import java.util.Set;

/**
 * 只用来获得一个"NeoForge 依赖检查完成之后"的时机（ModLauncher 在扫描完成后才调用 transformers()）。
 * 不注册任何字节码转换。
 */
public final class ShelterService implements ITransformationService {
    @Override
    public String name() {
        return "emergencyshelter";
    }

    @Override
    public void initialize(IEnvironment environment) {
    }

    @Override
    public void onLoad(IEnvironment env, Set<String> otherServices) {
    }

    @Override
    public List<? extends ITransformer<?>> transformers() {
        try {
            LaunchMonitor.afterScan();
        } catch (Throwable t) {
            EarlyLog.LOG.error("[紧急避险] 检查加载结果时出错", t);
        }
        return List.of();
    }
}
