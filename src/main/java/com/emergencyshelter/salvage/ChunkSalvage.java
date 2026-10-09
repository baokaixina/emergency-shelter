package com.emergencyshelter.salvage;

import com.emergencyshelter.EmergencyShelter;
import com.emergencyshelter.registry.ShelterRegistries;
import com.emergencyshelter.world.ChunkRescue;
import com.emergencyshelter.world.WorldGuard;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * 在原版解析区块 NBT 之前处理它：
 * <ul>
 *     <li>调色板里不存在的方块 → 换成占位方块，原状态记进区块附加数据；对应的方块实体数据一起记下；</li>
 *     <li>占位方块的原方块已经回来了 → 换回原方块，方块实体数据放回去。</li>
 * </ul>
 * 只改 NBT，不碰任何游戏对象，所以与其它修改区块加载的模组互不干扰。
 */
public final class ChunkSalvage {
    public static final String ATTACHMENTS_KEY = "neoforge:attachments";
    public static final String RECORD_KEY = EmergencyShelter.MODID + ":placeholder_blocks";
    private static final int SECTION_SIZE = 4096;

    private static final Map<String, Boolean> BLOCK_EXISTS = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> BLOCK_ENTITY_EXISTS = new ConcurrentHashMap<>();

    private ChunkSalvage() {
    }

    static String placeholderId() {
        return BuiltInRegistries.BLOCK.getKey(ShelterRegistries.PLACEHOLDER_BLOCK.get()).toString();
    }

    static boolean blockExists(String name) {
        return BLOCK_EXISTS.computeIfAbsent(name, n -> {
            ResourceLocation id = ResourceLocation.tryParse(n);
            return id != null && BuiltInRegistries.BLOCK.containsKey(id);
        });
    }

    /**
     * 方块实体 id 格式合法、但对应的类型不存在（模组不在了）。
     * 格式本身不合法的 id（例如某些模组写入的 "DUMMY"）不归我们管，保持原版行为。
     */
    static boolean blockEntityMissing(String name) {
        return !BLOCK_ENTITY_EXISTS.computeIfAbsent(name, n -> {
            ResourceLocation id = ResourceLocation.tryParse(n);
            return id == null || BuiltInRegistries.BLOCK_ENTITY_TYPE.containsKey(id);
        });
    }

    public static void preprocess(ChunkPos chunkPos, CompoundTag tag) {
        if (!ShelterRegistries.PLACEHOLDER_BLOCK.isBound()) {
            return;
        }
        try {
            doPreprocess(chunkPos, tag);
        } catch (Throwable t) {
            EmergencyShelter.LOGGER.error("[紧急避险] 处理区块 {} 时出错，按原版方式加载", chunkPos, t);
        }
    }

