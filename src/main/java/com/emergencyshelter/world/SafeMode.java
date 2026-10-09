package com.emergencyshelter.world;

import com.emergencyshelter.EmergencyShelter;
import com.emergencyshelter.ServerReportHandler;
import com.emergencyshelter.report.ShelterReport;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import org.jetbrains.annotations.Nullable;

/**
 * 安全模式：进入存档时整个世界先冻结（原版 {@code /tick freeze}：方块实体、实体、方块更新、随机刻、刷怪都停止，
 * 玩家可以走动、打开箱子、使用命令）。用于"一进存档就崩溃 / 卡死"时进去紧急修补：
 * 拆掉出问题的机器、清理东西、用命令处理，然后再解除。
 * <p>
 * 开启方式：settings.json 的 {@code safeMode}（打开任何存档都生效），
 * 或者 {@code /emergencyshelter safemode on}（只对当前存档，下次打开仍然生效，直到 off）。
 * <p>
 * 冻结发生在服务端开始运行第一刻之前，所以即使一运行就崩溃的东西也来不及运行。
 */
@EventBusSubscriber(modid = EmergencyShelter.MODID)
public final class SafeMode {
    private static final String FLAG = "safe-mode";

    private SafeMode() {
    }

    @Nullable
    private static Path flagFile() {
        Path root = WorldGuard.worldRoot();
        return root == null ? null : root.resolve("emergencyshelter").resolve(FLAG);
    }

    /** 当前存档是否用命令开启了安全模式。 */
    public static boolean worldFlag() {
        Path flag = flagFile();
        return flag != null && Files.isRegularFile(flag);
    }

    /** 本次打开存档时是否应当以安全模式进入。 */
    public static boolean requested() {
        return WorldGuard.enabled() && (ShelterReport.settings().safeMode || worldFlag());
    }

    public static boolean active(MinecraftServer server) {
        return requested() && server.tickRateManager().isFrozen();
    }

    /** 服务端开始运行第一刻之前（区块已经加载，但还没有任何东西运行过）。 */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onServerStarting(ServerStartingEvent event) {
        // 事件处理出错不能让游戏崩溃
        com.emergencyshelter.Defense.quietly("SafeMode.onServerStarting", () -> onServerStartingUnsafe(event));
    }

    private static void onServerStartingUnsafe(ServerStartingEvent event) {
        if (!requested()) {
            return;
        }
        try {
            event.getServer().tickRateManager().setFrozen(true);
            String why = ShelterReport.settings().safeMode ? "settings.json 里 safeMode 为 true" : "这个存档开启了安全模式";
            EmergencyShelter.LOGGER.warn("[紧急避险] 安全模式（{}）：世界已冻结，方块实体、实体、方块更新都不运行。修补完成后执行 /emergencyshelter safemode off", why);
            WorldGuard.record("SAFE_MODE", why, null, "世界已冻结；修补完成后执行 /emergencyshelter safemode off");
        } catch (Throwable t) {
            EmergencyShelter.LOGGER.error("[紧急避险] 无法进入安全模式", t);
        }
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        // 事件处理出错不能让游戏崩溃
        com.emergencyshelter.Defense.quietly("SafeMode.onLogin", () -> onLoginUnsafe(event));
    }

    private static void onLoginUnsafe(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && active(player.server) && ServerReportHandler.canViewReport(player)) {
            player.sendSystemMessage(notice());
        }
    }

    public static Component notice() {
        MutableComponent off = Component.literal("/emergencyshelter safemode off").withStyle(Style.EMPTY.withColor(ChatFormatting.AQUA)
                .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/emergencyshelter safemode off")));
        return Component.translatable("emergencyshelter.safemode.notice", off).withStyle(ChatFormatting.GOLD);
    }

    /** 命令：开启。当前存档立即冻结，以后每次打开这个存档都先冻结。 */
    public static boolean enable(MinecraftServer server) {
        Path flag = flagFile();
        if (flag == null) {
            return false;
        }
        try {
            Files.createDirectories(flag.getParent());
            Files.writeString(flag, "由 /emergencyshelter safemode on 创建；删除这个文件或执行 /emergencyshelter safemode off 即可解除。\n");
        } catch (IOException e) {
            EmergencyShelter.LOGGER.warn("[紧急避险] 无法写入安全模式标记 {}", flag, e);
            return false;
        }
        server.tickRateManager().setFrozen(true);
        return true;
    }

    /** 命令：解除。返回 false 表示 settings.json 里仍然开着（下次打开存档还会冻结）。 */
    public static boolean disable(MinecraftServer server) {
        Path flag = flagFile();
        if (flag != null) {
            try {
                Files.deleteIfExists(flag);
            } catch (IOException e) {
                EmergencyShelter.LOGGER.warn("[紧急避险] 无法删除安全模式标记 {}", flag, e);
            }
        }
        server.tickRateManager().setFrozen(false);
        return !ShelterReport.settings().safeMode;
    }
}
