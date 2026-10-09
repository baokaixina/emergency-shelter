package com.emergencyshelter.client;

import com.emergencyshelter.EmergencyShelter;
import com.emergencyshelter.report.ShelterReport;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.ProgressScreen;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.gui.LoadingErrorScreen;

@EventBusSubscriber(modid = EmergencyShelter.MODID, value = Dist.CLIENT)
public final class ClientReportHandler {
    private static boolean firstScreenSeen;
    private static boolean reportShown;

    private ClientReportHandler() {
    }

    /** 加载完成后打开的第一个界面：说明启动成功；如有需要弹出启动报告。 */
    @SubscribeEvent
    public static void onScreenOpening(ScreenEvent.Opening event) {
        // 事件处理出错不能让游戏崩溃
        com.emergencyshelter.Defense.quietly("ClientReportHandler.onScreenOpening", () -> onScreenOpeningUnsafe(event));
    }

    private static void onScreenOpeningUnsafe(ScreenEvent.Opening event) {
        Screen screen = event.getNewScreen();
        if (screen == null || screen instanceof ReportScreen) {
            return;
        }
        if (!firstScreenSeen) {
            firstScreenSeen = true;
            if (screen instanceof LoadingErrorScreen) {
                reportShown = true; // 加载出错了：保留启动标记，下次启动时自动修复
                return;
            }
            ShelterReport.markLaunchSucceeded();
            ShelterReport.get().startup = com.emergencyshelter.report.EarlyBridge.loadingFinished();
        }
        if (reportShown || isTransitional(screen)) {
            return; // 自动进入世界（quickplay）时先不打断，回到菜单时再显示；进入世界后聊天栏也会提示
        }
        reportShown = true;
        ShelterReport report = ShelterReport.get();
        if (report.hasNews()) {
            event.setNewScreen(new ReportScreen(screen, report));
        }
    }

    private static boolean isTransitional(Screen screen) {
        return screen instanceof LevelLoadingScreen || screen instanceof GenericMessageScreen || screen instanceof ConnectScreen
                || screen instanceof ReceivingLevelScreen || screen instanceof ProgressScreen;
    }
}