    private static void doPreprocess(ChunkPos chunkPos, CompoundTag tag) {
        ChunkRescue.mergeStashBack(chunkPos, tag);
        CompoundTag attachments = tag.getCompound(ATTACHMENTS_KEY);
        CompoundTag rawRecord = attachments.contains(RECORD_KEY, Tag.TAG_COMPOUND) ? attachments.getCompound(RECORD_KEY) : null;
        BlockRecord record = BlockRecord.read(rawRecord);
        String placeholder = placeholderId();
        boolean changed = false;
        Set<Long> restored = new HashSet<>();
        Set<String> retried = new HashSet<>();

        Map<Integer, List<Long>> recordedBySection = new HashMap<>();
        for (long pos : record.states.keySet()) {
            recordedBySection.computeIfAbsent(SectionPos.blockToSectionCoord(BlockPos.getY(pos)), k -> new ArrayList<>()).add(pos);
        }

        ListTag sections = tag.getList("sections", Tag.TAG_COMPOUND);
        for (int s = 0; s < sections.size(); s++) {
            CompoundTag section = sections.getCompound(s);
            int sectionY = section.getByte("Y");
            List<Long> recorded = recordedBySection.getOrDefault(sectionY, List.of());
            if (!section.contains("block_states", Tag.TAG_COMPOUND)) {
                if (!recorded.isEmpty()) {
                    recorded.forEach(record.states::remove);
                    changed = true;
                }
                continue;
            }
            CompoundTag blockStates = section.getCompound("block_states");
            ListTag palette = blockStates.getList("palette", Tag.TAG_COMPOUND);
            int n = palette.size();
            if (n == 0) {
                continue;
            }
            boolean[] unknown = new boolean[n];
            boolean[] isPlaceholder = new boolean[n];
            boolean anyUnknown = false;
            boolean anyPlaceholder = false;
            for (int i = 0; i < n; i++) {
                String name = palette.getCompound(i).getString("Name");
                if (name.equals(placeholder)) {
                    isPlaceholder[i] = true;
                    anyPlaceholder = true;
                } else if (!blockExists(name)) {
                    unknown[i] = true;
                    anyUnknown = true;
                }
            }
            if (!anyUnknown && !anyPlaceholder) {
                if (!recorded.isEmpty()) {
                    // 这一段里已经没有占位方块了（被玩家拆掉或替换），清理过期记录
                    recorded.forEach(record.states::remove);
                    changed = true;
                }
                continue;
            }
            int[] indices = unpack(blockStates, n);
            if (indices == null || !allBelow(indices, n)) {
                continue; // 数据异常，这一段交给原版处理
            }

            List<CompoundTag> newPalette = new ArrayList<>();
            Map<CompoundTag, Integer> newIndex = new HashMap<>();
            CompoundTag placeholderState = new CompoundTag();
            placeholderState.putString("Name", placeholder);
            int[] remap = new int[n];
            for (int i = 0; i < n; i++) {
                remap[i] = unknown[i] || isPlaceholder[i] ? -1 : indexFor(palette.getCompound(i), newPalette, newIndex);
            }
            Set<Long> stillPlaceholder = new HashSet<>();
            for (int i = 0; i < SECTION_SIZE; i++) {
                int old = indices[i];
                long pos = BlockPos.asLong(chunkPos.getMinBlockX() + (i & 15), SectionPos.sectionToBlockCoord(sectionY) + (i >> 8),
                        chunkPos.getMinBlockZ() + ((i >> 4) & 15));
                if (unknown[old]) {
                    CompoundTag original = palette.getCompound(old).copy();
                    record.states.put(pos, original);
                    indices[i] = indexFor(placeholderState, newPalette, newIndex);
                    stillPlaceholder.add(pos);
                    SalvageStats.placeholder("方块", original.getString("Name"));
                } else if (isPlaceholder[old]) {
                    CompoundTag original = record.states.get(pos);
                    CompoundTag fault = record.faults.get(pos);
                    if (original != null && blockExists(original.getString("Name")) && (fault == null || WorldGuard.shouldRetry(fault))) {
                        indices[i] = indexFor(original, newPalette, newIndex);
                        record.states.remove(pos);
                        record.faults.remove(pos);
                        restored.add(pos);
                        if (fault == null) {
                            SalvageStats.restored("方块", original.getString("Name"));
                        } else {
                            retried.add(original.getString("Name"));
                        }
                    } else {
                        indices[i] = indexFor(placeholderState, newPalette, newIndex);
                        stillPlaceholder.add(pos);
                    }
                } else {
                    indices[i] = remap[old];
                }
            }
            for (long pos : recorded) {
                if (!stillPlaceholder.contains(pos) && !restored.contains(pos)) {
                    record.states.remove(pos); // 过期记录
                }
            }
            ListTag paletteTag = new ListTag();
            paletteTag.addAll(newPalette);
            CompoundTag newBlockStates = new CompoundTag();
            newBlockStates.put("palette", paletteTag);
            long[] data = pack(indices, newPalette.size());
            if (data != null) {
                newBlockStates.put("data", new LongArrayTag(data));
            }
            section.put("block_states", newBlockStates);
            changed = true;
        }

        // 方块实体
        if (tag.contains("block_entities", Tag.TAG_LIST) || !record.blockEntities.isEmpty()) {
            ListTag blockEntities = tag.getList("block_entities", Tag.TAG_COMPOUND);
            ListTag kept = new ListTag();
            for (int i = 0; i < blockEntities.size(); i++) {
                CompoundTag be = blockEntities.getCompound(i);
                long pos = BlockPos.asLong(be.getInt("x"), be.getInt("y"), be.getInt("z"));
                String id = be.getString("id");
                if (record.states.containsKey(pos) || blockEntityMissing(id)) {
                    record.blockEntities.put(pos, be.copy());
                    changed = true;
                    if (blockEntityMissing(id)) {
                        SalvageStats.placeholder("方块实体", id);
                    }
                } else {
                    kept.add(be);
                }
            }
            for (long pos : new ArrayList<>(record.blockEntities.keySet())) {
                if (record.states.containsKey(pos)) {
                    continue; // 方块还是占位，数据继续保存
                }
                CompoundTag be = record.blockEntities.get(pos);
                if (!blockEntityMissing(be.getString("id"))) {
                    kept.add(be);
                    record.blockEntities.remove(pos);
                    changed = true;
                }
            }
            if (changed) {
                tag.put("block_entities", kept);
            }
        }

        for (String id : retried) {
            EmergencyShelter.LOGGER.info("[紧急避险] 区块 {} 中之前隔离的 {} 重新尝试加载", chunkPos, id);
        }
        if (!changed && rawRecord == null) {
            return;
        }
        if (record.isEmpty()) {
            attachments.remove(RECORD_KEY);
        } else {
            attachments.put(RECORD_KEY, record.write());
        }
        if (attachments.isEmpty()) {
            tag.remove(ATTACHMENTS_KEY);
        } else {
            tag.put(ATTACHMENTS_KEY, attachments);
        }
    }

