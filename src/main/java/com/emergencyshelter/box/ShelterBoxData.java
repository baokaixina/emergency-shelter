package com.emergencyshelter.box;

import com.emergencyshelter.EmergencyShelter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;

/**
 * 避险箱：每个玩家一份，保存在主世界的 data/emergencyshelter_box.dat。
 * 读取时会经过物品编解码器，所以模组回来后箱子里的占位物品会自动还原。
 */
public final class ShelterBoxData extends SavedData {
    public static final String NAME = "emergencyshelter_box";
    private static final Factory<ShelterBoxData> FACTORY = new Factory<>(ShelterBoxData::new, ShelterBoxData::load);

    private final Map<UUID, List<ItemStack>> boxes = new HashMap<>();
    /** 读不出来的物品数据：原样保存，不能因为读不出来就从箱子里丢掉。 */
    private final Map<UUID, ListTag> unreadable = new HashMap<>();

    public static ShelterBoxData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, NAME);
    }

    public List<ItemStack> items(UUID player) {
        return boxes.computeIfAbsent(player, k -> new ArrayList<>());
    }

    public void add(UUID player, ItemStack stack) {
        if (!stack.isEmpty()) {
            items(player).add(stack);
            setDirty();
        }
    }

    /** 用这一页的当前内容替换列表里 [start, start + length) 这一段，返回写回的物品数（新的 length）。 */
    public int writeBack(UUID player, int start, int length, List<ItemStack> pageContents) {
        List<ItemStack> list = items(player);
        int end = Math.min(list.size(), start + length);
        List<ItemStack> rebuilt = new ArrayList<>(list.subList(0, Math.min(start, list.size())));
        int written = 0;
        for (ItemStack stack : pageContents) {
            if (!stack.isEmpty()) {
                rebuilt.add(stack);
                written++;
            }
        }
        if (end < list.size()) {
            rebuilt.addAll(list.subList(end, list.size()));
        }
        list.clear();
        list.addAll(rebuilt);
        setDirty();
        return written;
    }

    /** 有改动时立即写盘（和自动保存写的是同一个文件）。 */
    public void saveNow(MinecraftServer server) {
        if (!isDirty()) {
            return;
        }
        try {
            Path dir = server.getWorldPath(LevelResource.ROOT).resolve("data");
            Files.createDirectories(dir);
            save(dir.resolve(NAME + ".dat").toFile(), server.registryAccess());
        } catch (Throwable t) {
            EmergencyShelter.LOGGER.warn("[紧急避险] 无法立即保存避险箱，等待下次自动保存", t);
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        CompoundTag players = new CompoundTag();
        boxes.forEach((uuid, list) -> {
            ListTag items = new ListTag();
            for (ItemStack stack : list) {
                if (!stack.isEmpty()) {
                    items.add(stack.save(registries));
                }
            }
            if (!items.isEmpty()) {
                players.put(uuid.toString(), items);
            }
        });
        unreadable.forEach((uuid, raw) -> {
            ListTag items = players.getList(uuid.toString(), Tag.TAG_COMPOUND);
            raw.forEach(t -> items.add(t.copy()));
            players.put(uuid.toString(), items);
        });
        tag.put("players", players);
        return tag;
    }

    private static ShelterBoxData load(CompoundTag tag, HolderLookup.Provider registries) {
        ShelterBoxData data = new ShelterBoxData();
        CompoundTag players = tag.getCompound("players");
        for (String key : players.getAllKeys()) {
            UUID uuid;
            try {
                uuid = UUID.fromString(key);
            } catch (IllegalArgumentException e) {
                continue;
            }
            List<ItemStack> list = new ArrayList<>();
            ListTag items = players.getList(key, Tag.TAG_COMPOUND);
            for (int i = 0; i < items.size(); i++) {
                CompoundTag raw = items.getCompound(i);
                ItemStack stack = ItemStack.parseOptional(registries, raw);
                if (!stack.isEmpty()) {
                    list.add(stack);
                } else if (!raw.getString("id").isEmpty() && !raw.getString("id").equals("minecraft:air") && raw.getInt("count") > 0) {
                    data.unreadable.computeIfAbsent(uuid, k -> new ListTag()).add(raw);
                }
            }
            data.boxes.put(uuid, list);
        }
        return data;
    }
}
