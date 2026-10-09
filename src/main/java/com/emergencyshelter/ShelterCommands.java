package com.emergencyshelter;

import com.emergencyshelter.box.ShelterBox;
import com.emergencyshelter.report.EarlyBridge;
import com.emergencyshelter.report.ReportText;
import com.emergencyshelter.report.ShelterReport;
import com.emergencyshelter.salvage.LostDimension;
import com.emergencyshelter.salvage.SalvageStats;
import com.emergencyshelter.world.EntityOverflow;
import com.emergencyshelter.world.SafeMode;
import com.emergencyshelter.world.ShelterWorldData;
import com.emergencyshelter.world.TickGuard;
import com.emergencyshelter.world.WorldGuard;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

@EventBusSubscriber(modid = EmergencyShelter.MODID)
public final class ShelterCommands {
    private ShelterCommands() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("emergencyshelter")
                .then(Commands.literal("report")
                        .requires(ShelterCommands::canViewReport)
                        .executes(ctx -> {
                            ShelterReport report = ShelterReport.get();
                            ctx.getSource().sendSystemMessage(Component.translatable("emergencyshelter.report.title")
                                    .withStyle(ChatFormatting.GOLD));
                            for (Component line : ReportText.lines(report)) {
                                ctx.getSource().sendSystemMessage(line);
                            }
                            for (Component line : ReportText.datapackLines(false)) {
                                ctx.getSource().sendSystemMessage(line);
                            }
                            sendStats(ctx.getSource());
                            for (Component line : ReportText.guardLines(20)) {
                                ctx.getSource().sendSystemMessage(line);
                            }
                            return 1;
                        }))
                .then(Commands.literal("quarantine")
                        .requires(ShelterCommands::canViewReport)
                        .executes(ctx -> {
                            listQuarantine(ctx.getSource());
                            return 1;
                        })
                        .then(Commands.literal("retry")
                                .executes(ctx -> {
                                    retryQuarantine(ctx.getSource());
                                    return 1;
                                }))
                        .then(Commands.literal("entities")
                                .executes(ctx -> {
                                    listEntityFiles(ctx.getSource());
                                    return 1;
                                })
                                .then(Commands.literal("restore")
                                        .then(Commands.argument("file", StringArgumentType.greedyString())
                                                .suggests((ctx, builder) -> {
                                                    Path world = WorldGuard.worldRoot();
                                                    return SharedSuggestionProvider.suggest(world == null ? List.of() : EntityOverflow.list(world), builder);
                                                })
                                                .executes(ctx -> {
                                                    restoreEntities(ctx.getSource(), StringArgumentType.getString(ctx, "file"));
                                                    return 1;
                                                })))))
                .then(Commands.literal("config")
                        .requires(ShelterCommands::canViewReport)
                        .then(Commands.literal("restore")
                                .then(Commands.argument("modid", StringArgumentType.word())
                                        .executes(ctx -> {
                                            restoreConfigs(ctx.getSource(), StringArgumentType.getString(ctx, "modid"));
                                            return 1;
                                        }))))
                .then(Commands.literal("safemode")
                        .requires(ShelterCommands::canViewReport)
                        .executes(ctx -> {
                            CommandSourceStack source = ctx.getSource();
                            boolean active = SafeMode.active(source.getServer());
                            source.sendSystemMessage(Component.translatable(active ? "emergencyshelter.safemode.status.on"
                                    : "emergencyshelter.safemode.status.off").withStyle(ChatFormatting.GOLD));
                            return active ? 1 : 0;
                        })
                        .then(Commands.literal("on")
                                .executes(ctx -> {
                                    boolean ok = SafeMode.enable(ctx.getSource().getServer());
                                    ctx.getSource().sendSystemMessage(Component.translatable(ok ? "emergencyshelter.safemode.enabled"
                                            : "emergencyshelter.safemode.failed").withStyle(ok ? ChatFormatting.GOLD : ChatFormatting.RED));
                                    return ok ? 1 : 0;
                                }))
                        .then(Commands.literal("off")
                                .executes(ctx -> {
                                    boolean clear = SafeMode.disable(ctx.getSource().getServer());
                                    ctx.getSource().sendSystemMessage(Component.translatable(clear ? "emergencyshelter.safemode.disabled"
                                            : "emergencyshelter.safemode.disabled_global").withStyle(ChatFormatting.GREEN));
                                    return 1;
                                })))
                .then(box(Commands.literal("box")))
                .then(Commands.literal("return")
                        .executes(ctx -> {
                            LostDimension.returnPlayer(ctx.getSource().getPlayerOrException());
                            return 1;
                        })));
        dispatcher.register(box(Commands.literal("避险箱")));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> box(LiteralArgumentBuilder<CommandSourceStack> root) {
        return root
                .executes(ctx -> {
                    ShelterBox.open(ctx.getSource().getPlayerOrException(), 1);
                    return 1;
                })
                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                        .executes(ctx -> {
                            ShelterBox.open(ctx.getSource().getPlayerOrException(), IntegerArgumentType.getInteger(ctx, "page"));
                            return 1;
                        }));
    }

    private static void sendStats(CommandSourceStack source) {
        Map<String, Long> placeholders = SalvageStats.placeholderSnapshot();
        if (placeholders.isEmpty() && SalvageStats.restoredTotal() == 0 && SalvageStats.strippedTotal() == 0) {
            return;
        }
        source.sendSystemMessage(Component.translatable("emergencyshelter.stats.header",
                SalvageStats.placeholderTotal(), SalvageStats.strippedTotal(), SalvageStats.restoredTotal()).withStyle(ChatFormatting.AQUA));
        int shown = 0;
        for (Map.Entry<String, Long> e : placeholders.entrySet()) {
            if (shown++ >= 20) {
                source.sendSystemMessage(Component.literal("  …").withStyle(ChatFormatting.GRAY));
                break;
            }
            source.sendSystemMessage(Component.literal("  " + e.getKey() + " × " + e.getValue()).withStyle(ChatFormatting.GRAY));
        }
    }

    private static void listQuarantine(CommandSourceStack source) {
        List<Component> lines = ReportText.guardLines(50);
        if (lines.isEmpty()) {
            source.sendSystemMessage(Component.translatable("emergencyshelter.guard.none").withStyle(ChatFormatting.GREEN));
        }
        lines.forEach(source::sendSystemMessage);
        ShelterWorldData data = ShelterWorldData.get();
        if (data != null) {
            var frozen = data.list();
            if (!frozen.isEmpty()) {
                source.sendSystemMessage(Component.translatable("emergencyshelter.guard.frozen.header", frozen.size()).withStyle(ChatFormatting.YELLOW));
                for (var e : frozen) {
                    source.sendSystemMessage(Component.translatable("emergencyshelter.guard.frozen.entry", e.getValue().type(), e.getKey(),
                            e.getValue().error()).withStyle(ChatFormatting.GRAY));
                }
            }
        }
        if (data != null) {
            var skipped = data.skipWorldgenView();
            if (!skipped.isEmpty()) {
                source.sendSystemMessage(Component.translatable("emergencyshelter.guard.skipped.header", skipped.size()).withStyle(ChatFormatting.YELLOW));
                skipped.forEach((key, m) -> source.sendSystemMessage(Component.literal("• " + key.replace('|', ' ') + " —— " + m.error())
                        .withStyle(ChatFormatting.GRAY)));
            }
            var hang = data.hangEntitiesView();
            if (!hang.isEmpty()) {
                source.sendSystemMessage(Component.translatable("emergencyshelter.guard.hangentities.header", hang.size()).withStyle(ChatFormatting.YELLOW));
                hang.forEach((uuid, m) -> source.sendSystemMessage(Component.literal("• " + m.what() + " " + uuid + " —— " + m.error())
                        .withStyle(ChatFormatting.GRAY)));
            }
        }
        Path world = WorldGuard.worldRoot();
        if (world != null) {
            int files = EntityOverflow.list(world).size();
            if (files > 0) {
                source.sendSystemMessage(Component.translatable("emergencyshelter.guard.entities.hint", files).withStyle(ChatFormatting.YELLOW));
            }
            source.sendSystemMessage(Component.translatable("emergencyshelter.guard.log", "emergencyshelter/quarantine/log.txt")
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
    }

    private static void listEntityFiles(CommandSourceStack source) {
        Path world = WorldGuard.worldRoot();
        List<String> files = world == null ? List.of() : EntityOverflow.list(world);
        if (files.isEmpty()) {
            source.sendSystemMessage(Component.translatable("emergencyshelter.guard.entities.none").withStyle(ChatFormatting.GREEN));
            return;
        }
        source.sendSystemMessage(Component.translatable("emergencyshelter.guard.entities.header", files.size()).withStyle(ChatFormatting.YELLOW));
        for (String name : files) {
            String command = "/emergencyshelter quarantine entities restore " + name;
            source.sendSystemMessage(Component.translatable("emergencyshelter.guard.entities.entry", name).withStyle(Style.EMPTY
                    .withColor(ChatFormatting.AQUA)
                    .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, command))
                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(command)))));
        }
    }

    private static void restoreEntities(CommandSourceStack source, String name) {
        Path world = WorldGuard.worldRoot();
        if (world == null) {
            return;
        }
        try {
            int n = EntityOverflow.restore(source.getLevel(), world, name);
            source.sendSystemMessage(Component.translatable("emergencyshelter.guard.entities.restored", n).withStyle(ChatFormatting.GREEN));
        } catch (Exception e) {
            source.sendSystemMessage(Component.translatable("emergencyshelter.guard.entities.failed", e.getMessage()).withStyle(ChatFormatting.RED));
        }
    }

    private static void retryQuarantine(CommandSourceStack source) {
        ShelterWorldData data = ShelterWorldData.get();
        if (data == null) {
            return;
        }
        List<Map.Entry<ResourceKey<Level>, BlockPos>> resume = new ArrayList<>();
        data.frozenView().forEach((dim, map) -> map.keySet().forEach(pos -> resume.add(Map.entry(dim, BlockPos.of(pos)))));
        data.retry();
        for (var e : resume) {
            ServerLevel level = source.getServer().getLevel(e.getKey());
            if (level != null && level.isLoaded(e.getValue())) {
                TickGuard.refreshTicker(level, e.getValue());
            }
        }
        source.sendSystemMessage(Component.translatable("emergencyshelter.guard.retry.done").withStyle(ChatFormatting.GREEN));
    }

    private static void restoreConfigs(CommandSourceStack source, String modId) {
        List<ShelterReport.ConfigChange> restored = EarlyBridge.restoreConfigs(modId);
        if (restored == null) {
            source.sendSystemMessage(Component.translatable("emergencyshelter.config.unavailable").withStyle(ChatFormatting.RED));
        } else if (restored.isEmpty()) {
            source.sendSystemMessage(Component.translatable("emergencyshelter.config.nothing", modId).withStyle(ChatFormatting.GRAY));
        } else {
            source.sendSystemMessage(Component.translatable("emergencyshelter.config.restored", restored.size(),
                    String.join("、", restored.stream().map(c -> c.file).toList())).withStyle(ChatFormatting.GREEN));
        }
    }

    private static boolean canViewReport(CommandSourceStack source) {
        if (source.hasPermission(2)) {
            return true;
        }
        return source.getEntity() instanceof ServerPlayer player && ServerReportHandler.canViewReport(player);
    }
}
