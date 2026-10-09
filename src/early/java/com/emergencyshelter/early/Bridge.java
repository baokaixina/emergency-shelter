package com.emergencyshelter.early;

/**
 * 游戏内部分调用 early 部分的入口。两部分在不同的模块层里，游戏内部分通过反射调用这里，
 * 参数和返回值只用字符串（返回 JSON），所以不需要共享任何类。开发环境里没有 early 部分时，游戏内部分会自动跳过。
 */
public final class Bridge {
    private Bridge() {
    }

    public static String call(String command, String argument) {
        try {
            switch (command) {
                case "loadingFinished" -> {
                    // 进入主菜单 / 服务端启动完成：没有明显变慢的话，把这时的配置记为"上次正常"
                    StartupProfiler.Summary summary = StartupProfiler.finish();
                    ShelterSettings settings = ShelterSettings.load();
                    if (settings.enabled && settings.configGuard && (!summary.notable || summary.accepted)) {
                        Thread t = new Thread(ConfigGuard::snapshot, "EmergencyShelter-ConfigSnapshot");
                        t.setDaemon(true);
                        t.setPriority(Thread.MIN_PRIORITY);
                        t.start();
                    }
                    return ShelterFiles.GSON.toJson(summary);
                }
                case "restoreConfigs" -> {
                    ConfigGuard.State state = ConfigGuard.compareNow();
                    LaunchReport scratch = new LaunchReport();
                    ConfigGuard.restoreForMod(state, argument, scratch, "MANUAL", false);
                    return ShelterFiles.GSON.toJson(scratch.configRestored);
                }
                case "changedConfigs" -> {
                    return ShelterFiles.GSON.toJson(ConfigGuard.changedFilesOf(ConfigGuard.compareNow(), argument));
                }
                default -> {
                    return null;
                }
            }
        } catch (Throwable t) {
            EarlyLog.LOG.error("[紧急避险] 处理 {} 时出错", command, t);
            return null;
        }
    }
}
