package com.emergencyshelter.early;

import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.toml.TomlParser;
import cpw.mods.jarhandling.JarContents;
import cpw.mods.jarhandling.SecureJar;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import net.neoforged.jarjar.metadata.ContainedJarMetadata;
import net.neoforged.jarjar.metadata.Metadata;
import net.neoforged.jarjar.metadata.MetadataIOHandler;
import net.neoforged.neoforgespi.language.IModFileInfo;
import net.neoforged.neoforgespi.language.IModInfo;
import net.neoforged.neoforgespi.language.MavenVersionAdapter;
import net.neoforged.neoforgespi.locating.IDiscoveryPipeline;
import net.neoforged.neoforgespi.locating.IModFile;
import net.neoforged.neoforgespi.locating.ModFileDiscoveryAttributes;
import org.apache.maven.artifact.versioning.ArtifactVersion;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.jetbrains.annotations.Nullable;

/**
 * 读取 mods 文件夹中每个 jar 的模组信息。
 * 顶层 jar 用 NeoForge 自己的读取器解析（与加载器结果完全一致）；
 * jar-in-jar 内嵌的模组只读出 id、版本和依赖，不创建任何文件系统，避免影响之后 NeoForge 的正式加载。
 */
final class ModScanner {
    static final Set<String> BUILTIN_LANGUAGES = Set.of("javafml", "lowcodefml", "minecraft");
    private static final String LANGUAGE_SERVICE = "net.neoforged.neoforgespi.language.IModLanguageLoader";
    private static final String MODS_TOML = "META-INF/neoforge.mods.toml";
    private static final String JARJAR_METADATA = "META-INF/jarjar/metadata.json";
    private static final int MAX_NESTING = 4;

    private ModScanner() {
    }

    sealed interface ScanResult permits Ok, Broken, NotAMod {
    }

    record Ok(ModCandidate candidate) implements ScanResult {
    }

    /** 文件损坏或模组信息有误，NeoForge 会因此报错拒绝启动。 */
    record Broken(String reason, String detail) implements ScanResult {
    }

    /** 不是 NeoForge 模组（例如 Fabric 模组），交给 NeoForge 按原逻辑处理（只会给出警告）。 */
    record NotAMod() implements ScanResult {
    }

    static ScanResult scan(Path path, IDiscoveryPipeline pipeline) {
        long size;
        long mtime;
        try {
            size = Files.size(path);
            mtime = Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return new Broken("UNREADABLE", String.valueOf(e));
        }
        ModCandidate candidate = new ModCandidate(path, path.getFileName().toString(), size, mtime);

        JarContents contents;
        try {
            contents = JarContents.of(path);
        } catch (Throwable t) {
            return new Broken("BROKEN_ZIP", rootMessage(t));
        }
        candidate.contents = contents;

        IModFile modFile;
        try {
            modFile = pipeline.readModFile(contents, ModFileDiscoveryAttributes.DEFAULT);
        } catch (Throwable t) {
            candidate.closeQuietly();
            String message = rootMessage(t);
            if (message.contains("No mod reader felt responsible")) {
                return new NotAMod();
            }
            return new Broken("BAD_METADATA", message);
        }
        if (modFile == null) {
            candidate.closeQuietly();
            return new NotAMod();
        }

        try {
            fillFromModFile(candidate, modFile, contents);
            readNested(candidate, modFile, contents);
        } catch (Throwable t) {
            candidate.closeQuietly();
            return new Broken("BAD_METADATA", rootMessage(t));
        }
        return new Ok(candidate);
    }

    private static void fillFromModFile(ModCandidate candidate, IModFile modFile, JarContents contents) {
        candidate.type = modFile.getType().name();
        IModFileInfo info = modFile.getModFileInfo();
        if (info != null) {
            candidate.moduleName = info.moduleName();
            for (IModFileInfo.LanguageSpec spec : info.requiredLanguageLoaders()) {
                if (!BUILTIN_LANGUAGES.contains(spec.languageName())) {
                    candidate.requiredLanguages.add(spec.languageName());
                }
            }
            for (var mixin : info.getConfig().getConfigList("mixins")) {
                mixin.<String>getConfigElement("config").ifPresent(candidate.mixinConfigs::add);
            }
        } else {
            candidate.moduleName = modFile.getSecureJar().name();
        }
        String manifestMixins = contents.getManifest().getMainAttributes().getValue("MixinConfigs");
        if (manifestMixins != null) {
            for (String config : manifestMixins.split(",")) {
                if (!config.isBlank()) {
                    candidate.mixinConfigs.add(config.trim());
                }
            }
        }
        for (SecureJar.Provider provider : contents.getMetaInfServices()) {
            if (LANGUAGE_SERVICE.equals(provider.serviceName())) {
                candidate.providesLanguageLoader = true;
            }
        }
        for (IModInfo mod : modFile.getModInfos()) {
            List<ModCandidate.DepDecl> deps = new ArrayList<>();
            for (IModInfo.ModVersion dep : mod.getDependencies()) {
                deps.add(new ModCandidate.DepDecl(dep.getModId(), dep.getVersionRange(), dep.getType(), dep.getSide()));
            }
            candidate.mods.add(new ModCandidate.ModDecl(mod.getModId(), mod.getDisplayName(), mod.getVersion(), false, deps));
        }
    }

