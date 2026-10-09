package com.emergencyshelter.client;

import com.emergencyshelter.EmergencyShelter;
import com.emergencyshelter.report.ShelterReport;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.GameShuttingDownEvent;
import org.jetbrains.annotations.Nullable;

/**
 * 记录客户端"正在做什么"：启动加载、重新加载资源包、在菜单里、在世界里，以及当时用的资源包和光影。
 * 正常退出时删除记录；崩溃（包括显卡驱动崩溃这种连崩溃报告都没有的情况）时会留下来，
 * 下次启动时由 early 部分（GraphicsGuard）判断是不是光影或资源包引起的，并自动停用它们。
 * 另外记下"上次成功加载的资源包组合"，资源包出错时只停用新加的那几个。
 */
@EventBusSubscriber(modid = EmergencyShelter.MODID, value = Dist.CLIENT)
public final class ClientSession {
    private static final String FILE = "client-session.json";
    private static final String STATE_FILE = "graphics-state.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private static String phase = "STARTUP";
    private static long phaseSince = System.currentTimeMillis();
    private static final long STARTED = System.currentTimeMillis();
    private static long reloadStart = System.currentTimeMillis();
    @Nullable
    private static Map<String, Object> lastShader;
    private static int ticks;
    /** 本次运行中资源包加载失败过：原版会停用所有资源包，之后试着只恢复上次正常的那些。 */
    @Nullable
    private static List<String> failedPacks;
    private static boolean restoreAttempted;
    /** 等待重新启用的资源包（资源包出错、原版停用了全部资源包之后）。 */
    @Nullable
    private static List<String> pendingPacks;
    /** 正在正常退出：之后的事件（退出世界等）不再写记录。 */
    private static volatile boolean shuttingDown;

    private ClientSession() {
    }

    private static boolean enabled() {
        return ShelterReport.settings().graphicsGuard;
    }

