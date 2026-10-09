package com.emergencyshelter.world;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

/**
 * 存档级的隔离状态，保存在主世界的 data/emergencyshelter_world.dat：
 * <ul>
 *     <li>因为运行出错而停止运行的方块实体（方块和数据都还在，只是不再运行）；</li>
 *     <li>手动重试计数：执行 /emergencyshelter quarantine retry 后加一，所有隔离的东西会在下次加载时重新尝试；</li>
 *     <li>卡住导致游戏被强制关闭的实体（下次加载时转为占位实体）和世界生成步骤（以后跳过）。</li>
 * </ul>
 */
public final class ShelterWorldData extends SavedData {
    public static final String NAME = "emergencyshelter_world";
    private static final Factory<ShelterWorldData> FACTORY = new Factory<>(ShelterWorldData::new, ShelterWorldData::load);

    @Nullable
    private static volatile ShelterWorldData cached;

    private int epoch;
    private final Map<ResourceKey<Level>, Long2ObjectOpenHashMap<Frozen>> frozen = new HashMap<>();
    /** 实体 UUID → 卡住的原因。 */
    private final Map<UUID, Marked> hangEntities = new HashMap<>();
    /** {@link WorldgenGuard#skipKey} → 卡住的原因。 */
    private final Map<String, Marked> skipWorldgen = new HashMap<>();

    public record Frozen(String type, String error, long time, String fingerprint) {
    }

    public record Marked(String what, String error, long time, String fingerprint) {
    }

    @Nullable
    public static ShelterWorldData get() {
        ShelterWorldData data = cached;
        if (data != null) {
            return data;
        }
        MinecraftServer server = WorldGuard.server();
        if (server == null || !server.isSameThread()) {
            return null; // 只在服务端主线程上读取（例如客户端收到方块数据时不应碰服务端的存档）
        }
        Level overworld = server.overworld();
        if (overworld == null) {
            return null; // 主世界还没创建
        }
        synchronized (ShelterWorldData.class) {
            if (cached == null) {
                cached = ((net.minecraft.server.level.ServerLevel) overworld).getDataStorage().computeIfAbsent(FACTORY, NAME);
                cached.onLoaded();
            }
            return cached;
        }
    }

    static void resetCache() {
        cached = null;
    }

    public static int epoch() {
        ShelterWorldData data = get();
        return data == null ? 0 : data.epoch;
    }

    /** 模组组合变了（更新、增删模组）：之前停止运行的方块实体、跳过的世界生成步骤重新试一次。 */
    private void onLoaded() {
        String fp = com.emergencyshelter.report.ShelterReport.get().modsFingerprint;
        int removed = 0;
        for (Long2ObjectOpenHashMap<Frozen> map : frozen.values()) {
            int before = map.size();
            map.values().removeIf(f -> fp != null && !fp.equals(f.fingerprint()));
            removed += before - map.size();
        }
        if (removed > 0) {
            setDirty();
            WorldGuard.record("UNFROZEN", String.valueOf(removed), null, "模组有变化，之前停止运行的方块实体重新尝试运行");
        }
        if (skipWorldgen.values().removeIf(m -> fp != null && !fp.equals(m.fingerprint()))) {
            setDirty();
        }
        HangGuard.mirror(this);
    }

    public void retry() {
        epoch++;
        frozen.clear();
        skipWorldgen.clear();
        setDirty();
        HangGuard.mirror(this);
    }

    public void markHangEntity(UUID uuid, String what, String error) {
        hangEntities.put(uuid, new Marked(what, error, System.currentTimeMillis(), currentFingerprint()));
        setDirty();
    }

    public boolean clearHangEntity(UUID uuid) {
        if (hangEntities.remove(uuid) != null) {
            setDirty();
            return true;
        }
        return false;
    }

    public void skipWorldgen(String key, String what, String error) {
        skipWorldgen.put(key, new Marked(what, error, System.currentTimeMillis(), currentFingerprint()));
        setDirty();
    }

    public Map<UUID, Marked> hangEntitiesView() {
        return hangEntities;
    }

    public Map<String, Marked> skipWorldgenView() {
        return skipWorldgen;
    }

    private static String currentFingerprint() {
        String fp = com.emergencyshelter.report.ShelterReport.get().modsFingerprint;
        return fp == null ? "dev" : fp;
    }

