package com.emergencyshelter.early;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLConfig;
import net.neoforged.fml.loading.VersionSupportMatrix;
import net.neoforged.neoforgespi.language.IModInfo;
import org.apache.maven.artifact.versioning.ArtifactVersion;
import org.apache.maven.artifact.versioning.VersionRange;

/**
 * 按 NeoForge 的同一套规则推算：哪些模组文件一旦加载就会让游戏报错，需要提前跳过。
 * 跳过一个文件后，依赖它的模组也会连锁失效，所以反复推算直到稳定。
 */
final class DependencyAnalysis {
    private final List<ModCandidate> candidates;
    private final Map<String, ArtifactVersion> systemMods;
    private final Dist dist;

    /** 被跳过的文件 → 原因 */
    final Map<ModCandidate, List<LaunchReport.Reason>> disabled = new LinkedHashMap<>();
    /** 因为存在同 id 的更新版本而被 NeoForge 忽略的旧文件 */
    final Map<ModCandidate, String> superseded = new LinkedHashMap<>();
    final Map<String, LinkedHashSet<String>> missing = new LinkedHashMap<>();

    /** 玩家要求永不禁用、但按规则本该禁用的文件（NeoForge 会因此报错）。 */
    final Map<ModCandidate, List<LaunchReport.Reason>> protectedFailures = new LinkedHashMap<>();

    private final Set<String> everProvided = new HashSet<>();
    private final Set<String> neverDisable;

    DependencyAnalysis(List<ModCandidate> candidates, Map<String, ArtifactVersion> systemMods, Dist dist, Set<String> neverDisable) {
        this.candidates = candidates;
        this.systemMods = systemMods;
        this.dist = dist;
        this.neverDisable = neverDisable;
    }

    private boolean isProtected(ModCandidate c) {
        return c.topLevelMods().stream().anyMatch(m -> neverDisable.contains(m.modId()));
    }

    void disableUpfront(ModCandidate candidate, LaunchReport.Reason reason) {
        disabled.computeIfAbsent(candidate, c -> new ArrayList<>()).add(reason);
    }