    // ---------------------------------------------------------------- jar-in-jar

    private static void readNested(ModCandidate candidate, IModFile modFile, JarContents contents) throws IOException {
        if (contents.findFile(JARJAR_METADATA).isEmpty()) {
            return;
        }
        Path metadataPath = modFile.findResource("META-INF", "jarjar", "metadata.json");
        Optional<Metadata> metadata;
        try (InputStream in = Files.newInputStream(metadataPath)) {
            metadata = MetadataIOHandler.fromStream(in);
        }
        if (metadata.isEmpty()) {
            return;
        }
        for (ContainedJarMetadata jar : metadata.get().jars()) {
            Path nestedPath = modFile.findResource(jar.path());
            if (!Files.isRegularFile(nestedPath)) {
                continue;
            }
            byte[] bytes;
            try (InputStream in = Files.newInputStream(nestedPath)) {
                bytes = in.readAllBytes();
            }
            readNestedJar(candidate, bytes, jar.version() == null ? null : jar.version().artifactVersion(), 1);
        }
    }

    /** 读取任意 jar（例如 NeoForge 本体）通过 jar-in-jar 内嵌的模组。 */
    /**
     * 一个 jar 自己的 neoforge.mods.toml 里声明的模组。用于"早期服务"类的 jar（例如 Sodium 0.6 以后的版本）：
     * 它们的外层是库文件，真正的模组由它们自己的加载器在启动时加入，NeoForge 扫描 mods 文件夹时看不到。
     */
    static List<ModCandidate.ModDecl> readOwnMods(Path jar) {
        ModCandidate holder = new ModCandidate(jar, jar.getFileName().toString(), 0, 0);
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry("META-INF/neoforge.mods.toml");
            if (entry == null) {
                return List.of();
            }
            String toml;
            try (InputStream in = zip.getInputStream(entry)) {
                toml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            String version = "0";
            ZipEntry manifestEntry = zip.getEntry("META-INF/MANIFEST.MF");
            if (manifestEntry != null) {
                try (InputStream in = zip.getInputStream(manifestEntry)) {
                    String v = new java.util.jar.Manifest(in).getMainAttributes().getValue("Implementation-Version");
                    if (v != null) {
                        version = v;
                    }
                }
            }
            parseNestedToml(holder, toml, version);
        } catch (Exception e) {
            EarlyLog.LOG.debug("[紧急避险] 无法读取 {} 声明的模组", jar, e);
        }
        return holder.mods;
    }

