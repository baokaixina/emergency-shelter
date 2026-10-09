package com.emergencyshelter.early;

import com.electronwill.nightconfig.toml.TomlParser;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import net.neoforged.fml.loading.FMLPaths;
import org.jetbrains.annotations.Nullable;

/**
 * 配置文件保护，原理类似 Windows 的"最后一次正确的配置"：
 * <ul>
 *     <li>每次成功启动后，把 config/、defaultconfigs/、KubeJS 和 CraftTweaker 脚本记一份快照（只复制有变化的文件）；</li>
 *     <li>下次启动时、任何模组读取配置之前：写坏的（空文件、全是 0 字节、格式无法解析）和被删掉的文件，直接用快照恢复；</li>
 *     <li>启动失败并归因到某个模组时，先把它自上次正常启动以来改动过的配置换回去，仍然失败才禁用模组。</li>
 * </ul>
 * 被换下来的文件不会删除，放在 {@code emergencyshelter/config-backups/<时间>/}。
 */
final class ConfigGuard {
    static final String SNAPSHOT_DIR = "lastgood-config";
    static final String BACKUP_DIR = "config-backups";
    /** 每次启动前刚改过的配置，改动之前的版本（"上次正常"随后会被更新成改动后的版本）。 */
    static final String PREVIOUS_DIR = "lastgood-config-previous";
    private static final String RECENT = "recent-config-changes.json";
    private static final String MANIFEST = "manifest.json";
    private static final String OWNERS = "owners.json";
    private static final long MAX_FILE_SIZE = 4L << 20;
    private static final long MAX_TOTAL_SIZE = 256L << 20;
    private static final int KEEP_BACKUPS = 10;
    /** 本次启动启用了配置保护。 */
    static volatile boolean active;

    /** 参与快照的目录（相对游戏目录），以及其中不需要快照的子目录。 */
    private static final List<String> ROOTS = List.of("config", "defaultconfigs", "kubejs", "scripts");
    private static final Set<String> SKIP_DIRS = Set.of("kubejs/assets", "kubejs/exported", "kubejs/probe", "kubejs/.cache",
            "config/emergencyshelter");
    private static final Set<String> SKIP_EXT = Set.of("bak", "old", "tmp", "lock", "log", "png", "jpg", "jpeg", "gif", "webp",
            "ogg", "wav", "mp3", "zip", "jar", "7z", "gz", "mca", "class", "db", "sqlite");
    /** 这些格式是纯文本，正常情况下绝不会出现 0 字节。 */
    private static final Set<String> TEXT_EXT = Set.of("toml", "json", "json5", "jsonc", "cfg", "conf", "properties", "txt", "ini",
            "yml", "yaml", "snbt", "js", "ts", "zs", "xml", "csv", "mcmeta", "hocon", "lang");

    private ConfigGuard() {
    }

    static final class Manifest {
        long time;
        Map<String, Entry> files = new TreeMap<>();
    }

    static final class Entry {
        long size;
        long mtime;
        long crc;
    }

    /** 本次启动时与快照的比较结果，供自动修复使用。 */
    static final class State {
        final Map<String, String> changed = new TreeMap<>(); // 相对路径 → CHANGED / DELETED
        /**
         * 上一次启动之前刚改过的配置 → 改动之前的版本。上一次启动成功了，所以改动后的版本已经被记为"正常"；
         * 如果随后在游戏过程中崩溃或卡住，这些刚改过的配置同样值得怀疑。
         */
        final Map<String, Path> recent = new TreeMap<>();
        /** 上一次启动之前新加的脚本（启动成功后已被记为"正常"）。 */
        final Set<String> recentAdded = new TreeSet<>();
        boolean hasSnapshot;
    }

    static final class Recent {
        String stamp;
        List<String> files = new ArrayList<>();
        List<String> added = new ArrayList<>();
    }

    static Path gameDir() {
        return FMLPaths.GAMEDIR.get();
    }

    static Path snapshotRoot() {
        return ShelterFiles.dir().resolve(SNAPSHOT_DIR);
    }

    private static Path snapshotFile(String rel) {
        return snapshotRoot().resolve("files").resolve(rel);
    }