    private static boolean allBelow(int[] indices, int n) {
        for (int v : indices) {
            if (v < 0 || v >= n) {
                return false;
            }
        }
        return true;
    }

    private static int indexFor(CompoundTag state, List<CompoundTag> palette, Map<CompoundTag, Integer> index) {
        return index.computeIfAbsent(state, k -> {
            palette.add(k);
            return palette.size() - 1;
        });
    }

    /** 与原版存档格式一致：调色板大小 ≤1 时没有数据，否则每格 max(4, ceil(log2(大小))) 位，不跨 long。 */
    static int bitsFor(int paletteSize) {
        if (paletteSize <= 1) {
            return 0;
        }
        return Math.max(4, Mth.ceillog2(paletteSize));
    }

    @org.jetbrains.annotations.Nullable
    static int[] unpack(CompoundTag blockStates, int paletteSize) {
        int bits = bitsFor(paletteSize);
        int[] out = new int[SECTION_SIZE];
        if (bits == 0) {
            return out;
        }
        long[] data = blockStates.getLongArray("data");
        int perLong = 64 / bits;
        int expected = (SECTION_SIZE + perLong - 1) / perLong;
        if (data.length != expected) {
            return null;
        }
        long mask = (1L << bits) - 1L;
        for (int i = 0; i < SECTION_SIZE; i++) {
            int cell = i / perLong;
            int offset = (i - cell * perLong) * bits;
            out[i] = (int) ((data[cell] >>> offset) & mask);
        }
        return out;
    }

    @org.jetbrains.annotations.Nullable
    static long[] pack(int[] indices, int paletteSize) {
        int bits = bitsFor(paletteSize);
        if (bits == 0) {
            return null;
        }
        int perLong = 64 / bits;
        long[] data = new long[(SECTION_SIZE + perLong - 1) / perLong];
        long mask = (1L << bits) - 1L;
        for (int i = 0; i < SECTION_SIZE; i++) {
            int cell = i / perLong;
            int offset = (i - cell * perLong) * bits;
            data[cell] |= ((long) indices[i] & mask) << offset;
        }
        return data;
    }

