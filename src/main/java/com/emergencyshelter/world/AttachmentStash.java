package com.emergencyshelter.world;

import com.emergencyshelter.EmergencyShelter;
import com.emergencyshelter.salvage.SalvageStats;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import org.jetbrains.annotations.Nullable;

/**
 * NeoForge 的附加数据（模组挂在玩家、实体、方块实体、区块上的数据，例如饰品栏、技能、魔力）在两种情况下会被直接丢弃：
 * 所属模组不在了，或者读取时出错。存盘后就永久消失了。
 * 这里把它们原样暂存在持有者身上，存盘时写回去：模组回来、或者修好之后（模组有变化或手动重试时会再试一次），数据还在。
 */
public final class AttachmentStash {
    /** 读取出错、而模组又重新创建了同名数据时，原始数据存在这里。 */
    public static final String BACKUP_KEY = EmergencyShelter.MODID + ":attachment_backup";
    private static final Set<String> LOGGED = ConcurrentHashMap.newKeySet();

    private AttachmentStash() {
    }

    /** 由 mixin 加到 NeoForge 的 AttachmentHolder 上。 */
    public interface Holder {
        @Nullable
        CompoundTag emergencyshelter$getStash();

        void emergencyshelter$setStash(@Nullable CompoundTag stash);
    }

    /** 读取之前：记下不认识的附加数据；之前读取出错而备份起来的数据，满足重试条件时放回去再试。 */
    public static void beforeDeserialize(Holder holder, CompoundTag tag) {
        holder.emergencyshelter$setStash(null);
        if (tag.contains(BACKUP_KEY, Tag.TAG_COMPOUND)) {
            CompoundTag backup = tag.getCompound(BACKUP_KEY);
            for (String key : new java.util.ArrayList<>(backup.getAllKeys())) {
                CompoundTag entry = backup.getCompound(key);
                if (entry.contains("data") && WorldGuard.shouldRetry(entry.getCompound("fault"))) {
                    tag.put(key, entry.get("data").copy()); // 原始数据比出错后重新开始的数据更重要
                    backup.remove(key);
                }
            }
            if (backup.isEmpty()) {
                tag.remove(BACKUP_KEY);
            }
        }
        for (String key : tag.getAllKeys()) {
            ResourceLocation id = ResourceLocation.tryParse(key);
            if (id == null) {
                continue;
            }
            AttachmentType<?> type = NeoForgeRegistries.ATTACHMENT_TYPES.get(id);
            if (type == null) {
                stash(holder, "unknown", key, tag.get(key));
                if (LOGGED.add("unknown " + key)) {
                    SalvageStats.placeholder("附加数据", key);
                }
            }
        }
    }

    /** 读取某一项时出错。 */
    public static void failed(Holder holder, String key, @Nullable Tag data, Throwable error) {
        if (data == null) {
            return;
        }
        stash(holder, "failed", key, data);
        if (LOGGED.add("failed " + key)) {
            EmergencyShelter.LOGGER.warn("[紧急避险] 附加数据 {} 读取出错，原始数据已保留（之后同类不再提示）：{}", key, WorldGuard.message(error));
        }
    }

    private static void stash(Holder holder, String group, String key, @Nullable Tag data) {
        if (data == null) {
            return;
        }
        CompoundTag stash = holder.emergencyshelter$getStash();
        if (stash == null) {
            stash = new CompoundTag();
            holder.emergencyshelter$setStash(stash);
        }
        CompoundTag part = stash.getCompound(group);
        part.put(key, data.copy());
        stash.put(group, part);
    }

    /** 写出时：把暂存的数据放回去。 */
    @Nullable
    public static CompoundTag merge(Holder holder, @Nullable CompoundTag result) {
        CompoundTag stash = holder.emergencyshelter$getStash();
        if (stash == null || stash.isEmpty()) {
            return result;
        }
        CompoundTag out = result == null ? new CompoundTag() : result;
        CompoundTag unknown = stash.getCompound("unknown");
        for (String key : unknown.getAllKeys()) {
            if (!out.contains(key)) {
                out.put(key, unknown.get(key).copy());
            }
        }
        CompoundTag failed = stash.getCompound("failed");
        for (String key : failed.getAllKeys()) {
            if (!out.contains(key)) {
                out.put(key, failed.get(key).copy()); // 模组没有重新创建它：原样写回，下次读取时再试
            } else {
                // 模组已经重新创建了它：现在的数据照常保存，原始数据另外备份，模组有变化或手动重试时再放回去
                CompoundTag backup = out.getCompound(BACKUP_KEY);
                CompoundTag entry = new CompoundTag();
                entry.put("data", failed.get(key).copy());
                entry.put("fault", WorldGuard.faultTag("ATTACHMENT", new IllegalStateException("读取出错")));
                backup.put(key, entry);
                out.put(BACKUP_KEY, backup);
            }
        }
        return out.isEmpty() ? result : out;
    }

    /** 复制附加数据时（区块升级为完整区块、玩家重生或换维度）连同暂存的一起复制。 */
    public static void copy(Object fromHolder, Object toHolder) {
        if (!(fromHolder instanceof Holder from) || !(toHolder instanceof Holder to)) {
            return;
        }
        CompoundTag stash = from.emergencyshelter$getStash();
        if (stash != null && !stash.isEmpty()) {
            CompoundTag existing = to.emergencyshelter$getStash();
            if (existing == null) {
                to.emergencyshelter$setStash(stash.copy());
            } else {
                for (String group : stash.getAllKeys()) {
                    CompoundTag part = existing.getCompound(group);
                    part.merge(stash.getCompound(group));
                    existing.put(group, part);
                }
            }
        }
    }
}
