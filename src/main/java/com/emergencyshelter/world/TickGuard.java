package com.emergencyshelter.world;

import com.emergencyshelter.EmergencyShelter;
import com.emergencyshelter.box.ShelterBoxData;
import com.emergencyshelter.mixin.LevelChunkInvoker;
import com.emergencyshelter.registry.ShelterRegistries;
import com.emergencyshelter.salvage.PlaceholderEntity;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.ChatFormatting;
import net.minecraft.CrashReport;
import net.minecraft.CrashReportCategory;
import net.minecraft.ReportType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * 运行时出错的隔离。原版遇到这些错误会直接崩溃（"Ticking block entity" / "Ticking entity"），
 * 而且下次进存档一靠近又会崩溃；NeoForge 的可选设置则会直接删掉它们。这里改成：
 * <ul>
 *     <li>方块实体（机器、箱子等）：停止运行，方块和数据原样保留；模组有变化或手动重试后恢复运行；</li>
 *     <li>实体（生物、矿车等）：变成占位实体，全部数据保存在里面，以后自动重试；</li>
 *     <li>背包里的物品：移进避险箱。</li>
 * </ul>
 */
public final class TickGuard {
    /** 客户端只需要在本次运行中停止，不需要保存。 */
    private static final Map<Level, LongOpenHashSet> CLIENT_FROZEN = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Set<String> LOGGED = ConcurrentHashMap.newKeySet();

    private TickGuard() {
    }

    static boolean fatal(Throwable t) {
        return t instanceof OutOfMemoryError;
    }

    // ------------------------------------------------------------------ 方块实体