    /** 模组构造时（启动加载开始）。 */
    public static void init() {
        if (!enabled()) {
            return;
        }
        write("STARTUP");
        // 每 10 秒更新一次记录文件的修改时间：进程被强制结束时，下次启动能知道它大约是什么时候停下的
        Thread heartbeat = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(10_000);
                    Path f = file(FILE);
                    if (shuttingDown) {
                        return;
                    }
                    if (Files.isRegularFile(f)) {
                        Files.setLastModifiedTime(f, FileTime.fromMillis(System.currentTimeMillis()));
                    }
                } catch (InterruptedException e) {
                    return;
                } catch (IOException ignored) {
                }
            }
        }, "EmergencyShelter-ClientSession");
        heartbeat.setDaemon(true);
        heartbeat.start();
    }

    // ------------------------------------------------------------------ 资源包加载

    public static void reloadStarted() {
        reloadStart = System.currentTimeMillis();
        if (enabled() && !"STARTUP".equals(phase)) {
            write("RELOAD");
        }
    }

    /** 资源加载完成（包括启动时的第一次）。 */
    public static void reloadFinished() {
        if (!enabled()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        List<String> packs = currentPacks();
        if (failedPacks == null) {
            JsonObject state = readState();
            JsonArray array = new JsonArray();
            packs.forEach(array::add);
            state.add("lastGoodPacks", array);
            state.addProperty("lastReloadMs", System.currentTimeMillis() - reloadStart);
            state.addProperty("streak", 0);
            writeState(state);
        } else {
            List<String> failed = failedPacks;
            failedPacks = null;
            if (!restoreAttempted) {
                // 原版因为资源包出错停用了全部资源包：试着把上次正常的那些放回去（只试一次）
                restoreAttempted = true;
                List<String> keep = lastGoodPacks();
                keep.removeIf(id -> !mc.getResourcePackRepository().getAvailableIds().contains(id));
                List<String> dropped = new ArrayList<>(failed);
                dropped.removeAll(keep);
                if (!keep.isEmpty() && !keep.equals(packs) && !dropped.isEmpty()) {
                    EmergencyShelter.LOGGER.warn("[紧急避险] 资源包加载失败，原版停用了全部资源包；重新启用上次正常的资源包 {}，只停用 {}", keep, dropped);
                    // 等游戏停在主菜单或已经进入世界时再重新加载，不和进入世界的过程抢在一起
                    pendingPacks = keep;
                    RenderGuard.queue(Component.translatable("emergencyshelter.render.packs_dropped", String.join("、", dropped)));
                }
            }
        }
        write(mc.level != null ? "WORLD" : "MENU");
    }

    /** 原版因为资源包出错准备停用全部资源包。 */
    public static void reloadFailed() {
        if (enabled() && failedPacks == null && !restoreAttempted) {
            failedPacks = currentPacks();
        }
    }

    private static List<String> currentPacks() {
        return new ArrayList<>(Minecraft.getInstance().options.resourcePacks);
    }

    private static List<String> lastGoodPacks() {
        List<String> out = new ArrayList<>();
        JsonObject state = readState();
        if (state.has("lastGoodPacks") && state.get("lastGoodPacks").isJsonArray()) {
            state.getAsJsonArray("lastGoodPacks").forEach(e -> out.add(e.getAsString()));
        }
        return out;
    }

    // ------------------------------------------------------------------ 世界、光影

    @SubscribeEvent
    public static void onLogin(ClientPlayerNetworkEvent.LoggingIn event) {
        if (enabled()) {
            write("WORLD");
        }
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        if (enabled()) {
            write("MENU");
        }
    }

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        List<String> packs = pendingPacks;
        if (packs != null && mc.getOverlay() == null
                && ((mc.level == null && mc.screen instanceof TitleScreen) || (mc.level != null && mc.player != null && mc.screen == null))) {
            pendingPacks = null;
            mc.getResourcePackRepository().setSelected(packs);
            mc.options.updateResourcePacks(mc.getResourcePackRepository());
        }
        // 光影可以随时在设置里开关：在世界里每 30 秒看一下
        if (++ticks % 600 == 0 && "WORLD".equals(phase) && enabled() && !Objects.equals(shader(), lastShader)) {
            write("WORLD");
        }
    }

    @SubscribeEvent
    public static void onShutdown(GameShuttingDownEvent event) {
        // 正常退出：不需要下次启动时处理
        shuttingDown = true;
        try {
            Files.deleteIfExists(file(FILE));
        } catch (IOException ignored) {
        }
    }

    /** Iris / Oculus 的光影设置（只有装了对应模组才算数）。 */
    @Nullable
    static Map<String, Object> shader() {
        for (String loader : List.of("iris", "oculus")) {
            // emergencyshelter.test.assumeShaders：只在测试时使用，没装光影模组也当作装了
            if (!ModList.get().isLoaded(loader) && !Boolean.getBoolean("emergencyshelter.test.assumeShaders")) {
                continue;
            }
            Path props = FMLPaths.CONFIGDIR.get().resolve(loader + ".properties");
            if (!Files.isRegularFile(props)) {
                continue;
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("loader", loader);
            try {
                for (String line : Files.readAllLines(props, StandardCharsets.UTF_8)) {
                    int eq = line.indexOf('=');
                    if (eq > 0) {
                        String key = line.substring(0, eq).trim();
                        String value = line.substring(eq + 1).trim();
                        if (key.equals("enableShaders")) {
                            out.put("enabled", Boolean.parseBoolean(value));
                        } else if (key.equals("shaderPack")) {
                            out.put("pack", value);
                        }
                    }
                }
            } catch (IOException e) {
                continue;
            }
            return out;
        }
        return null;
    }

    // ------------------------------------------------------------------ 文件

    private static void write(String newPhase) {
        if (shuttingDown) {
            return;
        }
        if (!newPhase.equals(phase)) {
            phase = newPhase;
            phaseSince = System.currentTimeMillis();
        }
        Map<String, Object> session = new LinkedHashMap<>();
        session.put("pid", ProcessHandle.current().pid());
        session.put("started", STARTED);
        session.put("phase", phase);
        session.put("phaseSince", phaseSince);
        try {
            session.put("packs", currentPacks());
        } catch (Throwable ignored) {
        }
        lastShader = shader();
        if (lastShader != null) {
            session.put("shader", lastShader);
        }
        writeJson(file(FILE), GSON.toJson(session));
    }

    private static JsonObject readState() {
        try {
            Path p = file(STATE_FILE);
            if (Files.isRegularFile(p)) {
                return JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8)).getAsJsonObject();
            }
        } catch (Exception ignored) {
        }
        return new JsonObject();
    }

    private static void writeState(JsonObject state) {
        writeJson(file(STATE_FILE), GSON.toJson(state));
    }

    private static Path file(String name) {
        return FMLPaths.GAMEDIR.get().resolve("emergencyshelter").resolve(name);
    }

    private static void writeJson(Path file, String json) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            EmergencyShelter.LOGGER.debug("[紧急避险] 无法写入 {}", file, e);
        }
    }
}