    public boolean isFrozen(ResourceKey<Level> dim, BlockPos pos, String type) {
        Long2ObjectOpenHashMap<Frozen> map = frozen.get(dim);
        if (map == null) {
            return false;
        }
        Frozen f = map.get(pos.asLong());
        return f != null && f.type().equals(type);
    }

    public void freeze(ResourceKey<Level> dim, BlockPos pos, String type, String error) {
        String fp = com.emergencyshelter.report.ShelterReport.get().modsFingerprint;
        frozen.computeIfAbsent(dim, k -> new Long2ObjectOpenHashMap<>()).put(pos.asLong(),
                new Frozen(type, error, System.currentTimeMillis(), fp == null ? "dev" : fp));
        setDirty();
    }

    public boolean unfreeze(ResourceKey<Level> dim, BlockPos pos) {
        Long2ObjectOpenHashMap<Frozen> map = frozen.get(dim);
        if (map != null && map.remove(pos.asLong()) != null) {
            setDirty();
            return true;
        }
        return false;
    }

    public List<Map.Entry<String, Frozen>> list() {
        List<Map.Entry<String, Frozen>> out = new ArrayList<>();
        frozen.forEach((dim, map) -> map.forEach((pos, f) -> out.add(Map.entry(WorldGuard.where(dim, BlockPos.of(pos)), f))));
        return out;
    }

    public Map<ResourceKey<Level>, Long2ObjectOpenHashMap<Frozen>> frozenView() {
        return frozen;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("epoch", epoch);
        ListTag list = new ListTag();
        frozen.forEach((dim, map) -> map.forEach((pos, f) -> {
            CompoundTag entry = new CompoundTag();
            entry.putString("dim", dim.location().toString());
            entry.putLong("pos", pos);
            entry.putString("type", f.type());
            entry.putString("error", f.error());
            entry.putLong("time", f.time());
            entry.putString("fp", f.fingerprint());
            list.add(entry);
        }));
        tag.put("frozen", list);
        ListTag hang = new ListTag();
        hangEntities.forEach((uuid, m) -> {
            CompoundTag entry = writeMarked(m);
            entry.putUUID("uuid", uuid);
            hang.add(entry);
        });
        tag.put("hangEntities", hang);
        ListTag skip = new ListTag();
        skipWorldgen.forEach((key, m) -> {
            CompoundTag entry = writeMarked(m);
            entry.putString("key", key);
            skip.add(entry);
        });
        tag.put("skipWorldgen", skip);
        return tag;
    }

    private static ShelterWorldData load(CompoundTag tag, HolderLookup.Provider registries) {
        ShelterWorldData data = new ShelterWorldData();
        data.epoch = tag.getInt("epoch");
        ListTag list = tag.getList("frozen", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            ResourceLocation dim = ResourceLocation.tryParse(entry.getString("dim"));
            if (dim == null) {
                continue;
            }
            data.frozen.computeIfAbsent(ResourceKey.create(Registries.DIMENSION, dim), k -> new Long2ObjectOpenHashMap<>())
                    .put(entry.getLong("pos"), new Frozen(entry.getString("type"), entry.getString("error"), entry.getLong("time"), entry.getString("fp")));
        }
        ListTag hang = tag.getList("hangEntities", Tag.TAG_COMPOUND);
        for (int i = 0; i < hang.size(); i++) {
            CompoundTag entry = hang.getCompound(i);
            if (entry.hasUUID("uuid")) {
                data.hangEntities.put(entry.getUUID("uuid"), readMarked(entry));
            }
        }
        ListTag skip = tag.getList("skipWorldgen", Tag.TAG_COMPOUND);
        for (int i = 0; i < skip.size(); i++) {
            CompoundTag entry = skip.getCompound(i);
            data.skipWorldgen.put(entry.getString("key"), readMarked(entry));
        }
        return data;
    }

    private static CompoundTag writeMarked(Marked m) {
        CompoundTag entry = new CompoundTag();
        entry.putString("what", m.what());
        entry.putString("error", m.error());
        entry.putLong("time", m.time());
        entry.putString("fp", m.fingerprint());
        return entry;
    }

    private static Marked readMarked(CompoundTag entry) {
        return new Marked(entry.getString("what"), entry.getString("error"), entry.getLong("time"), entry.getString("fp"));
    }
}
