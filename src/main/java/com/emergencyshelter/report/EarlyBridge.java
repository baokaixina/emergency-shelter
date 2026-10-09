package com.emergencyshelter.report;

import com.emergencyshelter.EmergencyShelter;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Method;
import java.util.List;
import org.jetbrains.annotations.Nullable;

/**
 * 调用 early 部分（不同的模块层，只能通过反射）。参数和返回值都是字符串，开发环境里没有 early 部分时返回 null。
 */
public final class EarlyBridge {
    private static final Gson GSON = new Gson();
    private static Method call;
    private static boolean resolved;

    private EarlyBridge() {
    }

    @Nullable
    private static synchronized String call(String command, String argument) {
        if (!resolved) {
            resolved = true;
            try {
                Class<?> bridge = Class.forName("com.emergencyshelter.early.Bridge", true, EarlyBridge.class.getClassLoader());
                call = bridge.getMethod("call", String.class, String.class);
            } catch (Throwable t) {
                EmergencyShelter.LOGGER.info("[紧急避险] 没有找到启动前检查部分（开发环境下属于正常情况）");
            }
        }
        if (call == null) {
            return null;
        }
        try {
            return (String) call.invoke(null, command, argument);
        } catch (Throwable t) {
            EmergencyShelter.LOGGER.warn("[紧急避险] 调用启动前检查部分失败：{}", command, t);
            return null;
        }
    }

    /** 加载完成：停止计时，拿到与以往的比较结果；没有明显变慢的话，把当前配置记为"上次正常"。 */
    @Nullable
    public static ShelterReport.StartupSummary loadingFinished() {
        String json = call("loadingFinished", "");
        if (json == null) {
            return null;
        }
        try {
            return GSON.fromJson(json, ShelterReport.StartupSummary.class);
        } catch (Exception e) {
            return null;
        }
    }

    /** 把某个模组的配置恢复为上次正常启动时的版本。返回被恢复的文件；无法调用时返回 null。 */
    @Nullable
    public static List<ShelterReport.ConfigChange> restoreConfigs(String modId) {
        String json = call("restoreConfigs", modId);
        if (json == null) {
            return null;
        }
        try {
            return GSON.fromJson(json, new TypeToken<List<ShelterReport.ConfigChange>>() {}.getType());
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean available() {
        call("", "");
        return call != null;
    }
}
