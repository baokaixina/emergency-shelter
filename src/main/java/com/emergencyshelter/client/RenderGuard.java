package com.emergencyshelter.client;

import com.emergencyshelter.EmergencyShelter;
import com.emergencyshelter.mixin.guard.client.PoseStackAccessor;
import com.emergencyshelter.report.ShelterReport;
import com.emergencyshelter.world.Culprits;
import com.emergencyshelter.world.WorldGuard;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.ChatFormatting;
import net.minecraft.ReportedException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.particle.Particle;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.jetbrains.annotations.Nullable;

/**
 * 渲染出错的隔离（客户端）。
 * <p>
 * 原版里只要有一个方块实体、实体、粒子、物品图标、界面或 HUD 的渲染代码出错，整个游戏就会崩溃
 * （Rendering Block Entity、Rendering entity in world、Rendering screen 等），而且只要再看到它就再崩一次，
 * 常见于模组之间、模组与光影 / 材质包之间不兼容。这里改为只把出错的那个东西隐藏起来（本次运行有效），
 * 在聊天栏告诉玩家是哪个模组的问题，游戏照常进行。
 */
@EventBusSubscriber(modid = EmergencyShelter.MODID, value = Dist.CLIENT)
public final class RenderGuard {
    private static final Map<Level, LongOpenHashSet> HIDDEN_BLOCK_ENTITIES = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Set<UUID> HIDDEN_ENTITIES = ConcurrentHashMap.newKeySet();
    private static final Set<Item> BROKEN_ITEMS = ConcurrentHashMap.newKeySet();
    private static final Set<Object> DISABLED_LAYERS = Collections.newSetFromMap(Collections.synchronizedMap(new WeakHashMap<>()));
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();
    private static final List<Component> PENDING = Collections.synchronizedList(new ArrayList<>());
    private static int failures;

    private RenderGuard() {
    }

    public static boolean enabled() {
        return ShelterReport.settings().graphicsGuard;
    }

    /** 当前的 PoseStack 深度；读不到时（例如访问器没有生效）返回 -1，这时出错后不做恢复，但保护代码本身不能出错。 */
    public static int depth(@Nullable PoseStack pose) {
        try {
            return pose == null ? -1 : ((PoseStackAccessor) pose).emergencyshelter$poses().size();
        } catch (Throwable t) {
            return -1;
        }
    }

    private static void restore(@Nullable PoseStack pose, int depth) {
        if (pose == null || depth < 0) {
            return;
        }
        try {
            while (depth(pose) > Math.max(1, depth)) {
                pose.popPose();
            }
        } catch (Throwable ignored) {
        }
    }

    static Throwable unwrap(Throwable t) {
        return t instanceof ReportedException && t.getCause() != null ? t.getCause() : t;
    }

    /** 内存不足这类问题不能"隐藏"，照常抛出。 */
    public static void rethrowIfFatal(Throwable t) {
        if (t instanceof OutOfMemoryError || !enabled()) {
            throw WorldGuard.sneakyThrow(t);
        }
    }

    // ------------------------------------------------------------------ 方块实体

    public static boolean isHidden(BlockEntity be) {
        Level level = be.getLevel();
        if (level == null) {
            return false;
        }
        LongOpenHashSet set = HIDDEN_BLOCK_ENTITIES.get(level);
        return set != null && set.contains(be.getBlockPos().asLong());
    }

