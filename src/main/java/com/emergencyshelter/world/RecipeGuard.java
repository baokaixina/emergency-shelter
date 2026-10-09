package com.emergencyshelter.world;

import com.emergencyshelter.Defense;
import com.emergencyshelter.EmergencyShelter;
import com.mojang.serialization.DataResult;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * 单条配方保护。
 * <p>
 * 原版读取配方时，只把格式错误（JsonParseException、IllegalArgumentException）当作"这一条坏了，跳过"。
 * 模组的配方代码如果抛出别的异常（例如 Create 的序列组装配方步骤为空时抛出 IndexOutOfBoundsException），
 * 整个配方加载就会中断，存档打不开（"Failed to load level data or datapacks"）。
 * 这里把这类异常也转成原版的"这一条格式错误"，只跳过出错的那一条配方，其余配方照常加载。
 */
public final class RecipeGuard {
    private static final AtomicInteger SKIPPED = new AtomicInteger();

    private RecipeGuard() {
    }

    /** 读取一条配方时出错：返回原版认得的"格式错误"，让原版跳过这一条。 */
    public static <R> DataResult<R> onRecipeFailure(@Nullable ResourceLocation id, Throwable error) {
        if (TickGuard.fatal(error) || !WorldGuard.enabled(s -> s.skipBrokenDataLoaders)) {
            throw WorldGuard.sneakyThrow(error);
        }
        String recipe = String.valueOf(id);
        String detail = WorldGuard.message(error);
        Defense.quietly("RecipeGuard.report", () -> report(recipe, detail, error));
        return DataResult.error(() -> "[紧急避险] 读取这个配方时出错（原版会让存档打不开），已跳过：" + detail);
    }

    private static void report(String recipe, String detail, Throwable error) {
        int n = SKIPPED.incrementAndGet();
        String mod = Culprits.modOf(error);
        String scripts = Culprits.scriptRefs(error);
        String full = detail + (mod == null ? "" : "（" + Culprits.displayName(mod) + "）") + (scripts.isEmpty() ? "" : "，出错的脚本：" + scripts);
        if (n == 1) {
            EmergencyShelter.LOGGER.error("[紧急避险] 读取配方 {} 时出错，已跳过这一条，其余配方照常加载（原版会让存档打不开）：{}", recipe, full, error);
        } else {
            EmergencyShelter.LOGGER.error("[紧急避险] 读取配方 {} 时出错，已跳过这一条：{}", recipe, full);
        }
        if (WorldGuard.server() != null) {
            WorldGuard.record("RECIPE_SKIPPED", recipe, null, full);
        } else {
            WorldGuard.recordBeforeStart("RECIPE_SKIPPED", recipe, full);
        }
    }
}
