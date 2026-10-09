package com.emergencyshelter.early;

import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

/**
 * 上次是在进入存档时崩溃的吗？
 * 游戏内部分在打开存档时写下 {@code world-session.json}，正常关闭存档时删除；崩溃时会留下来并记上崩溃时间。
 * 如果是"刚进存档就崩溃"，并且能找出是哪个模组：
 * <ol>
 *     <li>先把这个模组在存档里的数据文件、以及它的配置，换回"上次正常退出存档时"的版本；</li>
 *     <li>同一个模组再次让存档进不去，才禁用这个模组（它的东西会以占位形式保留）。</li>
 * </ol>
 */
final class WorldSessionCheck {
    static final String SESSION_FILE = "world-session.json";
    /** 进入存档后这么短时间内崩溃，算作"进不去存档"。 */
    private static final long ENTRY_WINDOW_MS = 120_000;
    private static final Set<String> SNAPSHOT_DATA_ROOTS = Set.of("data", "serverconfig");

    private WorldSessionCheck() {
    }

    /** 与游戏内部分 WorldGuard 写入的字段一致。 */
    static final class Session {
        String world;
        String state;
        long started;
        long runningSince;
        long crashTime;

        boolean crashedOnEntry() {
            if (!"CRASHED".equals(state)) {
                return false;
            }
            return runningSince <= 0 || crashTime - runningSince < ENTRY_WINDOW_MS;
        }
    }

    /** 读取并删除上次留下的存档会话记录。 */
    @Nullable
    static Session take() {
        Session session = ShelterFiles.read(SESSION_FILE, Session.class);
        ShelterFiles.delete(SESSION_FILE);
        if (session == null || session.world == null) {
            return null;
        }
        return session;
    }

    static void handle(Session session, ShelterSettings settings, HealState heal, List<ModCandidate> candidates,
                       java.util.Set<String> stampSet, ConfigGuard.State configState, LaunchReport report) {
        Path world = Paths.get(session.world);
        String worldName = world.getFileName() == null ? session.world : world.getFileName().toString();
        List<PendingIssues.Culprit> culprits = CrashAnalyzer.analyze(ConfigGuard.gameDir(), Math.max(session.started, session.crashTime - 10_000), candidates);
        if (culprits.isEmpty()) {
            report.healNotes.add("上次进入存档 " + worldName + " 时游戏崩溃了，但没能从崩溃报告中确定是哪个模组造成的。"
                    + "存档的关键数据在 " + worldName + "/emergencyshelter/lastgood/ 有上次正常退出时的副本。");
            return;
        }
        PendingIssues.Culprit culprit = culprits.getFirst();
        String modId = culprit.modId;
        if (modId == null || settings.neverDisable.contains(modId)) {
            report.healNotes.add("上次进入存档 " + worldName + " 时 " + culprit.file + " 出错导致崩溃（" + culprit.message + "）。");
            return;
        }
        int attempts = heal.worldRollbacks.getOrDefault(modId, 0);
        if (attempts == 0) {
            List<String> rolled = rollbackWorldData(world, modId);
            String scriptOwner = ScriptGuard.ownerOf(modId);
            String configOwner = scriptOwner != null ? scriptOwner : modId;
            List<String> configs = settings.configGuard ? ConfigGuard.restoreForMod(configState, configOwner, report, "WORLD", true) : List.of();
            List<String> moved = settings.configGuard && scriptOwner != null ? ScriptGuard.quarantineNew(scriptOwner, configState, true, report) : List.of();
            heal.worldRollbacks.put(modId, 1);
            if (scriptOwner != null && (!configs.isEmpty() || !moved.isEmpty())) {
                heal.configRolledBack.put(configOwner, System.currentTimeMillis());
                report.healNotes.add(ScriptGuard.note(scriptOwner, "上次进入存档 " + worldName + " 时 " + culprit.file + " 出错导致崩溃（" + culprit.message + "）",
                        configs, moved, "如果仍然崩溃，下次启动会禁用这个模组。"));
                return;
            }
            if (!rolled.isEmpty() || !configs.isEmpty()) {
                List<String> all = new ArrayList<>(rolled);
                all.addAll(configs);
                report.healNotes.add("上次进入存档 " + worldName + " 时 " + culprit.file + " 出错导致崩溃。已把它的相关文件换回上次正常时的版本："
                        + String.join("、", all) + "。被换下来的文件保存在 " + worldName + "/emergencyshelter/quarantine/。如果仍然崩溃，下次启动会禁用这个模组。");
                EarlyLog.LOG.warn("[紧急避险] 上次进入存档时 {} 崩溃，已回滚它的数据：{}", modId, all);
                return;
            }
        }
        if (!settings.autoHeal) {
            report.healNotes.add("上次进入存档 " + worldName + " 时 " + culprit.file + " 出错导致崩溃（" + culprit.message + "）。自动修复已关闭，未做处理。");
            return;
        }
        if (heal.entries.stream().anyMatch(e -> e.file.equals(culprit.file))) {
            return;
        }
        HealState.Entry entry = new HealState.Entry();
        entry.file = culprit.file;
        entry.modIds.add(modId);
        entry.reason = "进入存档时崩溃：" + culprit.message;
        entry.time = System.currentTimeMillis();
        entry.filesAtHeal = new ArrayList<>(stampSet);
        entry.scriptOwner = ScriptGuard.ownerOf(modId);
        entry.scriptStamp = entry.scriptOwner == null ? null : ScriptGuard.stamp(entry.scriptOwner);
        heal.entries.add(entry);
        heal.worldRollbacks.put(modId, attempts + 1);
        report.healNotes.add("上次进入存档 " + worldName + " 时 " + culprit.file + " 出错导致崩溃"
                + (attempts > 0 ? "（回滚它的数据后仍然崩溃）" : "") + "，本次已禁用这个模组，它的方块、物品、实体会以占位形式保留。");
        EarlyLog.LOG.warn("[紧急避险] 进入存档时 {} 崩溃，本次禁用它", culprit.file);
    }

