package com.emergencyshelter;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.jetbrains.annotations.Nullable;

/**
 * 紧急避险的硬性规则：只做防御，自己绝不能成为崩溃的原因。
 * <p>
 * 每一处钩子都必须有退路：紧急避险自己的代码出错时，表现得和没装紧急避险完全一样——
 * 原版本来会正常运行的，照常运行；原版本来就会抛出的错误，原样抛出那个错误，而不是换成一个紧急避险自己的新错误。
 * 这样钩子再多，最坏的结果也只是"这一处没救回来"，不会比原版更糟。
 */
public final class Defense {
    private static final Set<String> LOGGED = ConcurrentHashMap.newKeySet();

    private Defense() {
    }

    /**
     * 处理原版的错误 {@code original} 时，紧急避险自己又出错了（{@code own}）：把自己的错误附在原始错误上，原样抛出原始错误。
     * {@code own} 就是 {@code original} 时（处理代码决定不处理、把原始错误抛回来），直接抛出。
     */
    public static RuntimeException fallback(@Nullable Throwable original, Throwable own) {
        if (original == null) {
            throw sneakyThrow(own); // 没有原始错误可以退回（不应该发生）
        }
        if (own != original) {
            try {
                original.addSuppressed(own);
            } catch (Throwable ignored) {
            }
            log("fallback " + own.getClass().getName(), own);
        }
        throw sneakyThrow(original);
    }

    /** 只做记录、备份、提示这类附带工作：出错时只写日志，不影响原版流程。 */
    public static void quietly(String what, Runnable action) {
        try {
            action.run();
        } catch (Throwable own) {
            log(what, own);
        }
    }

    /** 同上，出错时返回 {@code fallback}（通常就是原版的结果）。 */
    public static <T> T quietly(String what, Supplier<T> action, T fallback) {
        try {
            return action.get();
        } catch (Throwable own) {
            log(what, own);
            return fallback;
        }
    }

    /** 同一处只打印一次完整堆栈：热路径上反复出错时不能刷屏卡住游戏。 */
    public static void log(String what, Throwable own) {
        try {
            if (LOGGED.add(what)) {
                EmergencyShelter.LOGGER.error("[紧急避险] 紧急避险自身的处理代码出错（{}），这一处已按原版处理。请把这段日志反馈给作者", what, own);
            }
        } catch (Throwable ignored) {
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> RuntimeException sneakyThrow(Throwable t) throws T {
        throw (T) t;
    }
}
