package com.emergencyshelter.world;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.neoforged.fml.ModList;
import net.neoforged.neoforgespi.language.IModFileInfo;
import org.jetbrains.annotations.Nullable;

/** 根据堆栈找出"是哪个模组的代码"。 */
public final class Culprits {
    /** 这些模块出现在堆栈里不代表它们有错。 */
    private static final Set<String> NEVER_BLAME = Set.of("minecraft", "neoforge", "emergencyshelter");
    /** KubeJS（server_scripts:recipes.js#12、server_scripts/recipes.js:12）和 CraftTweaker（recipes.zs:12）的脚本位置。 */
    private static final Pattern SCRIPT_REF = Pattern.compile(
            "((?:startup|server|client)_scripts[:/][\\w./\\-]+?\\.js|[\\w./\\-]+?\\.zs)(?:[:#](\\d+))?");
    @Nullable
    private static volatile Map<String, String> moduleToMod;

    private Culprits() {
    }

    /** 从最深的 cause 开始找第一个属于模组的栈帧，返回模组 id。 */
    @Nullable
    public static String modOf(Throwable error) {
        return com.emergencyshelter.Defense.quietly("modOf", () -> modOfUnsafe(error), null);
    }

    @Nullable
    private static String modOfUnsafe(Throwable error) {
        List<Throwable> chain = new ArrayList<>();
        for (Throwable t = error; t != null && chain.size() < 16 && !chain.contains(t); t = t.getCause()) {
            chain.add(t);
        }
        for (int i = chain.size() - 1; i >= 0; i--) {
            String mod = modOf(chain.get(i).getStackTrace());
            if (mod != null) {
                return mod;
            }
        }
        return null;
    }

    @Nullable
    public static String modOf(StackTraceElement[] frames) {
        for (StackTraceElement frame : frames) {
            String mod = modOfModule(frame.getModuleName());
            if (mod != null) {
                return mod;
            }
        }
        return null;
    }

    @Nullable
    public static String modOfClass(Class<?> type) {
        return com.emergencyshelter.Defense.quietly("modOfClass", () -> {
            Module module = type.getModule();
            return module == null ? null : modOfModule(module.getName());
        }, null);
    }

    @Nullable
    private static String modOfModule(@Nullable String module) {
        if (module == null) {
            return null;
        }
        Map<String, String> map = moduleToMod;
        if (map == null) {
            map = new HashMap<>();
            try {
                for (IModFileInfo info : ModList.get().getModFiles()) {
                    if (!info.getMods().isEmpty()) {
                        map.put(info.moduleName(), info.getMods().getFirst().getModId());
                    }
                }
            } catch (Throwable ignored) {
                return null;
            }
            moduleToMod = map;
        }
        String mod = map.get(module);
        return mod == null || NEVER_BLAME.contains(mod) || mod.startsWith("emergencyshelter") ? null : mod;
    }

    /** 模组显示名（找不到就用 id）。 */
    public static String displayName(@Nullable String modId) {
        if (modId == null) {
            return "?";
        }
        try {
            return ModList.get().getModContainerById(modId).map(c -> c.getModInfo().getDisplayName() + " (" + modId + ")").orElse(modId);
        } catch (Throwable t) {
            return modId;
        }
    }

    /** 从异常信息和堆栈里找出出错的脚本位置（KubeJS / CraftTweaker），找不到返回空字符串。 */
    public static String scriptRefs(Throwable error) {
        return com.emergencyshelter.Defense.quietly("scriptRefs", () -> scriptRefsUnsafe(error), "");
    }

    private static String scriptRefsUnsafe(Throwable error) {
        StringBuilder text = new StringBuilder();
        for (Throwable t = error; t != null && text.length() < 20000; t = t.getCause() == t ? null : t.getCause()) {
            text.append(t.getMessage()).append('\n');
            for (StackTraceElement frame : t.getStackTrace()) {
                if (frame.getFileName() != null && (frame.getFileName().endsWith(".js") || frame.getFileName().endsWith(".zs"))) {
                    text.append(frame.getFileName()).append(':').append(frame.getLineNumber()).append('\n');
                }
            }
        }
        return scriptRefs(text.toString());
    }

    public static String scriptRefs(String text) {
        Set<String> refs = new LinkedHashSet<>();
        Matcher m = SCRIPT_REF.matcher(text);
        while (m.find() && refs.size() < 5) {
            refs.add(m.group(1).replace(':', '/') + (m.group(2) == null ? "" : " 第 " + m.group(2) + " 行"));
        }
        return String.join("、", refs);
    }

    /** 堆栈的前几行，用于日志。 */
    public static String brief(StackTraceElement[] frames, int max) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(max, frames.length); i++) {
            sb.append("\n    at ").append(frames[i]);
        }
        return sb.toString();
    }
}
