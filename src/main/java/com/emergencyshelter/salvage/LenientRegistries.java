package com.emergencyshelter.salvage;

import com.emergencyshelter.EmergencyShelter;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.io.BufferedReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

/**
 * 数据包宽松加载：原版只要有一个世界生成条目读不出来（例如引用了被删模组的矿石），整个世界就打不开。
 * 这里在失败时找出出问题的条目和引用了缺失内容的条目，跳过它们重新加载，直到成功或无法再修复。
 */
public final class LenientRegistries {
    private static final int MAX_ATTEMPTS = 8;
    private static final Pattern UNBOUND = Pattern.compile("Unbound values in registry ResourceKey\\[minecraft:root / ([^\\]]+)\\]: \\[([^\\]]*)\\]");
    private static final ThreadLocal<Map<ResourceKey<?>, Exception>> CAPTURED = new ThreadLocal<>();
    private static final List<String> SKIPPED = Collections.synchronizedList(new ArrayList<>());

    private LenientRegistries() {
    }

    /** 本次运行中被跳过的数据包条目（给报告用）。 */
    public static List<String> skipped() {
        synchronized (SKIPPED) {
            return List.copyOf(SKIPPED);
        }
    }

    public static void captureErrors(Map<ResourceKey<?>, Exception> errors) {
        CAPTURED.set(new LinkedHashMap<>(errors));
    }

    public static RegistryAccess.Frozen load(ResourceManager resourceManager, RegistryAccess access,
                                             List<RegistryDataLoader.RegistryData<?>> data,
                                             Operation<RegistryAccess.Frozen> original) {
        Set<ResourceLocation> excluded = new LinkedHashSet<>();
        for (int attempt = 0; ; attempt++) {
            CAPTURED.remove();
            try {
                ResourceManager manager = excluded.isEmpty() ? resourceManager : new FilteringResourceManager(resourceManager, excluded);
                RegistryAccess.Frozen result = original.call(manager, access, data);
                if (!excluded.isEmpty()) {
                    for (ResourceLocation file : excluded) {
                        SKIPPED.add(file.toString());
                    }
                    EmergencyShelter.LOGGER.warn("[紧急避险] 跳过 {} 个引用了缺失内容的数据包条目后，注册表加载成功：{}", excluded.size(), excluded);
                }
                return result;
            } catch (IllegalStateException e) {
                Map<ResourceKey<?>, Exception> errors = CAPTURED.get();
                CAPTURED.remove();
                if (errors == null || errors.isEmpty() || attempt >= MAX_ATTEMPTS) {
                    throw e;
                }
                Set<ResourceLocation> more = findCulprits(resourceManager, data, errors);
                more.removeAll(excluded);
                if (more.isEmpty()) {
                    EmergencyShelter.LOGGER.error("[紧急避险] 注册表加载失败，且无法自动定位到出问题的数据包条目");
                    throw e;
                }
                EmergencyShelter.LOGGER.warn("[紧急避险] 数据包加载失败（第 {} 次），将跳过这些条目后重试：{}", attempt + 1, more);
                excluded.addAll(more);
            }
        }
    }

    private static Set<ResourceLocation> findCulprits(ResourceManager manager, List<RegistryDataLoader.RegistryData<?>> data,
                                                      Map<ResourceKey<?>, Exception> errors) {
        Set<ResourceLocation> culprits = new LinkedHashSet<>();
        Set<String> unboundIds = new HashSet<>();
        for (Map.Entry<ResourceKey<?>, Exception> entry : errors.entrySet()) {
            ResourceKey<?> key = entry.getKey();
            boolean isRegistryLevel = key.registry().equals(Registries.ROOT_REGISTRY_NAME);
            if (!isRegistryLevel) {
                // 单个条目解析失败：直接跳过这个文件
                ResourceKey<? extends Registry<?>> registry = ResourceKey.createRegistryKey(key.registry());
                culprits.add(key.location().withPath(Registries.elementsDirPath(registry) + "/" + key.location().getPath() + ".json"));
                continue;
            }
            for (Throwable t = entry.getValue(); t != null; t = t.getCause()) {
                if (t.getMessage() == null) {
                    continue;
                }
                Matcher m = UNBOUND.matcher(t.getMessage());
                while (m.find()) {
                    for (String id : m.group(2).split(",")) {
                        if (!id.isBlank()) {
                            unboundIds.add(id.trim());
                        }
                    }
                }
            }
        }
        if (!unboundIds.isEmpty()) {
            // 找出所有引用了这些缺失 id 的条目文件
            Set<String> needles = unboundIds.stream().map(id -> "\"" + id + "\"").collect(Collectors.toSet());
            for (RegistryDataLoader.RegistryData<?> registryData : data) {
                String dir = Registries.elementsDirPath(registryData.key());
                for (Map.Entry<ResourceLocation, Resource> res : manager.listResources(dir, p -> p.getPath().endsWith(".json")).entrySet()) {
                    String text;
                    try (BufferedReader reader = res.getValue().openAsReader()) {
                        text = reader.lines().collect(Collectors.joining("\n"));
                    } catch (Exception ex) {
                        continue;
                    }
                    for (String needle : needles) {
                        if (text.contains(needle)) {
                            culprits.add(res.getKey());
                            break;
                        }
                    }
                }
            }
        }
        return culprits;
    }

    /** 隐藏指定文件的资源管理器包装。 */
    private record FilteringResourceManager(ResourceManager delegate, Set<ResourceLocation> excluded) implements ResourceManager {
        @Override
        public Set<String> getNamespaces() {
            return delegate.getNamespaces();
        }

        @Override
        public List<Resource> getResourceStack(ResourceLocation location) {
            return excluded.contains(location) ? List.of() : delegate.getResourceStack(location);
        }

        @Override
        public Map<ResourceLocation, Resource> listResources(String path, Predicate<ResourceLocation> filter) {
            return delegate.listResources(path, filter.and(l -> !excluded.contains(l)));
        }

        @Override
        public Map<ResourceLocation, List<Resource>> listResourceStacks(String path, Predicate<ResourceLocation> filter) {
            return delegate.listResourceStacks(path, filter.and(l -> !excluded.contains(l)));
        }

        @Override
        public Stream<PackResources> listPacks() {
            return delegate.listPacks();
        }

        @Override
        public Optional<Resource> getResource(ResourceLocation location) {
            return excluded.contains(location) ? Optional.empty() : delegate.getResource(location);
        }
    }
}