    public static void onBlockEntityFailure(BlockEntity be, Throwable error, PoseStack pose, int depth) {
        rethrowIfFatal(error);
        restore(pose, depth);
        Level level = be.getLevel();
        if (level != null) {
            HIDDEN_BLOCK_ENTITIES.computeIfAbsent(level, k -> new LongOpenHashSet()).add(be.getBlockPos().asLong());
        }
        String type = String.valueOf(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType()));
        notify("block_entity", type, be.getBlockPos().toShortString(), unwrap(error));
    }

    // ------------------------------------------------------------------ 实体

    public static boolean isHidden(Entity entity) {
        return !HIDDEN_ENTITIES.isEmpty() && HIDDEN_ENTITIES.contains(entity.getUUID());
    }

    public static void onEntityFailure(Entity entity, Throwable error, PoseStack pose, int depth) {
        rethrowIfFatal(error);
        restore(pose, depth);
        HIDDEN_ENTITIES.add(entity.getUUID());
        String type = String.valueOf(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()));
        notify("entity", type, entity.blockPosition().toShortString(), unwrap(error));
    }

    // ------------------------------------------------------------------ 粒子

    public static void onParticleFailure(Particle particle, Throwable error) {
        rethrowIfFatal(error);
        try {
            particle.remove();
        } catch (Throwable ignored) {
        }
        notify("particle", particle.getClass().getSimpleName(), null, unwrap(error));
    }

    // ------------------------------------------------------------------ 物品图标、物品提示

    public static boolean isBroken(ItemStack stack) {
        return !BROKEN_ITEMS.isEmpty() && BROKEN_ITEMS.contains(stack.getItem());
    }

    public static void onItemFailure(ItemStack stack, Throwable error, PoseStack pose, int depth) {
        rethrowIfFatal(error);
        restore(pose, depth);
        BROKEN_ITEMS.add(stack.getItem());
        notify("item", String.valueOf(BuiltInRegistries.ITEM.getKey(stack.getItem())), null, unwrap(error));
    }

    public static List<Component> onTooltipFailure(ItemStack stack, Throwable error) {
        rethrowIfFatal(error);
        notify("tooltip", String.valueOf(BuiltInRegistries.ITEM.getKey(stack.getItem())), null, unwrap(error));
        List<Component> lines = new ArrayList<>();
        try {
            lines.add(stack.getHoverName());
        } catch (Throwable t) {
            lines.add(Component.literal(String.valueOf(BuiltInRegistries.ITEM.getKey(stack.getItem()))));
        }
        lines.add(Component.translatable("emergencyshelter.render.tooltip_failed").withStyle(ChatFormatting.RED));
        return lines;
    }

    public static void onTooltipRenderFailure(Throwable error, PoseStack pose, int depth) {
        rethrowIfFatal(error);
        restore(pose, depth);
        notify("tooltip_render", "tooltip", null, unwrap(error));
    }

    // ------------------------------------------------------------------ HUD、界面

    public static boolean isLayerDisabled(Object layer) {
        return !DISABLED_LAYERS.isEmpty() && DISABLED_LAYERS.contains(layer);
    }

    public static void onLayerFailure(Object layer, String name, Throwable error, PoseStack pose, int depth) {
        rethrowIfFatal(error);
        restore(pose, depth);
        DISABLED_LAYERS.add(layer);
        notify("hud", name, null, unwrap(error));
    }

    /** 界面渲染或处理输入时出错：关掉这个界面。 */
    public static void onScreenFailure(@Nullable Screen screen, Throwable error) {
        rethrowIfFatal(error);
        try {
            RenderSystem.disableScissor();
        } catch (Throwable ignored) {
        }
        Minecraft mc = Minecraft.getInstance();
        String name = screen == null ? "?" : screen.getClass().getName();
        notify("screen", name, null, unwrap(error));
        if (screen != null && mc.screen == screen) {
            mc.tell(() -> {
                if (mc.screen == screen) {
                    mc.setScreen(null);
                }
            });
        }
    }

    // ------------------------------------------------------------------ 提示

    private static void notify(String kind, String what, @Nullable String where, Throwable error) {
        failures++;
        String mod = Culprits.modOf(error);
        if (!REPORTED.add(kind + " " + what)) {
            return;
        }
        String modName = mod == null ? "?" : Culprits.displayName(mod);
        EmergencyShelter.LOGGER.error("[紧急避险] {} {}{} 渲染出错（模组：{}），已在本次运行中隐藏它（原版会崩溃）", kind, what,
                where == null ? "" : " @ " + where, modName, error);
        MutableComponent line = Component.translatable("emergencyshelter.render." + kind, what, modName).withStyle(ChatFormatting.YELLOW);
        PENDING.add(Component.translatable("emergencyshelter.guard.prefix").withStyle(ChatFormatting.GOLD).append(line));
    }

    /** 加一条提示（进入世界后显示在聊天栏，在菜单里则弹出提示框）。 */
    public static void queue(Component message) {
        PENDING.add(Component.translatable("emergencyshelter.guard.prefix").withStyle(ChatFormatting.GOLD)
                .append(message.copy().withStyle(ChatFormatting.YELLOW)));
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (PENDING.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        List<Component> lines;
        synchronized (PENDING) {
            lines = new ArrayList<>(PENDING);
            PENDING.clear();
        }
        for (Component line : lines) {
            if (mc.player != null) {
                mc.gui.getChat().addMessage(line);
            } else {
                SystemToast.add(mc.getToasts(), SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                        Component.translatable("emergencyshelter.render.toast_title"), line);
            }
        }
    }

    /** 本次运行中被隐藏的渲染错误次数。 */
    public static int failures() {
        return failures;
    }
}