    void run() {
        for (ModCandidate c : candidates) {
            for (ModCandidate.ModDecl mod : c.mods) {
                everProvided.add(mod.modId());
            }
        }
        everProvided.addAll(systemMods.keySet());

        List<ModCandidate> active = new ArrayList<>();
        for (ModCandidate c : candidates) {
            if (!disabled.containsKey(c)) {
                active.add(c);
            }
        }

        resolveDuplicates(active);

        for (int round = 0; round < 64; round++) {
            Map<String, Provider> provided = buildProvided(active);
            boolean anyLanguageProvider = active.stream().anyMatch(c -> c.providesLanguageLoader);

            Map<ModCandidate, List<LaunchReport.Reason>> failed = new LinkedHashMap<>();
            for (ModCandidate c : active) {
                List<LaunchReport.Reason> reasons = new ArrayList<>();
                for (ModCandidate.ModDecl mod : c.mods) {
                    if (mod.nested()) {
                        Provider chosen = provided.get(mod.modId());
                        // 内嵌模组只有真正被选中加载时，它的依赖才有意义
                        if (chosen == null || chosen.decl != mod) {
                            continue;
                        }
                    }
                    checkDependencies(mod, provided, reasons);
                }
                if (!anyLanguageProvider) {
                    for (String lang : c.requiredLanguages) {
                        LaunchReport.Reason r = new LaunchReport.Reason();
                        r.kind = "LANGUAGE";
                        r.mod = firstId(c);
                        r.dependency = lang;
                        reasons.add(r);
                    }
                }
                if (!reasons.isEmpty()) {
                    if (isProtected(c)) {
                        protectedFailures.putIfAbsent(c, reasons);
                    } else {
                        failed.put(c, reasons);
                    }
                }
            }
            if (failed.isEmpty()) {
                break;
            }
            for (var e : failed.entrySet()) {
                disabled.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).addAll(e.getValue());
                for (LaunchReport.Reason r : e.getValue()) {
                    if ("MISSING".equals(r.kind)) {
                        missing.computeIfAbsent(r.dependency, k -> new LinkedHashSet<>()).add(r.mod);
                    }
                }
            }
            active.removeAll(failed.keySet());
        }
    }

    private void checkDependencies(ModCandidate.ModDecl mod, Map<String, Provider> provided, List<LaunchReport.Reason> out) {
        Set<String> removedByOverride = new HashSet<>();
        for (FMLConfig.DependencyOverride override : FMLConfig.getOverrides(mod.modId())) {
            if (override.remove()) {
                removedByOverride.add(override.modId());
            }
        }
        for (ModCandidate.DepDecl dep : mod.deps()) {
            if (dep.modId().equals(mod.modId()) || removedByOverride.contains(dep.modId())) {
                continue;
            }
            if (!dep.side().isContained(dist)) {
                continue;
            }
            Provider p = provided.get(dep.modId());
            switch (dep.type()) {
                case REQUIRED -> {
                    if (p == null) {
                        out.add(reason(everProvided.contains(dep.modId()) ? "DISABLED_DEP" : "MISSING", mod, dep, null));
                    } else if (!inRange(dep.modId(), dep.range(), provided)) {
                        out.add(reason("VERSION", mod, dep, p.version.toString()));
                    }
                }
                case OPTIONAL -> {
                    if (p != null && !inRange(dep.modId(), dep.range(), provided)) {
                        out.add(reason("VERSION", mod, dep, p.version.toString()));
                    }
                }
                case INCOMPATIBLE -> {
                    if (p != null && inRange(dep.modId(), dep.range(), provided)) {
                        out.add(reason("INCOMPATIBLE", mod, dep, p.version.toString()));
                    }
                }
                case DISCOURAGED -> {
                    // NeoForge 只给警告，不阻止启动
                }
            }
        }
    }

    /** 与 NeoForge ModSorter#modVersionNotContained 相同的判断。 */
    private static boolean inRange(String modId, VersionRange range, Map<String, Provider> provided) {
        return VersionSupportMatrix.testVersionSupportMatrix(range, modId, "mod", (id, r) -> {
            Provider p = provided.get(id);
            return p != null && (r.containsVersion(p.version) || p.version.toString().equals("0.0NONE"));
        });
    }

    private static LaunchReport.Reason reason(String kind, ModCandidate.ModDecl mod, ModCandidate.DepDecl dep, String found) {
        LaunchReport.Reason r = new LaunchReport.Reason();
        r.kind = kind;
        r.mod = mod.modId();
        r.dependency = dep.modId();
        r.range = dep.range() == null ? null : dep.range().toString();
        r.found = found;
        return r;
    }

    private Map<String, Provider> buildProvided(List<ModCandidate> active) {
        Map<String, Provider> provided = new HashMap<>();
        systemMods.forEach((id, v) -> provided.put(id, new Provider(v, null, null)));
        for (ModCandidate c : active) {
            for (ModCandidate.ModDecl mod : c.mods) {
                if (!mod.nested()) {
                    provided.putIfAbsent(mod.modId(), new Provider(mod.version(), c, mod));
                }
            }
        }
        // 内嵌（jar-in-jar）模组：顶层没有提供时才使用，多个版本取最高的
        for (ModCandidate c : active) {
            for (ModCandidate.ModDecl mod : c.mods) {
                if (mod.nested()) {
                    Provider existing = provided.get(mod.modId());
                    if (existing == null || (existing.decl != null && existing.decl.nested() && existing.version.compareTo(mod.version()) < 0)) {
                        provided.put(mod.modId(), new Provider(mod.version(), c, mod));
                    }
                }
            }
        }
        return provided;
    }

    /**
     * NeoForge 对"第一个 modid 相同"的多个文件只保留版本最高的；
     * 不同文件声明了同一个 modid（且不是第一个）会直接报错，这里保留一个、跳过其余。
     */
    private void resolveDuplicates(List<ModCandidate> active) {
        Map<String, List<ModCandidate>> byFirstId = new LinkedHashMap<>();
        for (ModCandidate c : active) {
            ModCandidate.ModDecl first = c.firstMod();
            if (first != null) {
                byFirstId.computeIfAbsent(first.modId(), k -> new ArrayList<>()).add(c);
            }
        }
        for (var e : byFirstId.entrySet()) {
            List<ModCandidate> files = e.getValue();
            if (files.size() > 1) {
                files.sort(Comparator.comparing((ModCandidate c) -> c.firstMod().version()).reversed());
                for (int i = 1; i < files.size(); i++) {
                    superseded.put(files.get(i), files.getFirst().fileName);
                    active.remove(files.get(i));
                }
            }
        }

        Map<String, ModCandidate> owner = new HashMap<>();
        for (ModCandidate c : new ArrayList<>(active)) {
            for (ModCandidate.ModDecl mod : c.topLevelMods()) {
                ModCandidate prev = owner.putIfAbsent(mod.modId(), c);
                if (prev != null && prev != c) {
                    ModCandidate loser = mod.modId().equals(firstId(c)) ? prev : c;
                    ModCandidate winner = loser == c ? prev : c;
                    LaunchReport.Reason r = new LaunchReport.Reason();
                    r.kind = "DUPLICATE";
                    r.mod = mod.modId();
                    r.detail = winner.fileName;
                    disabled.computeIfAbsent(loser, k -> new ArrayList<>()).add(r);
                    active.remove(loser);
                    owner.put(mod.modId(), winner);
                }
            }
        }
    }

    private static String firstId(ModCandidate c) {
        ModCandidate.ModDecl first = c.firstMod();
        return first == null ? c.fileName : first.modId();
    }

    private record Provider(ArtifactVersion version, ModCandidate file, ModCandidate.ModDecl decl) {
    }
}
