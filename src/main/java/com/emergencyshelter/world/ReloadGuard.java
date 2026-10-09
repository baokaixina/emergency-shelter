package com.emergencyshelter.world;

import com.emergencyshelter.EmergencyShelter;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.crafting.RecipeManager;

/**
 * 数据包加载保护（服务端数据：配方、战利品、标签、KubeJS / CraftTweaker 脚本等）。
 * <p>
 * 原版加载数据包时，只要有一个加载器抛出异常（常见于 KubeJS 服务端脚本、写错的数据包、模组的数据加载 bug），
 * 整个加载就失败：单人游戏提示"数据包错误，是否以安全模式进入"（安全模式会去掉所有模组的数据），专用服务端直接无法启动。
 * 这里改为：只跳过出错的那一个加载器，其余照常加载，存档照常打开，并告诉玩家是哪个模组、哪个脚本出的错。
 */
public final class ReloadGuard {
    private ReloadGuard() {
    }

    /**
     * 只处理服务端数据的加载（客户端资源包加载另有原版的回退机制）。
     * <p>
     * 只在原版把某一个加载器交给运行器的那一刻替换它，监听器列表本身保持原样：
     * 其它模组（例如 Forgified Fabric API）会在列表里按类型查找、按 id 排序原版的加载器（RecipeManager 等），
     * 如果把整个列表换成包装对象，它们就认不出来了，反而导致数据包加载失败。
     */
    public static PreparableReloadListener guard(PreparableReloadListener listener, List<PreparableReloadListener> listeners) {
        if (listener instanceof Safe || !WorldGuard.enabled(s -> s.skipBrokenDataLoaders) || !isServerData(listeners)) {
            return listener;
        }
        return new Safe(listener);
    }

    private static boolean isServerData(List<PreparableReloadListener> listeners) {
        for (PreparableReloadListener l : listeners) {
            if (l instanceof RecipeManager) {
                return true;
            }
        }
        return false;
    }

    private record Safe(PreparableReloadListener delegate) implements PreparableReloadListener {
        @Override
        public CompletableFuture<Void> reload(PreparationBarrier barrier, ResourceManager resourceManager, ProfilerFiller preparationsProfiler,
                                              ProfilerFiller reloadProfiler, Executor backgroundExecutor, Executor gameExecutor) {
            AtomicBoolean passed = new AtomicBoolean();
            PreparationBarrier proxy = new PreparationBarrier() {
                @Override
                public <T> CompletableFuture<T> wait(T value) {
                    passed.set(true);
                    return barrier.wait(value);
                }
            };
            CompletableFuture<Void> future;
            try {
                future = delegate.reload(proxy, resourceManager, preparationsProfiler, reloadProfiler, backgroundExecutor, gameExecutor);
            } catch (Throwable t) {
                future = CompletableFuture.failedFuture(t);
            }
            return future.handle((ok, error) -> {
                if (error == null) {
                    return CompletableFuture.<Void>completedFuture(null);
                }
                Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
                if (cause instanceof OutOfMemoryError) {
                    throw WorldGuard.sneakyThrow(cause); // 内存不足不能跳过，照原版处理
                }
                com.emergencyshelter.Defense.quietly("ReloadGuard.onFailure", () -> onFailure(delegate, error));
                // 出错时还没走到"准备完成"这一步：替它报到，否则其它加载器会一直等它
                return passed.get() ? CompletableFuture.<Void>completedFuture(null) : barrier.wait(null).<Void>thenApply(x -> null);
            }).thenCompose(Function.identity());
        }

        @Override
        public String getName() {
            return delegate.getName();
        }

        @Override
        public String toString() {
            return delegate.toString();
        }
    }

    private static void onFailure(PreparableReloadListener listener, Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        String mod = Culprits.modOf(cause);
        if (mod == null) {
            mod = Culprits.modOfClass(listener.getClass());
        }
        // NeoForge 会把加载器包一层（WrappedStateAwareListener 等），名字看不出是谁：有模组名就用模组名
        String name = mod != null && listener.getName().startsWith("Wrapped") ? Culprits.displayName(mod) : listener.getName();
        String scripts = Culprits.scriptRefs(cause);
        String detail = WorldGuard.message(cause) + (mod == null ? "" : "（" + Culprits.displayName(mod) + "）")
                + (scripts.isEmpty() ? "" : "，出错的脚本：" + scripts);
        EmergencyShelter.LOGGER.error("[紧急避险] 加载数据时 {} 出错，已跳过它，其余数据照常加载（原版会无法打开存档）：{}", name, detail, cause);
        if (WorldGuard.server() != null) {
            WorldGuard.record("DATA_RELOAD_SKIPPED", name, null, detail);
        } else {
            WorldGuard.recordBeforeStart("DATA_RELOAD_SKIPPED", name, detail);
        }
    }
}