    @Nullable
    private static Manifest readManifest() {
        Path path = snapshotRoot().resolve(MANIFEST);
        if (!Files.isRegularFile(path)) {
            return null;
        }
        try {
            Manifest m = ShelterFiles.GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), Manifest.class);
            if (m != null && m.files == null) {
                m.files = new TreeMap<>();
            }
            return m;
        } catch (Exception e) {
            EarlyLog.LOG.warn("[紧急避险] 配置快照清单无法读取，本次按没有快照处理", e);
            return null;
        }
    }

    private static void writeManifest(Manifest manifest) {
        Path path = snapshotRoot().resolve(MANIFEST);
        try {
            Files.createDirectories(path.getParent());
            Path tmp = path.resolveSibling(MANIFEST + ".tmp");
            Files.writeString(tmp, ShelterFiles.GSON.toJson(manifest), StandardCharsets.UTF_8);
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            EarlyLog.LOG.warn("[紧急避险] 无法写入配置快照清单", e);
        }
    }

    // ------------------------------------------------------------------ 启动前检查

    /** 在任何模组读取配置之前调用：修复写坏的、补回被删的，并找出改动过的文件。 */
    static State checkAtLaunch(ShelterSettings settings, LaunchReport report) {
        active = true;
        State state = new State();
        Manifest manifest = readManifest();
        if (manifest == null || manifest.files.isEmpty()) {
            return state;
        }
        state.hasSnapshot = true;
        Path game = gameDir();
        String stamp = timestamp();
        Recent previousLaunch = ShelterFiles.read(RECENT, Recent.class);
        boolean manifestChanged = false;
        for (Map.Entry<String, Entry> e : manifest.files.entrySet()) {
            String rel = e.getKey();
            Entry entry = e.getValue();
            Path current = game.resolve(rel);
            try {
                if (!Files.exists(current)) {
                    // 脚本（KubeJS / CraftTweaker）被删掉通常是玩家有意为之，只补回配置文件
                    if (settings.restoreDeletedConfigs && !ScriptGuard.isScript(rel) && Files.isRegularFile(snapshotFile(rel))) {
                        restore(rel, stamp, false);
                        manifestChanged |= refresh(manifest, rel);
                        report.configRestored.add(new LaunchReport.ConfigChange(rel, "DELETED", null, null));
                        EarlyLog.LOG.warn("[紧急避险] 配置文件 {} 被删除，已从上次正常启动时的快照补回", rel);
                    } else {
                        state.changed.put(rel, "DELETED");
                    }
                    continue;
                }
                long size = Files.size(current);
                long mtime = Files.getLastModifiedTime(current).toMillis();
                if (size == entry.size && mtime == entry.mtime) {
                    continue;
                }
                byte[] bytes = Files.readAllBytes(current);
                if (crc(bytes) == entry.crc) {
                    entry.mtime = mtime; // 内容没变，只是被重新保存过
                    manifestChanged = true;
                    continue;
                }
                byte[] good = readSnapshot(rel);
                String broken = good == null ? null : brokenReason(rel, bytes, good);
                if (broken != null) {
                    String backup = restore(rel, stamp, true);
                    manifestChanged |= refresh(manifest, rel);
                    report.configRestored.add(new LaunchReport.ConfigChange(rel, "CORRUPT", broken, backup));
                    EarlyLog.LOG.warn("[紧急避险] 配置文件 {} 已损坏（{}），已用上次正常启动时的版本替换，坏文件备份在 {}", rel, broken, backup);
                } else {
                    state.changed.put(rel, "CHANGED");
                }
            } catch (Exception ex) {
                EarlyLog.LOG.warn("[紧急避险] 检查配置文件 {} 时出错", rel, ex);
            }
        }
        if (manifestChanged) {
            writeManifest(manifest);
        }
        rememberRecent(state, stamp, previousLaunch, manifest.files.keySet());
        return state;
    }

    /** 记下这次启动前刚改过的配置的旧版本；读出上一次启动时记下的那些。 */
    private static void rememberRecent(State state, String stamp, @Nullable Recent previousLaunch, Set<String> known) {
        Path root = ShelterFiles.dir().resolve(PREVIOUS_DIR);
        if (previousLaunch != null && previousLaunch.stamp != null && previousLaunch.files != null) {
            for (String rel : previousLaunch.files) {
                Path p = root.resolve(previousLaunch.stamp).resolve(rel);
                if (Files.isRegularFile(p) && !state.changed.containsKey(rel)) {
                    state.recent.put(rel, p);
                }
            }
        }
        if (previousLaunch != null && previousLaunch.added != null) {
            for (String rel : previousLaunch.added) {
                if (Files.isRegularFile(gameDir().resolve(rel))) {
                    state.recentAdded.add(rel);
                }
            }
        }
        Recent now = new Recent();
        now.stamp = stamp;
        now.added.addAll(ScriptGuard.scriptsNotIn(known));
        for (Map.Entry<String, String> e : state.changed.entrySet()) {
            if (!"CHANGED".equals(e.getValue())) {
                continue;
            }
            try {
                Path target = root.resolve(stamp).resolve(e.getKey());
                Files.createDirectories(target.getParent());
                Files.copy(snapshotFile(e.getKey()), target, StandardCopyOption.REPLACE_EXISTING);
                now.files.add(e.getKey());
            } catch (IOException ex) {
                EarlyLog.LOG.debug("[紧急避险] 无法保留 {} 的旧版本", e.getKey(), ex);
            }
        }
        ShelterFiles.write(RECENT, now);
        // 只需要保留这一次和上一次的
        try (Stream<Path> dirs = Files.isDirectory(root) ? Files.list(root) : Stream.empty()) {
            for (Path dir : dirs.toList()) {
                String name = dir.getFileName().toString();
                if (!name.equals(stamp) && (previousLaunch == null || !name.equals(previousLaunch.stamp))) {
                    deleteTree(dir);
                }
            }
        } catch (IOException ignored) {
        }
    }

    /**
     * 出错归因到某个模组时：把它改动过的配置换回上次正常时的版本。返回被恢复的文件。
     *
     * @param includeRecent 游戏过程中出的问题（进存档崩溃、卡住）：上一次启动前刚改过的配置也换回改动之前的版本
     */
    static List<String> restoreForMod(State state, String modId, LaunchReport report, String why, boolean includeRecent) {
        List<String> files = new ArrayList<>();
        for (String rel : state.changed.keySet()) {
            if (belongsTo(rel, modId)) {
                files.add(rel);
            }
        }
        List<String> restored = restoreAll(state, files, modId, report, why);
        if (includeRecent) {
            String stamp = timestamp();
            for (Map.Entry<String, Path> e : new ArrayList<>(state.recent.entrySet())) {
                String rel = e.getKey();
                if (!belongsTo(rel, modId) || restored.contains(rel)) {
                    continue;
                }
                try {
                    String backup = restoreFrom(rel, e.getValue(), stamp);
                    state.recent.remove(rel);
                    restored.add(rel);
                    report.configRestored.add(new LaunchReport.ConfigChange(rel, why, modId, backup));
                    EarlyLog.LOG.warn("[紧急避险] 已把配置文件 {} 恢复为上一次修改之前的版本（{}）", rel, why);
                } catch (IOException ex) {
                    EarlyLog.LOG.warn("[紧急避险] 无法恢复配置文件 {}", rel, ex);
                }
            }
        }
        return restored;
    }

    private static String restoreFrom(String rel, Path source, String stamp) throws IOException {
        Path game = gameDir();
        Path target = game.resolve(rel);
        String backupRel = null;
        if (Files.exists(target)) {
            Path backup = ShelterFiles.dir().resolve(BACKUP_DIR).resolve(stamp).resolve(rel);
            Files.createDirectories(backup.getParent());
            Files.copy(target, backup, StandardCopyOption.REPLACE_EXISTING);
            backupRel = game.relativize(backup).toString().replace('\\', '/');
            pruneBackups();
        }
        Files.createDirectories(target.getParent());
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        return backupRel;
    }

    static List<String> restoreAll(State state, List<String> files, @Nullable String modId, LaunchReport report, String why) {
        List<String> restored = new ArrayList<>();
        if (files.isEmpty()) {
            return restored;
        }
        Manifest manifest = readManifest();
        String stamp = timestamp();
        for (String rel : files) {
            try {
                if (!Files.isRegularFile(snapshotFile(rel))) {
                    continue;
                }
                String backup = restore(rel, stamp, true);
                if (manifest != null) {
                    refresh(manifest, rel);
                }
                state.changed.remove(rel);
                restored.add(rel);
                report.configRestored.add(new LaunchReport.ConfigChange(rel, why, modId, backup));
                EarlyLog.LOG.warn("[紧急避险] 已把配置文件 {} 恢复为上次正常启动时的版本（{}）", rel, why);
            } catch (Exception e) {
                EarlyLog.LOG.warn("[紧急避险] 无法恢复配置文件 {}", rel, e);
            }
        }
        if (manifest != null) {
            writeManifest(manifest);
        }
        return restored;
    }

    /** 快照里有哪些文件（相对游戏目录）；还没有快照时返回 null。 */
    @Nullable
    static Set<String> snapshotFiles() {
        Manifest manifest = readManifest();
        return manifest == null || manifest.files.isEmpty() ? null : manifest.files.keySet();
    }

    static List<String> changedFilesOf(State state, String modId) {
        List<String> out = new ArrayList<>();
        for (String rel : state.changed.keySet()) {
            if (belongsTo(rel, modId)) {
                out.add(rel);
            }
        }
        return out;
    }

    /** 当前与快照不同的文件（游戏运行中调用，供启动变慢时给出建议）。 */
    static State compareNow() {
        State state = new State();
        Manifest manifest = readManifest();
        if (manifest == null) {
            return state;
        }
        state.hasSnapshot = true;
        Path game = gameDir();
        for (Map.Entry<String, Entry> e : manifest.files.entrySet()) {
            Path current = game.resolve(e.getKey());
            try {
                if (!Files.exists(current)) {
                    state.changed.put(e.getKey(), "DELETED");
                } else if (Files.size(current) != e.getValue().size || Files.getLastModifiedTime(current).toMillis() != e.getValue().mtime) {
                    if (crc(Files.readAllBytes(current)) != e.getValue().crc) {
                        state.changed.put(e.getKey(), "CHANGED");
                    }
                }
            } catch (IOException ignored) {
            }
        }
        return state;
    }

    /** 把快照里的版本放回原位；需要时先备份当前文件。返回备份位置（相对游戏目录）。 */
    @Nullable
    private static String restore(String rel, String stamp, boolean backupCurrent) throws IOException {
        Path game = gameDir();
        Path target = game.resolve(rel);
        String backupRel = null;
        if (backupCurrent && Files.exists(target)) {
            Path backup = ShelterFiles.dir().resolve(BACKUP_DIR).resolve(stamp).resolve(rel);
            Files.createDirectories(backup.getParent());
            Files.copy(target, backup, StandardCopyOption.REPLACE_EXISTING);
            backupRel = game.relativize(backup).toString().replace('\\', '/');
            pruneBackups();
        }
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".emergencyshelter-tmp");
        Files.copy(snapshotFile(rel), tmp, StandardCopyOption.REPLACE_EXISTING);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        return backupRel;
    }

    private static boolean refresh(Manifest manifest, String rel) {
        Path current = gameDir().resolve(rel);
        Entry entry = manifest.files.get(rel);
        if (entry == null) {
            return false;
        }
        try {
            entry.size = Files.size(current);
            entry.mtime = Files.getLastModifiedTime(current).toMillis();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    @Nullable
    private static byte[] readSnapshot(String rel) {
        try {
            Path p = snapshotFile(rel);
            return Files.isRegularFile(p) ? Files.readAllBytes(p) : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** 判断文件是否"写坏了"。只在快照里的版本是好的、而当前版本明显不正常时才下结论。 */
    @Nullable
    static String brokenReason(String rel, byte[] bytes, byte[] good) {
        String ext = extension(rel);
        if (bytes.length == 0) {
            return good.length > 0 ? "文件变成了空的" : null;
        }
        if (allZero(bytes)) {
            return "文件内容全是 0 字节，通常是断电或强制关机造成的";
        }
        if (TEXT_EXT.contains(ext) && countZero(bytes) > 0 && countZero(good) == 0) {
            return "文本文件中出现了 0 字节，文件写入不完整";
        }
        if (("toml".equals(ext) || "json".equals(ext)) && !parses(ext, bytes) && parses(ext, good)) {
            return "格式无法解析";
        }
        return null;
    }

    private static boolean parses(String ext, byte[] bytes) {
        try {
            if ("toml".equals(ext)) {
                new TomlParser().parse(new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8));
            } else {
                String text = new String(bytes, StandardCharsets.UTF_8);
                if (text.startsWith("﻿")) {
                    text = text.substring(1);
                }
                JsonReader reader = new JsonReader(new StringReader(text));
                reader.setLenient(true);
                JsonParser.parseReader(reader);
                if (reader.peek() != com.google.gson.stream.JsonToken.END_DOCUMENT) {
                    return false;
                }
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------------ 快照

    /** 成功启动后调用：把当前配置记为"上次正常"。只复制有变化的文件。 */
    static synchronized void snapshot() {
        long start = System.currentTimeMillis();
        Manifest manifest = readManifest();
        if (manifest == null) {
            manifest = new Manifest();
        }
        Map<String, Entry> seen = new TreeMap<>();
        long[] total = {0};
        int[] copied = {0};
        Path game = gameDir();
        for (String root : ROOTS) {
            Path dir = game.resolve(root);
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try {
                Manifest m = manifest;
                Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) {
                        String rel = rel(game, d);
                        return SKIP_DIRS.contains(rel) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        String rel = rel(game, file);
                        if (!attrs.isRegularFile() || attrs.size() > MAX_FILE_SIZE || SKIP_EXT.contains(extension(rel))
                                || rel.endsWith(".emergencyshelter-tmp") || total[0] + attrs.size() > MAX_TOTAL_SIZE) {
                            return FileVisitResult.CONTINUE;
                        }
                        Entry old = m.files.get(rel);
                        long mtime = attrs.lastModifiedTime().toMillis();
                        if (old != null && old.size == attrs.size() && old.mtime == mtime && Files.isRegularFile(snapshotFile(rel))) {
                            seen.put(rel, old);
                            total[0] += attrs.size();
                            return FileVisitResult.CONTINUE;
                        }
                        try {
                            byte[] bytes = Files.readAllBytes(file);
                            long crc = crc(bytes);
                            Entry entry = new Entry();
                            entry.size = bytes.length;
                            entry.mtime = mtime;
                            entry.crc = crc;
                            if (old != null && old.crc == crc && Files.isRegularFile(snapshotFile(rel))) {
                                seen.put(rel, entry);
                            } else {
                                byte[] previous = old == null ? null : readSnapshot(rel);
                                if (previous != null && brokenReason(rel, bytes, previous) != null) {
                                    seen.put(rel, old); // 现在这份是坏的：保留快照里的好版本
                                } else {
                                    Path target = snapshotFile(rel);
                                    Files.createDirectories(target.getParent());
                                    Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
                                    Files.write(tmp, bytes);
                                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                                    seen.put(rel, entry);
                                    copied[0]++;
                                }
                            }
                            total[0] += bytes.length;
                        } catch (IOException e) {
                            EarlyLog.LOG.debug("[紧急避险] 快照时无法读取 {}", rel, e);
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exc) {
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException e) {
                EarlyLog.LOG.warn("[紧急避险] 记录配置快照时出错", e);
            }
        }
        // 已经不存在的文件：从快照里删掉
        for (String rel : manifest.files.keySet()) {
            if (!seen.containsKey(rel)) {
                try {
                    Files.deleteIfExists(snapshotFile(rel));
                } catch (IOException ignored) {
                }
            }
        }
        manifest.files = seen;
        manifest.time = System.currentTimeMillis();
        writeManifest(manifest);
        recordOwners();
        EarlyLog.LOG.info("[紧急避险] 已记录本次正常启动时的配置快照：{} 个文件，更新 {} 个，用时 {} ms",
                seen.size(), copied[0], System.currentTimeMillis() - start);
    }

    /** 游戏运行期间被删掉的文件（模组自己迁移配置等）：不应该在下次启动时补回。 */
    static synchronized void forgetDeletedFiles() {
        Manifest manifest = readManifest();
        if (manifest == null) {
            return;
        }
        Path game = gameDir();
        boolean changed = manifest.files.keySet().removeIf(rel -> !Files.exists(game.resolve(rel)));
        if (changed) {
            writeManifest(manifest);
        }
    }

    // ------------------------------------------------------------------ 配置文件属于哪个模组

    /** NeoForge 自己登记的"文件名 → 模组"对应关系，启动成功时记下来。 */
    @SuppressWarnings("unchecked")
    private static void recordOwners() {
        Map<String, String> owners = new TreeMap<>();
        try {
            Class<?> tracker = Class.forName("net.neoforged.fml.config.ConfigTracker");
            Object instance = tracker.getField("INSTANCE").get(null);
            Field fileMap = tracker.getDeclaredField("fileMap");
            fileMap.setAccessible(true);
            for (Map.Entry<String, ?> e : ((Map<String, ?>) fileMap.get(instance)).entrySet()) {
                Object config = e.getValue();
                String modId = (String) config.getClass().getMethod("getModId").invoke(config);
                owners.put(e.getKey().replace('\\', '/'), modId);
            }
        } catch (Throwable t) {
            EarlyLog.LOG.debug("[紧急避险] 无法读取 NeoForge 的配置登记表", t);
            return;
        }
        try {
            Path path = snapshotRoot().resolve(OWNERS);
            Files.createDirectories(path.getParent());
            Files.writeString(path, ShelterFiles.GSON.toJson(owners), StandardCharsets.UTF_8);
            ownersCache = owners;
        } catch (IOException e) {
            EarlyLog.LOG.debug("[紧急避险] 无法写入配置归属表", e);
        }
    }

    @Nullable
    private static Map<String, String> ownersCache;

    @SuppressWarnings("unchecked")
    private static Map<String, String> owners() {
        if (ownersCache == null) {
            ownersCache = new HashMap<>();
            Path path = snapshotRoot().resolve(OWNERS);
            if (Files.isRegularFile(path)) {
                try {
                    Map<String, String> m = ShelterFiles.GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), Map.class);
                    if (m != null) {
                        ownersCache.putAll(m);
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return ownersCache;
    }

    /**
     * 文件是否属于某个模组：先查 NeoForge 的登记表（文件名可能在 config/ 或 defaultconfigs/ 下），
     * 再按命名习惯判断：第一级目录或文件名本身就是模组 id，或以"模组id-"、"模组id_"、"模组id."开头。
     */
    static boolean belongsTo(String rel, String modId) {
        String id = modId.toLowerCase(Locale.ROOT);
        int slash = rel.indexOf('/');
        String inRoot = slash < 0 ? rel : rel.substring(slash + 1);
        String owner = owners().get(inRoot);
        if (owner != null) {
            return owner.equals(modId);
        }
        if (rel.startsWith("kubejs/")) {
            return id.equals("kubejs");
        }
        if (rel.startsWith("scripts/")) {
            return id.equals("crafttweaker");
        }
        String first = inRoot.contains("/") ? inRoot.substring(0, inRoot.indexOf('/')) : inRoot;
        first = first.toLowerCase(Locale.ROOT);
        int dot = first.lastIndexOf('.');
        String base = dot > 0 && !inRoot.contains("/") ? first.substring(0, dot) : first;
        if (base.equals(id)) {
            return true;
        }
        String[] tokens = base.split("[-_. ]");
        return tokens.length > 1 && tokens[0].equals(id);
    }

    // ------------------------------------------------------------------ 工具

    private static String rel(Path game, Path p) {
        return game.relativize(p).toString().replace('\\', '/');
    }

    static String extension(String rel) {
        int slash = rel.lastIndexOf('/');
        int dot = rel.lastIndexOf('.');
        return dot > slash ? rel.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }

    private static long crc(byte[] bytes) {
        CRC32 crc = new CRC32();
        crc.update(bytes);
        return crc.getValue();
    }

    private static boolean allZero(byte[] bytes) {
        for (byte b : bytes) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    private static int countZero(byte[] bytes) {
        int n = 0;
        for (byte b : bytes) {
            if (b == 0) {
                n++;
            }
        }
        return n;
    }

    static String timestamp() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
    }

    private static void pruneBackups() {
        Path dir = ShelterFiles.dir().resolve(BACKUP_DIR);
        try (Stream<Path> list = Files.list(dir)) {
            List<Path> all = list.filter(Files::isDirectory).sorted(Comparator.comparing(Path::getFileName)).toList();
            for (int i = 0; i < all.size() - KEEP_BACKUPS; i++) {
                deleteTree(all.get(i));
            }
        } catch (IOException ignored) {
        }
    }

    static void deleteTree(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }
}
