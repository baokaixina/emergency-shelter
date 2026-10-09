package com.emergencyshelter.early;

import cpw.mods.niofs.union.UnionFileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.neoforged.fml.ModLoader;
import net.neoforged.fml.ModLoadingIssue;
import net.neoforged.fml.i18n.FMLTranslations;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.fml.loading.LoadingModList;
import net.neoforged.neoforgespi.language.IModInfo;
import net.neoforged.neoforgespi.locating.IModFile;
import org.jetbrains.annotations.Nullable;

/**
 * 监视本次启动：如果 NeoForge 仍然报出了加载错误（我们事先没能预判到的情况），
 * 记下能明确对应到 mods 文件夹中某个文件的错误，下次启动时自动跳过那个文件。
 */
final class LaunchMonitor {
    private static boolean installed;

    private LaunchMonitor() {
    }

    static synchronized void install() {
        if (installed) {
            return;
        }
        installed = true;
        Thread hook = new Thread(LaunchMonitor::onShutdown, "EmergencyShelter-LaunchMonitor");
        hook.setDaemon(false);
        Runtime.getRuntime().addShutdownHook(hook);

        // 有些模组在加载早期直接抛出异常、进程退出，连崩溃报告都不会生成；在这里截下异常用于归因
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                onUncaught(thread, error);
            } catch (Throwable ignored) {
            }
            if (previous != null) {
                previous.uncaughtException(thread, error);
            } else {
                // 保持 JVM 默认行为
                System.err.print("Exception in thread \"" + thread.getName() + "\" ");
                error.printStackTrace(System.err);
            }
        });
    }

    private static void onUncaught(Thread thread, Throwable error) {
        if (!Files.exists(ShelterFiles.file(ShelterFiles.LAUNCH_MARKER))) {
            return; // 游戏已经启动完成，之后的异常与加载无关
        }
        PendingIssues.Culprit culprit = attributeThrowable(error);
        if (culprit != null) {
            record(List.of(culprit));
        }
    }

    /** 从最深的 cause 开始，找第一个属于 mods 文件夹中某个模组的栈帧。 */
    @Nullable
    static PendingIssues.Culprit attributeThrowable(Throwable error) {
        LoadingModList list = LoadingModList.get();
        if (list == null) {
            return null;
        }
        Path modsDir = FMLPaths.MODSDIR.get().toAbsolutePath().normalize();
        Map<String, Path> moduleToFile = new LinkedHashMap<>();
        Map<String, String> moduleToModId = new LinkedHashMap<>();
        for (var info : list.getModFiles()) {
            try {
                Path path = topLevelPath(info.getFile());
                if (path == null || path.getFileSystem() != FileSystems.getDefault()) {
                    continue;
                }
                path = path.toAbsolutePath().normalize();
                if (path.getParent() == null || !path.getParent().equals(modsDir)) {
                    continue;
                }
                moduleToFile.put(info.moduleName(), path);
                if (!info.getMods().isEmpty()) {
                    moduleToModId.put(info.moduleName(), info.getMods().getFirst().getModId());
                }
            } catch (Throwable ignored) {
            }
        }
        List<Throwable> chain = new ArrayList<>();
        for (Throwable t = error; t != null && chain.size() < 16 && !chain.contains(t); t = t.getCause()) {
            chain.add(t);
        }
        for (int i = chain.size() - 1; i >= 0; i--) {
            for (StackTraceElement frame : chain.get(i).getStackTrace()) {
                String module = frame.getModuleName();
                if (module == null || module.startsWith("emergencyshelter")) {
                    continue;
                }
                Path file = moduleToFile.get(module);
                if (file != null) {
                    String message = ModScanner.rootMessage(error);
                    if (message.length() > 600) {
                        message = message.substring(0, 600) + "…";
                    }
                    return new PendingIssues.Culprit(file.getFileName().toString(), moduleToModId.get(module),
                            "加载时抛出异常：" + message);
                }
            }
        }
        return null;
    }

    /** NeoForge 完成依赖检查之后调用。 */
    static void afterScan() {
        LoadingModList list = LoadingModList.get();
        if (list == null) {
            return;
        }
        List<ModLoadingIssue> errors = list.getModLoadingIssues().stream()
                .filter(i -> i.severity() == ModLoadingIssue.Severity.ERROR)
                .toList();
        if (errors.isEmpty()) {
            return;
        }
        List<PendingIssues.Culprit> culprits = attribute(errors);
        record(culprits);
        if (!culprits.isEmpty()) {
            List<String> names = culprits.stream().map(c -> c.file).distinct().toList();
            list.getModLoadingIssues().add(ModLoadingIssue.error(
                    "紧急避险：已记录出错的模组文件 {0}。关闭并重新启动游戏后会自动禁用它们，届时即可正常进入游戏。",
                    String.join("、", names)));
        }
    }

    private static void onShutdown() {
        try {
            if (ConfigGuard.active) {
                ConfigGuard.forgetDeletedFiles();
            }
        } catch (Throwable ignored) {
        }
        try {
            if (!Files.exists(ShelterFiles.file(ShelterFiles.LAUNCH_MARKER))) {
                return; // 本次启动已经成功完成
            }
            List<ModLoadingIssue> errors = new ArrayList<>();
            try {
                errors.addAll(ModLoader.getLoadingErrors());
            } catch (Throwable ignored) {
            }
            try {
                LoadingModList list = LoadingModList.get();
                if (list != null) {
                    list.getModLoadingIssues().stream().filter(i -> i.severity() == ModLoadingIssue.Severity.ERROR).forEach(errors::add);
                }
            } catch (Throwable ignored) {
            }
            record(attribute(errors));
        } catch (Throwable t) {
            // 关闭阶段，不能再抛出任何东西
        }
    }

    private static synchronized void record(List<PendingIssues.Culprit> culprits) {
        if (culprits.isEmpty()) {
            return;
        }
        PendingIssues pending = ShelterFiles.read(ShelterFiles.PENDING_ISSUES, PendingIssues.class);
        if (pending == null || pending.culprits == null) {
            pending = new PendingIssues();
        }
        Map<String, PendingIssues.Culprit> merged = new LinkedHashMap<>();
        for (PendingIssues.Culprit c : pending.culprits) {
            merged.put(c.file, c);
        }
        for (PendingIssues.Culprit c : culprits) {
            merged.putIfAbsent(c.file, c);
        }
        pending.culprits = new ArrayList<>(merged.values());
        pending.time = System.currentTimeMillis();
        ShelterFiles.write(ShelterFiles.PENDING_ISSUES, pending);
        EarlyLog.LOG.warn("[紧急避险] 记录本次启动出错的模组文件：{}", merged.keySet());
    }

    static List<PendingIssues.Culprit> attribute(List<ModLoadingIssue> issues) {
        Path modsDir = FMLPaths.MODSDIR.get().toAbsolutePath().normalize();
        List<PendingIssues.Culprit> result = new ArrayList<>();
        for (ModLoadingIssue issue : issues) {
            try {
                IModFile file = issue.affectedModFile();
                IModInfo mod = issue.affectedMod();
                if (file == null && mod != null && mod.getOwningFile() != null) {
                    file = mod.getOwningFile().getFile();
                }
                Path path = file != null ? topLevelPath(file) : issue.affectedPath();
                if (path == null || path.getFileSystem() != FileSystems.getDefault()) {
                    continue;
                }
                path = path.toAbsolutePath().normalize();
                if (path.getParent() == null || !path.getParent().equals(modsDir)) {
                    continue; // 只处理 mods 文件夹里的文件
                }
                String modId = mod != null ? mod.getModId()
                        : (file != null && !file.getModInfos().isEmpty() ? file.getModInfos().getFirst().getModId() : null);
                String message = FMLTranslations.stripControlCodes(FMLTranslations.translateIssueEnglish(issue));
                if (issue.cause() != null) {
                    message += " (" + ModScanner.rootMessage(issue.cause()) + ")";
                }
                if (message.length() > 600) {
                    message = message.substring(0, 600) + "…";
                }
                result.add(new PendingIssues.Culprit(path.getFileName().toString(), modId, message));
            } catch (Throwable ignored) {
            }
        }
        return result;
    }

    @Nullable
    private static Path topLevelPath(IModFile file) {
        IModFile current = file;
        int guard = 0;
        while (current.getDiscoveryAttributes() != null && current.getDiscoveryAttributes().parent() != null && guard++ < 8) {
            current = current.getDiscoveryAttributes().parent();
        }
        Path path = current.getFilePath();
        if (path != null && path.getFileSystem() instanceof UnionFileSystem union) {
            path = union.getPrimaryPath();
        }
        return path;
    }
}
