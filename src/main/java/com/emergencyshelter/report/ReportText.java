package com.emergencyshelter.report;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

/** 把启动报告转换成给玩家看的文字（界面、聊天栏、命令共用）。 */
public final class ReportText {
    private static final String P = "emergencyshelter.report.";

    private ReportText() {
    }

    public static List<Component> lines(ShelterReport r) {
        Map<String, String> names = names(r);
        List<Component> out = new ArrayList<>();

        if (!r.hasNews()) {
            out.add(Component.translatable(P + "all_good").withStyle(ChatFormatting.GREEN));
        } else if (!r.disabled.isEmpty()) {
            out.add(Component.translatable(P + "summary", r.disabled.size()).withStyle(ChatFormatting.GOLD));
        }

        if (!r.missing.isEmpty()) {
            out.add(Component.empty());
            out.add(Component.translatable(P + "section.missing").withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
            for (ShelterReport.MissingMod m : r.missing) {
                out.add(Component.translatable(P + "missing.entry",
                        Component.literal(m.id).withStyle(ChatFormatting.RED),
                        String.join("、", m.requiredBy.stream().map(id -> names.getOrDefault(id, id)).toList())));
            }
        }

        if (!r.disabled.isEmpty()) {
            out.add(Component.empty());
            out.add(Component.translatable(P + "section.disabled").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD));
            for (ShelterReport.DisabledFile d : r.disabled) {
                out.add(Component.translatable(P + "disabled.entry",
                        Component.literal(d.displayName()).withStyle(ChatFormatting.WHITE),
                        Component.literal(d.file).withStyle(ChatFormatting.DARK_GRAY)));
                for (ShelterReport.Reason reason : d.reasons) {
                    out.add(Component.literal("    ").append(reason(reason, names)).withStyle(ChatFormatting.GRAY));
                }
            }
        }

        if (!r.healNotes.isEmpty()) {
            out.add(Component.empty());
            out.add(Component.translatable(P + "section.heal").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
            for (String note : r.healNotes) {
                out.add(Component.literal("• " + note).withStyle(ChatFormatting.GRAY));
            }
        }

        if (!r.configRestored.isEmpty()) {
            out.add(Component.empty());
            out.add(Component.translatable(P + "section.config").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
            for (ShelterReport.ConfigChange c : r.configRestored) {
                String reason = c.reason == null ? "MANUAL" : c.reason;
                out.add(Component.translatable(P + "config." + reason, c.file, c.detail == null ? "" : c.detail).withStyle(ChatFormatting.GRAY));
                if (c.backup != null) {
                    out.add(Component.translatable(P + "config.backup", c.backup).withStyle(ChatFormatting.DARK_GRAY));
                }
            }
        }

        out.addAll(startupLines(r.startup));

        List<ShelterReport.IgnoredFile> ignored = r.ignored;
        if (!ignored.isEmpty()) {
            out.add(Component.empty());
            out.add(Component.translatable(P + "section.ignored").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD));
            for (ShelterReport.IgnoredFile f : ignored) {
                String key = P + "ignored." + (f.reason == null ? "UNREADABLE" : f.reason);
                out.add(Component.translatable(key, f.file, f.detail == null ? "" : f.detail).withStyle(ChatFormatting.GRAY));
            }
        }

        if (!r.notes.isEmpty()) {
            out.add(Component.empty());
            out.add(Component.translatable(P + "section.notes").withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD));
            for (String note : r.notes) {
                out.add(Component.literal("• " + note).withStyle(ChatFormatting.GRAY));
            }
        }

        if (!r.disabled.isEmpty() || !r.missing.isEmpty()) {
            out.add(Component.empty());
            out.add(Component.translatable(P + "footer.restore").withStyle(ChatFormatting.GREEN));
            out.add(Component.translatable(P + "footer.placeholder").withStyle(ChatFormatting.GREEN));
        }
        return out;
    }