    /** 与游戏内部分 HangGuard 写入的 hang.json 中需要的字段一致。 */
    static final class Hang {
        String kind;
        String mod;
        long seconds;
    }

    /**
     * 上次服务端的某一刻卡住后被关闭（例如模组或脚本在每刻事件里死循环；专用服务端的看门狗会在 60 秒时关闭它）：
     * 先恢复那个模组改过的配置，脚本模组则恢复改过的脚本、移走新加的脚本。卡在具体方块实体、实体、世界生成里的情况由游戏内部分隔离。
     */
    static void handleHang(Session session, ShelterSettings settings, HealState heal, ConfigGuard.State configState, LaunchReport report) {
        Path file = Paths.get(session.world).resolve("emergencyshelter").resolve("hang.json");
        if (!Files.isRegularFile(file)) {
            return;
        }
        List<Hang> hangs;
        try {
            hangs = ShelterFiles.GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8),
                    new TypeToken<List<Hang>>() {
                    }.getType());
        } catch (Exception e) {
            return;
        }
        if (hangs == null) {
            return;
        }
        for (Hang h : hangs) {
            if (h == null || !"TICK".equals(h.kind) || h.mod == null) {
                continue;
            }
            String cause = "上次服务端有一刻卡住了 " + h.seconds + " 秒（卡在模组 " + h.mod + " 里）后被关闭";
            if (!settings.configGuard || !EarlyShelter.rollBackConfigsOf(h.mod, heal, configState, report, "PLAY_HANG", cause, "")) {
                report.healNotes.add(cause + "。它的" + (ScriptGuard.ownerOf(h.mod) != null ? "脚本和" : "") + "配置自上次正常启动以来没有改动，"
                        + "所以没有自动处理；卡住时的堆栈记录在 logs/latest.log。");
            }
        }
    }

    /** 把某个模组的存档数据文件（各维度 data/ 下、serverconfig/ 下属于它的文件）换回上次正常退出时的版本。 */
    static List<String> rollbackWorldData(Path world, String modId) {
        List<String> rolled = new ArrayList<>();
        Path lastgood = world.resolve("emergencyshelter").resolve("lastgood");
        if (!Files.isDirectory(lastgood)) {
            return rolled;
        }
        Path quarantine = world.resolve("emergencyshelter").resolve("quarantine").resolve(ConfigGuard.timestamp() + "-rollback");
        try {
            Files.walkFileTree(lastgood, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String rel = lastgood.relativize(file).toString().replace('\\', '/');
                    if (rel.endsWith(".json") && !rel.contains("/")) {
                        return FileVisitResult.CONTINUE; // 清单文件
                    }
                    if (!isModDataFile(rel, modId)) {
                        return FileVisitResult.CONTINUE;
                    }
                    Path current = world.resolve(rel);
                    try {
                        if (Files.exists(current) && Files.mismatch(current, file) == -1L) {
                            return FileVisitResult.CONTINUE;
                        }
                        if (Files.exists(current)) {
                            Path backup = quarantine.resolve(rel);
                            Files.createDirectories(backup.getParent());
                            Files.copy(current, backup, StandardCopyOption.REPLACE_EXISTING);
                        }
                        Files.createDirectories(current.getParent());
                        Files.copy(file, current, StandardCopyOption.REPLACE_EXISTING);
                        rolled.add(rel);
                    } catch (IOException e) {
                        EarlyLog.LOG.warn("[紧急避险] 无法回滚 {}", rel, e);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            EarlyLog.LOG.warn("[紧急避险] 回滚存档数据时出错", e);
        }
        return rolled;
    }

    /** {@code data/ae2_storage.dat}、{@code DIM-1/data/mekanism/xxx.dat}、{@code serverconfig/create-server.toml} 这类文件是否属于某个模组。 */
    static boolean isModDataFile(String rel, String modId) {
        String[] parts = rel.split("/");
        int idx = -1;
        for (int i = 0; i < parts.length - 1; i++) {
            if (SNAPSHOT_DATA_ROOTS.contains(parts[i])) {
                idx = i;
            }
        }
        if (idx < 0 || idx + 1 >= parts.length) {
            return false;
        }
        String first = parts[idx + 1].toLowerCase(Locale.ROOT);
        String id = modId.toLowerCase(Locale.ROOT);
        boolean isFile = idx + 1 == parts.length - 1;
        String base = isFile && first.lastIndexOf('.') > 0 ? first.substring(0, first.lastIndexOf('.')) : first;
        if (base.equals(id)) {
            return true;
        }
        String[] tokens = base.split("[-_. ]");
        return tokens.length > 1 && tokens[0].equals(id);
    }
}
