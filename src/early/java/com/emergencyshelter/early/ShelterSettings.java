package com.emergencyshelter.early;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** {@code emergencyshelter/settings.json}，玩家或整合包作者可编辑。 */
final class ShelterSettings {
    /** 总开关：false 时完全不干预模组加载。 */
    boolean enabled = true;
    /** 加载阶段崩溃后，下次启动是否自动禁用出错的模组。 */
    boolean autoHeal = true;
    /** 连续自动修复的次数上限。 */
    int maxConsecutiveHeals = 5;
    /** 永远不要自动禁用的模组 id（即使会因此无法启动）。 */
    List<String> neverDisable = new ArrayList<>();
    /** 配置文件保护：记下上次正常启动时的配置，写坏或出错时换回去。 */
    boolean configGuard = true;
    /** 被删掉的配置文件是否自动补回。想故意删掉某个配置让模组重新生成时，把它改成 false。 */
    boolean restoreDeletedConfigs = true;
    /** 记录每个模组的加载耗时，启动明显变慢或卡住时在报告里指出原因。 */
    boolean startupProfiler = true;
    /** 存档保护：运行出错的东西隔离保存而不是崩溃，存档数据读不出来时换回上次正常的版本。 */
    boolean worldGuard = true;
    /** 光影、资源包导致崩溃后自动停用它们；渲染出错时只隐藏出错的东西而不是崩溃（客户端）。 */
    boolean graphicsGuard = true;
    /** 一个区块里同一种实体超过这个数量时，多出来的部分移进隔离区（0 表示不限制）。 */
    int entityLimitPerChunk = 2000;

    // ---- 存档保护的各项功能，可以单独关掉（worldGuard 为 false 时全部关闭）。字段与游戏内部分 ShelterReport.Settings 一致。
    /** 方块实体（机器等）运行出错时停止它的运行；false 时按原版处理（崩溃）。 */
    boolean freezeBrokenBlockEntities = true;
    /** 实体运行出错时转为占位实体保存；false 时按原版处理（崩溃）。 */
    boolean quarantineBrokenEntities = true;
    /** 背包里的物品运行出错时移进避险箱；false 时按原版处理（崩溃）。 */
    boolean moveBrokenItems = true;
    /** 数据包加载时只跳过出错的那一个加载器；false 时按原版处理（整体加载失败）。 */
    boolean skipBrokenDataLoaders = true;
    /** 生成地形出错时跳过出错的地物、结构。 */
    boolean worldgenGuard = true;
    /** 记录卡住的方块实体、实体、生成步骤，下次打开存档时停止它们。 */
    boolean hangGuard = true;
    /** 第一次压下某个运行错误之前，先把出错位置所在的存档文件（出错之前最后一次保存的版本）复制一份。 */
    boolean backupBeforeSuppress = true;
    /** 安全模式：打开任何存档时世界都先冻结（方块、实体、机器都不运行，玩家可以走动、使用命令），用于进存档紧急修补。 */
    boolean safeMode = false;

    /** 读取设置时发现的问题（写错格式、写错类型），提示玩家用。不写进文件。 */
    transient String problem;

    static ShelterSettings load() {
        Path file = ShelterFiles.file(ShelterFiles.SETTINGS);
        JsonObject defaults = ShelterFiles.GSON.toJsonTree(new ShelterSettings()).getAsJsonObject();
        if (!Files.isRegularFile(file)) {
            ShelterSettings settings = new ShelterSettings();
            ShelterFiles.write(ShelterFiles.SETTINGS, settings);
            return settings;
        }
        JsonObject user;
        String problem = null;
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            user = parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
            if (user == null) {
                problem = "不是一个 JSON 对象";
            }
        } catch (Exception e) {
            user = null;
            problem = "格式有误（" + rootMessage(e) + "）";
        }
        boolean rewrite = user == null;
        List<String> wrongType = new ArrayList<>();
        if (user != null) {
            for (Map.Entry<String, JsonElement> entry : defaults.entrySet()) {
                JsonElement value = user.get(entry.getKey());
                if (value == null) {
                    rewrite = true; // 旧版本写下的设置文件里没有新加的选项：补上默认值，方便玩家看到并修改
                } else if (!sameKind(entry.getValue(), value)) {
                    wrongType.add(entry.getKey() + " = " + value);
                    user.remove(entry.getKey());
                    rewrite = true;
                }
            }
            if (!wrongType.isEmpty()) {
                problem = "以下选项的值类型不对，已按默认值处理：" + String.join("，", wrongType);
            }
        }
        ShelterSettings settings = null;
        if (user != null) {
            try {
                settings = ShelterFiles.GSON.fromJson(user, ShelterSettings.class);
            } catch (Exception e) {
                problem = "无法读取（" + rootMessage(e) + "）";
            }
        }
        if (settings == null) {
            settings = new ShelterSettings();
            rewrite = true;
        }
        if (settings.neverDisable == null) {
            settings.neverDisable = new ArrayList<>();
        }
        if (problem != null) {
            // 先把玩家写的原文件留一份，不能直接覆盖掉
            String backup = ShelterFiles.SETTINGS + ".broken-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
            try {
                Files.copy(file, file.resolveSibling(backup), StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception e) {
                EarlyLog.LOG.warn("[紧急避险] 无法备份 {}", file, e);
            }
            settings.problem = "emergencyshelter/settings.json " + problem + "。原文件已另存为 emergencyshelter/" + backup
                    + "，" + (user == null ? "本次全部使用默认设置" : "其余选项照常生效") + "，请对照修改。";
            EarlyLog.LOG.warn("[紧急避险] {}", settings.problem);
        }
        if (rewrite) {
            ShelterFiles.write(ShelterFiles.SETTINGS, settings);
        }
        return settings;
    }

    private static boolean sameKind(JsonElement expected, JsonElement actual) {
        if (expected.isJsonArray()) {
            return actual.isJsonArray();
        }
        if (expected.isJsonPrimitive() && actual.isJsonPrimitive()) {
            JsonPrimitive e = expected.getAsJsonPrimitive();
            JsonPrimitive a = actual.getAsJsonPrimitive();
            return e.isBoolean() ? a.isBoolean() : e.isNumber() ? a.isNumber() : a.isString();
        }
        return false;
    }

    private static String rootMessage(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return String.valueOf(root.getMessage());
    }
}