    public static void onBlockEntityTickFailure(Level level, BlockPos pos, BlockEntity be, Throwable error) {
        if (fatal(error) || !WorldGuard.enabled(s -> s.freezeBrokenBlockEntities)) {
            throw WorldGuard.sneakyThrow(error);
        }
        String type = String.valueOf(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType()));
        if (LOGGED.add("block-entity " + type)) {
            CrashReport report = CrashReport.forThrowable(error, "Ticking block entity");
            CrashReportCategory category = report.addCategory("Block entity being ticked");
            try {
                be.fillCrashReportCategory(category);
            } catch (Throwable ignored) {
            }
            EmergencyShelter.LOGGER.error("[紧急避险] 方块实体 {} @ {} 运行时出错，已停止它的运行（原版会在这里崩溃），方块和数据完整保留。\n{}",
                    type, pos, report.getFriendlyReport(ReportType.CRASH));
        } else {
            // 同一种方块实体的完整报告只打印一次：成千上万个同时出错时逐个打印会让服务端卡住
            EmergencyShelter.LOGGER.error("[紧急避险] 方块实体 {} @ {} 运行时出错，已停止它的运行：{}", type, pos, WorldGuard.message(error));
        }
        if (level instanceof ServerLevel serverLevel) {
            WorldGuard.backupBeforeSuppress(serverLevel, "region", new ChunkPos(pos));
            ShelterWorldData data = ShelterWorldData.get();
            if (data != null) {
                data.freeze(serverLevel.dimension(), pos, type, WorldGuard.message(error));
            }
            WorldGuard.record("BLOCK_ENTITY_FROZEN", type, WorldGuard.where(serverLevel.dimension(), pos), WorldGuard.message(error));
        } else {
            CLIENT_FROZEN.computeIfAbsent(level, k -> new LongOpenHashSet()).add(pos.asLong());
        }
        refreshTicker(level, pos);
    }

    /** 方块实体是否处于"停止运行"状态（LevelChunk 创建运行器之前询问）。 */
    public static boolean isFrozen(Level level, BlockEntity be) {
        BlockPos pos = be.getBlockPos();
        if (level.isClientSide) {
            LongOpenHashSet set = CLIENT_FROZEN.get(level);
            return set != null && set.contains(pos.asLong());
        }
        if (!(level instanceof ServerLevel serverLevel) || !WorldGuard.enabled(s -> s.freezeBrokenBlockEntities)) {
            return false; // 这项功能关掉后，之前停止运行的方块实体也照常运行
        }
        ShelterWorldData data = ShelterWorldData.get();
        return data != null && data.isFrozen(serverLevel.dimension(), pos, String.valueOf(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType())));
    }

    /** 方块被拆除或替换：停止运行的记录随之作废。 */
    public static void onBlockEntityRemoved(Level level, BlockPos pos) {
        if (level instanceof ServerLevel serverLevel) {
            ShelterWorldData data = ShelterWorldData.get();
            if (data != null) {
                data.unfreeze(serverLevel.dimension(), pos);
            }
        } else {
            LongOpenHashSet set = CLIENT_FROZEN.get(level);
            if (set != null) {
                set.remove(pos.asLong());
            }
        }
    }

    /** 重新计算某个位置的方块实体运行器（冻结时移除、解冻时恢复）。 */
    public static void refreshTicker(Level level, BlockPos pos) {
        try {
            if (level.getChunk(pos) instanceof LevelChunk chunk) {
                BlockEntity be = chunk.getBlockEntity(pos, LevelChunk.EntityCreationType.CHECK);
                if (be != null) {
                    ((LevelChunkInvoker) chunk).emergencyshelter$updateBlockEntityTicker(be);
                }
            }
        } catch (Throwable t) {
            EmergencyShelter.LOGGER.warn("[紧急避险] 无法更新 {} 的运行状态", pos, t);
        }
    }

    // ------------------------------------------------------------------ 实体

    /** 返回 true 表示已经处理（不再崩溃）。 */
    public static boolean onEntityTickFailure(Level level, Entity entity, Throwable error) {
        if (fatal(error) || entity instanceof Player || !WorldGuard.enabled(s -> s.quarantineBrokenEntities)) {
            return false;
        }
        if (entity instanceof PlaceholderEntity) {
            // 占位实体本身不会出错；万一出错也不能再套一层占位（数据会越套越大），保持原样、只记一次日志
            if (LOGGED.add("placeholder-entity")) {
                EmergencyShelter.LOGGER.error("[紧急避险] 占位实体运行时出错，已忽略", error);
            }
            return true;
        }
        String type = String.valueOf(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()));
        if (level.isClientSide) {
            // 客户端：这个实体本次不再显示（服务端那边会单独处理）
            if (LOGGED.add("client-entity " + type)) {
                EmergencyShelter.LOGGER.error("[紧急避险] 客户端实体 {} 运行时出错，已在本地移除它（原版会崩溃）", type, error);
            }
            safeDiscard(entity);
            return true;
        }
        if (LOGGED.add("entity " + type)) {
            CrashReport report = CrashReport.forThrowable(error, "Ticking entity");
            try {
                entity.fillCrashReportCategory(report.addCategory("Entity being ticked"));
            } catch (Throwable ignored) {
            }
            EmergencyShelter.LOGGER.error("[紧急避险] 实体 {} @ {} 运行时出错，已把它转为占位实体保存（原版会在这里崩溃）。\n{}",
                    type, entity.blockPosition(), report.getFriendlyReport(ReportType.CRASH));
        } else {
            EmergencyShelter.LOGGER.error("[紧急避险] 实体 {} @ {} 运行时出错，已把它转为占位实体保存：{}", type, entity.blockPosition(),
                    WorldGuard.message(error));
        }
        String where = WorldGuard.where(level.dimension(), entity.blockPosition());
        if (level instanceof ServerLevel serverLevel) {
            WorldGuard.backupBeforeSuppress(serverLevel, "entities", entity.chunkPosition());
        }
        try {
            entity.ejectPassengers();
        } catch (Throwable ignored) {
        }
        CompoundTag data = new CompoundTag();
        boolean saved;
        try {
            saved = entity.saveAsPassenger(data);
        } catch (Throwable t) {
            saved = false;
        }
        if (saved && ShelterRegistries.PLACEHOLDER_ENTITY.isBound()) {
            data.remove("Passengers");
            data.put(PlaceholderEntity.FAULT_KEY, WorldGuard.faultTag("ENTITY_TICK", error));
            PlaceholderEntity placeholder = ShelterRegistries.PLACEHOLDER_ENTITY.get().create(level);
            if (placeholder != null) {
                placeholder.moveTo(entity.getX(), entity.getY(), entity.getZ(), entity.getYRot(), entity.getXRot());
                placeholder.setOriginal(data);
                level.addFreshEntity(placeholder);
            }
            WorldGuard.record("ENTITY_QUARANTINED", type, where, WorldGuard.message(error));
        } else {
            WorldGuard.record("ENTITY_LOST", type, where, WorldGuard.message(error));
        }
        safeDiscard(entity);
        return true;
    }

    private static void safeDiscard(Entity entity) {
        try {
            entity.discard();
        } catch (Throwable t) {
            try {
                entity.setRemoved(Entity.RemovalReason.DISCARDED);
            } catch (Throwable ignored) {
            }
        }
    }

    // ------------------------------------------------------------------ 背包物品

    public static void onItemTickFailure(Inventory inventory, ItemStack stack, Throwable error) {
        if (fatal(error) || !WorldGuard.enabled(s -> s.moveBrokenItems)) {
            throw WorldGuard.sneakyThrow(error);
        }
        Player player = inventory.player;
        String id = String.valueOf(BuiltInRegistries.ITEM.getKey(stack.getItem()));
        if (!(player instanceof ServerPlayer serverPlayer)) {
            if (LOGGED.add("client-item " + id)) {
                EmergencyShelter.LOGGER.error("[紧急避险] 背包里的物品 {} 在客户端运行时出错，已跳过（原版会崩溃）", id, error);
            }
            return;
        }
        EmergencyShelter.LOGGER.error("[紧急避险] 玩家 {} 背包里的物品 {} 运行时出错，已移进避险箱（原版会崩溃）",
                player.getName().getString(), id, error);
        WorldGuard.backupBeforeSuppress(serverPlayer);
        // 先放进避险箱，成功了再从背包里拿走：放不进去时物品还在背包里，不会丢
        ShelterBoxData.get(serverPlayer.server).add(serverPlayer.getUUID(), stack.copy());
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (inventory.getItem(i) == stack) {
                inventory.setItem(i, ItemStack.EMPTY);
                break;
            }
        }
        com.emergencyshelter.Defense.quietly("TickGuard.itemMovedMessage", () -> serverPlayer.sendSystemMessage(
                Component.translatable("emergencyshelter.guard.item_moved", stack.getHoverName())
                        .withStyle(ChatFormatting.GOLD).append(" ").append(com.emergencyshelter.box.ShelterBox.openLink())));
        WorldGuard.record("ITEM_MOVED", id, player.getName().getString(), WorldGuard.message(error));
    }
}
