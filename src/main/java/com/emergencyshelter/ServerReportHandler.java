package com.emergencyshelter;

import com.emergencyshelter.report.ConsoleText;
import com.emergencyshelter.report.ReportText;
import com.emergencyshelter.report.ShelterReport;
import com.emergencyshelter.salvage.LenientRegistries;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

@EventBusSubscriber(modid = EmergencyShelter.MODID)
public final class ServerReportHandler {
    private static final Set<UUID> notified = new HashSet<>();
    private static final Set<UUID> guardNotified = new HashSet<>();

    private ServerReportHandler() {
    }

    @SubscribeEvent
    public static void onServerAboutToStart(ServerAboutToStartEvent event) {
        notified.clear();
        guardNotified.clear();
        if (event.getServer().isDedicatedServer()) {
            // 专用服务器：所有模组都加载完成，算作启动成功（之后加载世界出的问题不属于"模组加载失败"）
            ShelterReport.markLaunchSucceeded();
        }
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        if (!event.getServer().isDedicatedServer()) {
            return;
        }
        ShelterReport report = ShelterReport.get();
        report.startup = com.emergencyshelter.report.EarlyBridge.loadingFinished();
        if (report.hasNews() || !LenientRegistries.skipped().isEmpty()) {
            EmergencyShelter.LOGGER.warn("================ " + ConsoleText.render(Component.translatable("emergencyshelter.report.title"))
                    + " ================");
            for (Component line : ReportText.lines(report)) {
                EmergencyShelter.LOGGER.warn(ConsoleText.render(line));
            }
            for (Component line : ReportText.datapackLines(false)) {
                EmergencyShelter.LOGGER.warn(ConsoleText.render(line));
            }
            String backup = WorldBackup.lastBackupNote();
            if (backup != null) {
                EmergencyShelter.LOGGER.warn(ConsoleText.render(Component.translatable("emergencyshelter.backup.done", backup)));
            }
            EmergencyShelter.LOGGER.warn("==========================================================");
        }
        for (Component line : ReportText.guardLines(50)) {
            EmergencyShelter.LOGGER.warn(ConsoleText.render(line));
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        ShelterReport report = ShelterReport.get();
        boolean datapackNews = !LenientRegistries.skipped().isEmpty();
        if (canViewReport(player) && !com.emergencyshelter.world.WorldGuard.events().isEmpty() && guardNotified.add(player.getUUID())) {
            for (Component line : ReportText.guardLines(8)) {
                player.sendSystemMessage(line);
            }
        }
        if ((report.hasNews() || datapackNews) && canViewReport(player) && notified.add(player.getUUID())) {
            if (report.hasNews()) {
                player.sendSystemMessage(ReportText.chatSummary(report));
            }
            for (Component line : ReportText.datapackLines(true)) {
                player.sendSystemMessage(line);
            }
            String backup = WorldBackup.lastBackupNote();
            if (backup != null) {
                player.sendSystemMessage(Component.translatable("emergencyshelter.backup.done", backup)
                        .withStyle(net.minecraft.ChatFormatting.GRAY));
            }
        }
    }

    public static boolean canViewReport(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        return player.hasPermissions(2) || (server != null && server.isSingleplayerOwner(player.getGameProfile()));
    }
}