    static List<ModCandidate.ModDecl> readJarInJarOf(Path jar) {
        ModCandidate holder = new ModCandidate(jar, jar.getFileName().toString(), 0, 0);
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jar.toFile())) {
            ZipEntry metaEntry = zip.getEntry(JARJAR_METADATA);
            if (metaEntry == null) {
                return List.of();
            }
            Optional<Metadata> metadata;
            try (InputStream in = zip.getInputStream(metaEntry)) {
                metadata = MetadataIOHandler.fromStream(in);
            }
            if (metadata.isEmpty()) {
                return List.of();
            }
            for (ContainedJarMetadata contained : metadata.get().jars()) {
                ZipEntry entry = zip.getEntry(contained.path());
                if (entry == null) {
                    continue;
                }
                byte[] bytes;
                try (InputStream in = zip.getInputStream(entry)) {
                    bytes = in.readAllBytes();
                }
                readNestedJar(holder, bytes, contained.version() == null ? null : contained.version().artifactVersion(), 1);
            }
        } catch (Exception e) {
            EarlyLog.LOG.warn("[紧急避险] 无法读取 {} 内嵌的模组", jar, e);
        }
        return holder.mods;
    }

    private static void readNestedJar(ModCandidate candidate, byte[] jarBytes, @Nullable ArtifactVersion declaredVersion, int depth) throws IOException {
        String toml = null;
        Manifest manifest = null;
        byte[] innerMetadata = null;
        Map<String, byte[]> innerJars = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(jarBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (name.equals(MODS_TOML)) {
                    toml = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                } else if (name.equals("META-INF/MANIFEST.MF")) {
                    manifest = new Manifest(new ByteArrayInputStream(zip.readAllBytes()));
                } else if (name.equals("META-INF/services/" + LANGUAGE_SERVICE)) {
                    candidate.providesLanguageLoader = true;
                } else if (depth < MAX_NESTING && name.equals(JARJAR_METADATA)) {
                    innerMetadata = zip.readAllBytes();
                } else if (depth < MAX_NESTING && name.startsWith("META-INF/jarjar/") && name.endsWith(".jar")) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    zip.transferTo(out);
                    innerJars.put(name, out.toByteArray());
                }
            }
        }

        if (toml != null) {
            String jarVersion = null;
            if (manifest != null) {
                jarVersion = manifest.getMainAttributes().getValue(Attributes.Name.IMPLEMENTATION_VERSION);
            }
            if (jarVersion == null && declaredVersion != null) {
                jarVersion = declaredVersion.toString();
            }
            parseNestedToml(candidate, toml, jarVersion == null ? "0.0NONE" : jarVersion);
        }

        if (innerMetadata != null) {
            Optional<Metadata> metadata = MetadataIOHandler.fromStream(new ByteArrayInputStream(innerMetadata));
            if (metadata.isPresent()) {
                for (ContainedJarMetadata jar : metadata.get().jars()) {
                    byte[] inner = innerJars.get(jar.path());
                    if (inner != null) {
                        readNestedJar(candidate, inner, jar.version() == null ? null : jar.version().artifactVersion(), depth + 1);
                    }
                }
            }
        }
    }

    private static void parseNestedToml(ModCandidate candidate, String toml, String jarVersion) {
        Config config = new TomlParser().parse(new InputStreamReader(new ByteArrayInputStream(toml.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8));
        String modLoader = config.get("modLoader");
        if (modLoader != null && !BUILTIN_LANGUAGES.contains(modLoader)) {
            candidate.requiredLanguages.add(modLoader);
        }
        List<Config> mods = config.get("mods");
        if (mods == null) {
            return;
        }
        for (Config mod : mods) {
            String modId = mod.get("modId");
            if (modId == null) {
                continue;
            }
            String rawVersion = mod.getOrElse("version", "1");
            String version = rawVersion.replace("${file.jarVersion}", jarVersion);
            if (version.contains("${")) {
                version = "0.0NONE";
            }
            String displayName = mod.getOrElse("displayName", modId);
            List<ModCandidate.DepDecl> deps = new ArrayList<>();
            List<Config> depConfigs = config.get(List.of("dependencies", modId));
            if (depConfigs != null) {
                for (Config dep : depConfigs) {
                    String depId = dep.get("modId");
                    if (depId == null) {
                        continue;
                    }
                    IModInfo.DependencyType type = IModInfo.DependencyType.REQUIRED;
                    String typeName = dep.get("type");
                    if (typeName != null) {
                        try {
                            type = IModInfo.DependencyType.valueOf(typeName.toUpperCase(Locale.ROOT));
                        } catch (IllegalArgumentException ignored) {
                        }
                    }
                    IModInfo.DependencySide side = IModInfo.DependencySide.BOTH;
                    String sideName = dep.get("side");
                    if (sideName != null) {
                        try {
                            side = IModInfo.DependencySide.valueOf(sideName.toUpperCase(Locale.ROOT));
                        } catch (IllegalArgumentException ignored) {
                        }
                    }
                    String range = dep.get("versionRange");
                    deps.add(new ModCandidate.DepDecl(depId,
                            range == null ? IModInfo.UNBOUNDED : MavenVersionAdapter.createFromVersionSpec(range), type, side));
                }
            }
            candidate.mods.add(new ModCandidate.ModDecl(modId, displayName, new DefaultArtifactVersion(version), true, deps));
        }
    }

    static String rootMessage(Throwable t) {
        StringBuilder sb = new StringBuilder();
        Throwable cur = t;
        int guard = 0;
        while (cur != null && guard++ < 8) {
            if (!sb.isEmpty()) {
                sb.append(" <- ");
            }
            sb.append(cur.getClass().getSimpleName());
            if (cur.getMessage() != null) {
                sb.append(": ").append(cur.getMessage());
            }
            cur = cur.getCause();
        }
        return sb.toString();
    }
}
