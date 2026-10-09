package com.emergencyshelter.client;

import com.emergencyshelter.report.EarlyBridge;
import com.emergencyshelter.report.ReportText;
import com.emergencyshelter.report.ShelterReport;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;

/** 启动报告界面：游戏加载完成后弹出一次，关闭后回到原本要打开的界面。 */
public final class ReportScreen extends Screen {
    private static final int TOP = 32;
    private static final int BOTTOM_MARGIN = 40;
    private static final int LINE_HEIGHT = 11;

    @Nullable
    private final Screen next;
    private final ShelterReport report;
    private final List<FormattedCharSequence> wrapped = new ArrayList<>();
    private double scroll;

    public ReportScreen(@Nullable Screen next, ShelterReport report) {
        super(Component.translatable("emergencyshelter.report.title"));
        this.next = next;
        this.report = report;
    }

    @Override
    protected void init() {
        wrapped.clear();
        int textWidth = Math.min(width - 40, 460);
        for (Component line : ReportText.lines(report)) {
            if (line.getString().isEmpty()) {
                wrapped.add(FormattedCharSequence.EMPTY);
            } else {
                wrapped.addAll(font.split(line, textWidth));
            }
        }
        int buttonY = height - 28;
        List<String> modsWithChanges = new ArrayList<>();
        if (report.startup != null && report.startup.isNotable()) {
            for (ShelterReport.ModTime m : report.startup.mods) {
                if (m.changedConfigs != null && !m.changedConfigs.isEmpty()) {
                    modsWithChanges.add(m.id);
                }
            }
        }
        int w = modsWithChanges.isEmpty() ? 150 : 120;
        int total = modsWithChanges.isEmpty() ? 2 : 3;
        int x = width / 2 - (total * w + (total - 1) * 8) / 2;
        addRenderableWidget(Button.builder(Component.translatable("emergencyshelter.report.button.continue"), b -> onClose())
                .bounds(x, buttonY, w, 20).build());
        x += w + 8;
        if (!modsWithChanges.isEmpty()) {
            addRenderableWidget(Button.builder(Component.translatable("emergencyshelter.report.button.restore_configs"), b -> {
                int restored = 0;
                for (String id : modsWithChanges) {
                    List<ShelterReport.ConfigChange> list = EarlyBridge.restoreConfigs(id);
                    restored += list == null ? 0 : list.size();
                }
                b.setMessage(Component.translatable("emergencyshelter.report.restore_done", restored));
                b.active = false;
            }).bounds(x, buttonY, w, 20).build());
            x += w + 8;
        }
        addRenderableWidget(Button.builder(Component.translatable("emergencyshelter.report.button.folder"),
                        b -> Util.getPlatform().openFile(ShelterReport.dir().toFile()))
                .bounds(x, buttonY, w, 20).build());
        scroll = Mth.clamp(scroll, 0, maxScroll());
    }

    private int viewHeight() {
        return height - TOP - BOTTOM_MARGIN;
    }

    private double maxScroll() {
        return Math.max(0, wrapped.size() * LINE_HEIGHT - viewHeight());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, 12, 0xFFFFAA00);

        int textWidth = Math.min(width - 40, 460);
        int left = (width - textWidth) / 2;
        int bottom = TOP + viewHeight();
        graphics.enableScissor(0, TOP, width, bottom);
        int y = TOP - (int) scroll;
        for (FormattedCharSequence line : wrapped) {
            if (y + LINE_HEIGHT > TOP && y < bottom) {
                graphics.drawString(font, line, left, y, 0xFFFFFFFF);
            }
            y += LINE_HEIGHT;
        }
        graphics.disableScissor();

        if (maxScroll() > 0) {
            int barHeight = Math.max(20, (int) ((double) viewHeight() * viewHeight() / (wrapped.size() * LINE_HEIGHT)));
            int barY = TOP + (int) ((viewHeight() - barHeight) * (scroll / maxScroll()));
            int barX = left + textWidth + 6;
            graphics.fill(barX, TOP, barX + 4, bottom, 0x40FFFFFF);
            graphics.fill(barX, barY, barX + 4, barY + barHeight, 0xC0FFFFFF);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        scroll = Mth.clamp(scroll - scrollY * LINE_HEIGHT * 3, 0, maxScroll());
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // 方向键 / 翻页键滚动
        switch (keyCode) {
            case 264 -> scroll = Mth.clamp(scroll + LINE_HEIGHT, 0, maxScroll());
            case 265 -> scroll = Mth.clamp(scroll - LINE_HEIGHT, 0, maxScroll());
            case 267 -> scroll = Mth.clamp(scroll + viewHeight(), 0, maxScroll());
            case 266 -> scroll = Mth.clamp(scroll - viewHeight(), 0, maxScroll());
            default -> {
                return super.keyPressed(keyCode, scanCode, modifiers);
            }
        }
        return true;
    }

    @Override
    public void onClose() {
        minecraft.setScreen(next);
    }
}
