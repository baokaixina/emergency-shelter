package com.emergencyshelter.early;

import cpw.mods.jarhandling.JarContents;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.neoforged.neoforgespi.language.IModInfo;
import org.apache.maven.artifact.versioning.ArtifactVersion;
import org.apache.maven.artifact.versioning.VersionRange;
import org.jetbrains.annotations.Nullable;

/**
 * mods 文件夹中的一个 jar，以及从中读出的全部模组声明（包括 jar-in-jar 内嵌的）。
 */
final class ModCandidate {
    final Path path;
    final String fileName;
    final long size;
    final long lastModified;

    @Nullable
    JarContents contents;
    /** MOD / LIBRARY / GAMELIBRARY */
    String type = "MOD";
    /** 模块名：出现在崩溃堆栈里，用于把崩溃归因到文件。 */
    @Nullable
    String moduleName;
    final List<ModDecl> mods = new ArrayList<>();
    /** 需要的非内置语言加载器（例如 kotlinforforge）。 */
    final Set<String> requiredLanguages = new LinkedHashSet<>();
    /** 自己（或内嵌 jar）是否提供语言加载器。 */
    boolean providesLanguageLoader;
    final Set<String> mixinConfigs = new LinkedHashSet<>();

    ModCandidate(Path path, String fileName, long size, long lastModified) {
        this.path = path;
        this.fileName = fileName;
        this.size = size;
        this.lastModified = lastModified;
    }

    @Nullable
    ModDecl firstMod() {
        for (ModDecl mod : mods) {
            if (!mod.nested()) {
                return mod;
            }
        }
        return null;
    }

    List<ModDecl> topLevelMods() {
        return mods.stream().filter(m -> !m.nested()).toList();
    }

    String describe() {
        ModDecl first = firstMod();
        return first == null ? fileName : first.displayName() + " (" + first.modId() + ")";
    }

    void closeQuietly() {
        if (contents != null) {
            try {
                contents.close();
            } catch (Exception ignored) {
            }
            contents = null;
        }
    }

    record ModDecl(String modId, String displayName, ArtifactVersion version, boolean nested, List<DepDecl> deps) {
    }

    record DepDecl(String modId, VersionRange range, IModInfo.DependencyType type, IModInfo.DependencySide side) {
    }
}
