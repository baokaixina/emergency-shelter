package com.emergencyshelter.world;

import com.emergencyshelter.EmergencyShelter;
import com.emergencyshelter.registry.ShelterRegistries;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;

/**
 * 区块读取出错时的抢救。原版会把这个区块当成从没生成过，重新生成地形，原来的建筑和东西在下次保存时被永久覆盖。
 * <ol>
 *     <li>先把原始数据存进隔离目录；</li>
 *     <li>去掉模组附加在区块上的数据再读一次：多数情况下是某个模组读自己的数据时出错，地形和建筑本身没问题。
 *         去掉的数据暂存在区块上，模组有变化或手动重试后自动放回去再试；</li>
 *     <li>仍然不行才交给原版重新生成，并把整个区域文件备份一份。</li>
 * </ol>
 */
public final class ChunkRescue {
    public static final String STASH_KEY = EmergencyShelter.MODID + ":stashed_chunk_data";
    private static final String ATTACHMENTS = "neoforge:attachments";
    /** 原版区块数据里的字段，其余的都是模组加的。 */
    private static final Set<String> VANILLA_KEYS = Set.of("DataVersion", "xPos", "yPos", "zPos", "LastUpdate", "InhabitedTime", "Status",
            "sections", "block_entities", "Heightmaps", "fluid_ticks", "block_ticks", "PostProcessing", "structures", "isLightOn",
            "blending_data", "below_zero_retrogen", "UpgradeData", "CarvingMasks", "Lights", "entities", "shouldSave", ATTACHMENTS);

    /** 正在用去掉模组数据的版本重试：这时区块加载事件里的错误不再让整个区块失败。 */
    private static final ThreadLocal<Boolean> RETRYING = ThreadLocal.withInitial(() -> false);

    private ChunkRescue() {
    }

    public static ProtoChunk read(ServerLevel level, PoiManager poi, RegionStorageInfo info, ChunkPos pos, CompoundTag tag,
                                  Operation<ProtoChunk> original) {
        try {
            return original.call(level, poi, info, pos, tag);
        } catch (Throwable error) {
            try {
                return rescue(level, poi, info, pos, tag, original, error);
            } catch (Throwable own) {
                // 处理过程中紧急避险自己出错：按原版处理（原版会重新生成这个区块）
                throw com.emergencyshelter.Defense.fallback(error, own);
            }
        }
    }

    private static ProtoChunk rescue(ServerLevel level, PoiManager poi, RegionStorageInfo info, ChunkPos pos, CompoundTag tag,
                                     Operation<ProtoChunk> original, Throwable error) {
        if (!WorldGuard.enabled() || error instanceof OutOfMemoryError) {
            throw WorldGuard.sneakyThrow(error);
        }
        // 原版读取时不会修改这份数据，出错时它仍然是完整的
        CompoundTag pristine = tag.copy();
        String dim = level.dimension().location().toString().replace(':', '_');
        String saved = WorldGuard.quarantineNbt("chunks/" + dim + "/c." + pos.x + "." + pos.z + ".nbt", pristine);
        String where = level.dimension().location() + " " + pos.getMiddleBlockX() + ", ~, " + pos.getMiddleBlockZ();

        // 先只去掉模组加在区块上的字段，不行再连同模组的附加数据一起去掉
        CompoundTag stripped = pristine.copy();
        CompoundTag stash = new CompoundTag();
        for (int step = 1; step <= 2; step++) {
            boolean removed = strip(stripped, stash, step == 2);
            if (step == 1 && !removed) {
                continue;
            }
            RETRYING.set(step == 2);
            ProtoChunk chunk;
            try {
                chunk = original.call(level, poi, info, pos, stripped.copy());
            } catch (Throwable retry) {
                error.addSuppressed(retry);
                continue;
            } finally {
                RETRYING.set(false);
            }
            // 已经读出来了：暂存、记录出错也必须把读到的区块交出去（去掉的数据在隔离目录里还有一份）
            com.emergencyshelter.Defense.quietly("ChunkRescue.stash", () -> {
                if (!stash.isEmpty()) {
                    stash.put("fault", WorldGuard.faultTag("CHUNK_DATA", error));
                    CompoundTag previous = chunk.getExistingDataOrNull(ShelterRegistries.STASHED_CHUNK_DATA.get());
                    chunk.setData(ShelterRegistries.STASHED_CHUNK_DATA.get(), mergeStash(previous, stash));
                    EmergencyShelter.LOGGER.error("[紧急避险] 读取区块 {} 时出错（原版会重新生成这个区块），去掉模组附加的数据 {} 后读取成功，这些数据已暂存",
                            pos, describe(stash), error);
                    WorldGuard.record("CHUNK_DATA_STASHED", describe(stash), where, WorldGuard.message(error));
                } else {
                    WorldGuard.record("CHUNK_EVENT_SKIPPED", WorldGuard.message(error), where, WorldGuard.message(error));
                }
            });
            return chunk;
        }
        EmergencyShelter.LOGGER.error("[紧急避险] 区块 {} 无法读取，原版会重新生成它。原始数据已保存到 {}", pos, saved, error);
        WorldGuard.record("CHUNK_LOST", saved == null ? "?" : saved, where, WorldGuard.message(error));
        throw WorldGuard.sneakyThrow(error);
    }

