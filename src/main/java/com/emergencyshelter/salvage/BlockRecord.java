package com.emergencyshelter.salvage;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.Nullable;

/**
 * 一个区块里所有占位方块的原始数据：位置 → 原方块状态，位置 → 原方块实体数据。
 * 以区块附加数据（NeoForge attachment）的形式随区块一起保存。
 * 相同的方块状态只存一份，后面跟着它所在的全部位置，避免体积过大。
 */
final class BlockRecord {
    final Map<Long, CompoundTag> states = new HashMap<>();
    final Map<Long, CompoundTag> blockEntities = new HashMap<>();
    /** 方块本身还在、但它的数据读取出错被隔离的位置 → 隔离信息（什么时候再试一次）。 */
    final Map<Long, CompoundTag> faults = new HashMap<>();

    static BlockRecord read(@Nullable CompoundTag raw) {
        BlockRecord record = new BlockRecord();
        if (raw == null || raw.isEmpty()) {
            return record;
        }
        ListTag blocks = raw.getList("blocks", Tag.TAG_COMPOUND);
        for (int i = 0; i < blocks.size(); i++) {
            CompoundTag group = blocks.getCompound(i);
            CompoundTag state = group.getCompound("state");
            for (long pos : group.getLongArray("pos")) {
                record.states.put(pos, state);
            }
        }
        ListTag entities = raw.getList("block_entities", Tag.TAG_COMPOUND);
        for (int i = 0; i < entities.size(); i++) {
            CompoundTag entry = entities.getCompound(i);
            record.blockEntities.put(entry.getLong("pos"), entry.getCompound("nbt"));
        }
        ListTag faults = raw.getList("faults", Tag.TAG_COMPOUND);
        for (int i = 0; i < faults.size(); i++) {
            CompoundTag entry = faults.getCompound(i);
            record.faults.put(entry.getLong("pos"), entry.getCompound("fault"));
        }
        return record;
    }

    CompoundTag write() {
        CompoundTag raw = new CompoundTag();
        if (!states.isEmpty()) {
            Map<CompoundTag, LongArrayList> grouped = new LinkedHashMap<>();
            states.forEach((pos, state) -> grouped.computeIfAbsent(state, k -> new LongArrayList()).add((long) pos));
            ListTag blocks = new ListTag();
            grouped.forEach((state, positions) -> {
                CompoundTag group = new CompoundTag();
                group.put("state", state);
                group.put("pos", new LongArrayTag(positions.toLongArray()));
                blocks.add(group);
            });
            raw.put("blocks", blocks);
        }
        if (!blockEntities.isEmpty()) {
            ListTag entities = new ListTag();
            blockEntities.forEach((pos, nbt) -> {
                CompoundTag entry = new CompoundTag();
                entry.putLong("pos", pos);
                entry.put("nbt", nbt);
                entities.add(entry);
            });
            raw.put("block_entities", entities);
        }
        if (!faults.isEmpty()) {
            ListTag list = new ListTag();
            faults.forEach((pos, fault) -> {
                if (states.containsKey(pos)) {
                    CompoundTag entry = new CompoundTag();
                    entry.putLong("pos", pos);
                    entry.put("fault", fault);
                    list.add(entry);
                }
            });
            if (!list.isEmpty()) {
                raw.put("faults", list);
            }
        }
        return raw;
    }

    boolean isEmpty() {
        return states.isEmpty() && blockEntities.isEmpty();
    }
}