    // ------------------------------------------------------------------ 运行时：拆除占位方块

    /** 从区块记录里取出某个位置的原始数据（并从记录中删除）。 */
    @org.jetbrains.annotations.Nullable
    static CompoundTag[] take(LevelChunk chunk, BlockPos pos) {
        if (!chunk.hasData(ShelterRegistries.PLACEHOLDER_BLOCKS)) {
            return null;
        }
        CompoundTag raw = chunk.getData(ShelterRegistries.PLACEHOLDER_BLOCKS);
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        BlockRecord record = BlockRecord.read(raw);
        long key = pos.asLong();
        CompoundTag state = record.states.remove(key);
        CompoundTag be = record.blockEntities.remove(key);
        if (state == null && be == null) {
            return null;
        }
        chunk.setData(ShelterRegistries.PLACEHOLDER_BLOCKS, record.write());
        chunk.setUnsaved(true);
        return new CompoundTag[]{state, be};
    }

    /**
     * 方块实体数据读取出错：把方块换成占位方块，原方块状态和方块实体数据存进区块记录（原版会直接丢掉这些数据）。
     * 直接修改区块数据，不触发原方块的任何逻辑。模组有变化或手动重试后，下次加载区块时自动换回去再试。
     */
    public static void quarantineBlock(LevelChunk chunk, BlockPos pos, BlockState original, @org.jetbrains.annotations.Nullable CompoundTag blockEntity,
                                       CompoundTag fault) {
        CompoundTag raw = chunk.hasData(ShelterRegistries.PLACEHOLDER_BLOCKS) ? chunk.getData(ShelterRegistries.PLACEHOLDER_BLOCKS) : null;
        BlockRecord record = BlockRecord.read(raw);
        long key = pos.asLong();
        record.states.put(key, NbtUtils.writeBlockState(original));
        if (blockEntity != null) {
            record.blockEntities.put(key, blockEntity.copy());
        }
        record.faults.put(key, fault);
        chunk.setData(ShelterRegistries.PLACEHOLDER_BLOCKS, record.write());

        BlockState placeholder = ShelterRegistries.PLACEHOLDER_BLOCK.get().defaultBlockState();
        LevelChunkSection section = chunk.getSection(chunk.getSectionIndex(pos.getY()));
        section.setBlockState(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15, placeholder, false);
        for (Map.Entry<Heightmap.Types, Heightmap> e : chunk.getHeightmaps()) {
            e.getValue().update(pos.getX() & 15, pos.getY(), pos.getZ() & 15, placeholder);
        }
        chunk.setUnsaved(true);
        Level level = chunk.getLevel();
        if (level != null && !level.isClientSide) {
            level.getChunkSource().getLightEngine().checkBlock(pos);
            level.sendBlockUpdated(pos, original, placeholder, Block.UPDATE_CLIENTS);
        }
    }

    @org.jetbrains.annotations.Nullable
    static CompoundTag peekFault(LevelChunk chunk, BlockPos pos) {
        if (!chunk.hasData(ShelterRegistries.PLACEHOLDER_BLOCKS)) {
            return null;
        }
        return BlockRecord.read(chunk.getData(ShelterRegistries.PLACEHOLDER_BLOCKS)).faults.get(pos.asLong());
    }

    @org.jetbrains.annotations.Nullable
    static CompoundTag peekState(LevelChunk chunk, BlockPos pos) {
        if (!chunk.hasData(ShelterRegistries.PLACEHOLDER_BLOCKS)) {
            return null;
        }
        CompoundTag raw = chunk.getData(ShelterRegistries.PLACEHOLDER_BLOCKS);
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        return BlockRecord.read(raw).states.get(pos.asLong());
    }
}