    /** 启动明显变慢时：哪些模组占用了时间，它们的配置有没有被改过。 */
    public static List<Component> startupLines(ShelterReport.StartupSummary s) {
        List<Component> out = new ArrayList<>();
        if (s == null || !s.isNotable()) {
            return out;
        }
        out.add(Component.empty());
        out.add(Component.translatable(P + "section.startup").withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD));
        out.add(s.baselineMs == null
                ? Component.translatable(P + "startup.total_nobase", s.totalMs / 1000).withStyle(ChatFormatting.GRAY)
                : Component.translatable(P + "startup.total", s.totalMs / 1000, s.baselineMs / 1000).withStyle(ChatFormatting.GRAY));
        boolean anyChanged = false;
        int shown = 0;
        for (ShelterReport.ModTime m : s.mods) {
            if (shown++ >= 6 && !m.slow) {
                continue;
            }
            String name = m.name == null ? m.id : m.name;
            Component line;
            if (m.baselineMs == null) {
                line = Component.translatable(P + "startup.mod_nobase", name, m.busyMs / 1000);
            } else if (m.baselineMs < 1000) {
                line = Component.translatable(P + "startup.mod_new", name, m.busyMs / 1000);
            } else {
                line = Component.translatable(P + "startup.mod", name, m.busyMs / 1000, m.baselineMs / 1000);
            }
            out.add(line.copy().withStyle(m.slow ? ChatFormatting.YELLOW : ChatFormatting.GRAY));
            if (m.changedConfigs != null && !m.changedConfigs.isEmpty()) {
                anyChanged = true;
                out.add(Component.translatable(P + "startup.changed", String.join("、", m.changedConfigs)).withStyle(ChatFormatting.GOLD));
            }
        }
        if (anyChanged) {
            out.add(Component.translatable(P + "startup.hint").withStyle(ChatFormatting.GREEN));
        }
        if (s.accepted) {
            out.add(Component.translatable(P + "startup.accepted").withStyle(ChatFormatting.GRAY));
        }
        return out;
    }

    /** 本次运行中隔离、回滚的东西。 */
    public static List<Component> guardLines(int limit) {
        List<Component> out = new ArrayList<>();
        List<com.emergencyshelter.world.WorldGuard.Event> events = com.emergencyshelter.world.WorldGuard.events();
        if (events.isEmpty()) {
            return out;
        }
        out.add(Component.translatable("emergencyshelter.guard.header", events.size()).withStyle(ChatFormatting.GOLD));
        int shown = 0;
        for (com.emergencyshelter.world.WorldGuard.Event e : events) {
            if (shown++ >= limit) {
                out.add(Component.translatable("emergencyshelter.guard.more", events.size() - limit).withStyle(ChatFormatting.GRAY));
                break;
            }
            out.add(Component.literal("  ").append(com.emergencyshelter.world.WorldGuard.describe(e)));
        }
        return out;
    }

    public static Component reason(ShelterReport.Reason reason, Map<String, String> names) {
        String dep = reason.dependency == null ? "?" : names.getOrDefault(reason.dependency, reason.dependency);
        String kind = reason.kind == null ? "UNKNOWN" : reason.kind;
        return switch (kind) {
            case "MISSING" -> Component.translatable(P + "reason.MISSING", dep);
            case "DISABLED_DEP" -> Component.translatable(P + "reason.DISABLED_DEP", dep);
            case "VERSION" -> Component.translatable(P + "reason.VERSION", dep, nz(reason.range), nz(reason.found));
            case "INCOMPATIBLE" -> Component.translatable(P + "reason.INCOMPATIBLE", dep, nz(reason.found));
            case "LANGUAGE" -> Component.translatable(P + "reason.LANGUAGE", dep);
            case "DUPLICATE" -> Component.translatable(P + "reason.DUPLICATE", nz(reason.detail));
            case "HEALED" -> Component.translatable(P + "reason.HEALED", nz(reason.detail));
            default -> Component.literal(kind + " " + dep);
        };
    }

    /** 本次运行中因引用缺失内容而被跳过的数据包条目。 */
    public static List<Component> datapackLines(boolean shortForm) {
        List<String> skipped = com.emergencyshelter.salvage.LenientRegistries.skipped();
        List<Component> out = new ArrayList<>();
        if (skipped.isEmpty()) {
            return out;
        }
        if (shortForm) {
            out.add(Component.translatable(P.replace("report.", "") + "datapack.skipped.short", skipped.size()).withStyle(ChatFormatting.YELLOW));
            return out;
        }
        out.add(Component.empty());
        out.add(Component.translatable(P.replace("report.", "") + "datapack.skipped", skipped.size()).withStyle(ChatFormatting.YELLOW));
        for (String file : skipped) {
            out.add(Component.literal("  • " + file).withStyle(ChatFormatting.GRAY));
        }
        return out;
    }

    /** 进入世界时发给玩家的一行摘要，点击可查看完整报告。 */
    public static Component chatSummary(ShelterReport r) {
        MutableComponent msg = Component.translatable(P + "chat.prefix").withStyle(ChatFormatting.GOLD);
        if (!r.disabled.isEmpty()) {
            msg.append(Component.translatable(P + "chat.disabled", r.disabled.size()).withStyle(ChatFormatting.YELLOW));
        }
        if (!r.missing.isEmpty()) {
            msg.append(Component.translatable(P + "chat.missing",
                    String.join("、", r.missing.stream().map(m -> m.id).toList())).withStyle(ChatFormatting.RED));
        }
        if (r.disabled.isEmpty() && r.missing.isEmpty()) {
            msg.append(Component.translatable(P + "chat.other").withStyle(ChatFormatting.YELLOW));
        }
        msg.append(" ");
        msg.append(Component.translatable(P + "chat.details").withStyle(Style.EMPTY
                .withColor(ChatFormatting.AQUA).withUnderlined(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/emergencyshelter report"))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.translatable(P + "chat.details.hover")))));
        return msg;
    }

    private static Map<String, String> names(ShelterReport r) {
        Map<String, String> names = new HashMap<>();
        for (ShelterReport.DisabledFile d : r.disabled) {
            for (ShelterReport.ModRef mod : d.mods) {
                if (mod.id != null && mod.name != null) {
                    names.put(mod.id, mod.name);
                }
            }
        }
        return names;
    }

    private static String nz(String s) {
        return s == null ? "?" : s;
    }
}