    /**
     * 区块加载事件（模组读取自己的区块数据）中出错。第一次照常抛出，让上面去掉模组数据重试；
     * 重试时仍然出错（与数据无关的错误），就只跳过这个事件，不再为此放弃整个区块。
     */
    public static void onLoadEventFailure(net.neoforged.bus.api.Event event, Throwable error) {
        if (!RETRYING.get() || error instanceof OutOfMemoryError || !(event instanceof net.neoforged.neoforge.event.level.ChunkDataEvent.Load load)) {
            throw WorldGuard.sneakyThrow(error);
        }
        ChunkPos pos = load.getChunk().getPos();
        String dimension = load.getLevel() instanceof net.minecraft.world.level.Level level ? level.dimension().location() + " " : "";
        EmergencyShelter.LOGGER.error("[紧急避险] 区块 {} 的加载事件中有模组出错，已跳过（原版会重新生成这个区块）", pos, error);
        WorldGuard.record("CHUNK_EVENT_SKIPPED", WorldGuard.message(error), dimension + pos.getMiddleBlockX()
                + ", ~, " + pos.getMiddleBlockZ(), WorldGuard.message(error));
    }

    /** 原版决定放弃这个区块（重新生成）之前：备份它所在的整个区域文件。 */
    public static void beforeRegenerate(ServerLevel level, ChunkPos pos, Throwable error) {
        if (!WorldGuard.enabled()) {
            return;
        }
        try {
            Path world = level.getServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
            Path region = DimensionType.getStorageFolder(level.dimension(), world).resolve("region")
                    .resolve("r." + pos.getRegionX() + "." + pos.getRegionZ() + ".mca");
            String copy = WorldGuard.quarantineCopy(world, region);
            if (copy != null) {
                EmergencyShelter.LOGGER.warn("[紧急避险] 区块 {} 读取失败、将被重新生成，已先把区域文件备份到 {}", pos, copy);
                WorldGuard.record("REGION_BACKUP", copy, WorldGuard.where(level.dimension(), pos.getMiddleBlockPosition(64)),
                        WorldGuard.message(error));
            }
        } catch (Throwable t) {
            EmergencyShelter.LOGGER.warn("[紧急避险] 备份区域文件失败", t);
        }
    }

    /** 读取区块之前：上次暂存的模组数据，满足重试条件时放回去。 */
    public static void mergeStashBack(ChunkPos pos, CompoundTag tag) {
        CompoundTag attachments = tag.getCompound(ATTACHMENTS);
        if (!attachments.contains(STASH_KEY, Tag.TAG_COMPOUND)) {
            return;
        }
        CompoundTag stash = attachments.getCompound(STASH_KEY);
        if (!WorldGuard.shouldRetry(stash.getCompound("fault"))) {
            return;
        }
        attachments.remove(STASH_KEY);
        CompoundTag keys = stash.getCompound("keys");
        for (String key : keys.getAllKeys()) {
            if (!tag.contains(key)) {
                tag.put(key, keys.get(key));
            }
        }
        CompoundTag foreign = stash.getCompound("attachments");
        for (String key : foreign.getAllKeys()) {
            if (!attachments.contains(key)) {
                attachments.put(key, foreign.get(key));
            }
        }
        if (attachments.isEmpty()) {
            tag.remove(ATTACHMENTS);
        } else {
            tag.put(ATTACHMENTS, attachments);
        }
        EmergencyShelter.LOGGER.info("[紧急避险] 区块 {} 之前暂存的模组数据已放回，重新尝试读取", pos);
    }

    /** 把模组附加的字段和附加数据从区块数据中取出来。 */
    /** 把模组加在区块上的字段（以及 withAttachments 时模组的附加数据）移进 stash。返回是否移走了东西。 */
    private static boolean strip(CompoundTag tag, CompoundTag stash, boolean withAttachments) {
        boolean removed = false;
        CompoundTag keys = stash.getCompound("keys");
        for (String key : new ArrayList<>(tag.getAllKeys())) {
            if (!VANILLA_KEYS.contains(key)) {
                keys.put(key, tag.get(key));
                tag.remove(key);
                removed = true;
            }
        }
        if (!keys.isEmpty()) {
            stash.put("keys", keys);
        }
        if (withAttachments) {
            CompoundTag attachments = tag.getCompound(ATTACHMENTS);
            CompoundTag foreign = stash.getCompound("attachments");
            for (String key : new ArrayList<>(attachments.getAllKeys())) {
                if (!key.startsWith(EmergencyShelter.MODID + ":")) {
                    foreign.put(key, attachments.get(key));
                    attachments.remove(key);
                    removed = true;
                }
            }
            if (attachments.isEmpty()) {
                tag.remove(ATTACHMENTS);
            } else {
                tag.put(ATTACHMENTS, attachments);
            }
            if (!foreign.isEmpty()) {
                stash.put("attachments", foreign);
            }
        }
        return removed;
    }

    private static CompoundTag mergeStash(CompoundTag previous, CompoundTag next) {
        if (previous == null || previous.isEmpty()) {
            return next;
        }
        CompoundTag merged = previous.copy();
        for (String part : new String[]{"keys", "attachments"}) {
            CompoundTag target = merged.getCompound(part);
            CompoundTag source = next.getCompound(part);
            for (String key : source.getAllKeys()) {
                target.put(key, source.get(key));
            }
            if (!target.isEmpty()) {
                merged.put(part, target);
            }
        }
        merged.put("fault", next.getCompound("fault"));
        return merged;
    }

    private static String describe(CompoundTag stash) {
        java.util.List<String> names = new ArrayList<>();
        names.addAll(stash.getCompound("keys").getAllKeys());
        names.addAll(stash.getCompound("attachments").getAllKeys());
        return String.join(", ", names);
    }
}
