package com.emergencyshelter.world;

import com.emergencyshelter.EmergencyShelter;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

/**
 * 存档数据文件读取出错时，用"上次正常"的副本（{@link WorldSnapshot}）兜底：
 * <ul>
 *     <li>模组的存档数据（data/*.dat，AE2、精致存储等的仓库）：原版会当成空数据重新开始，存盘时把原文件覆盖掉；</li>
 *     <li>玩家数据：原版在 .dat 和 .dat_old 都坏掉时让玩家从零开始；读取时出错则直接崩溃；</li>
 *     <li>level.dat：原版在它和 level.dat_old 都坏掉时无法打开存档。</li>
 * </ul>
 * 坏掉的文件一律先复制进隔离目录，不会丢。
 */
public final class DataRescue {
    /** 原版玩家数据里的字段，其余的都是模组加的。 */
    private static final Set<String> VANILLA_PLAYER_KEYS = Set.of("DataVersion", "id", "Pos", "Motion", "Rotation", "FallDistance", "Fire",
            "Air", "OnGround", "Invulnerable", "PortalCooldown", "UUID", "CustomName", "CustomNameVisible", "Silent", "NoGravity", "Glowing",
            "TicksFrozen", "HasVisualFire", "Tags", "Passengers", "NeoForgeData", "CanUpdate", "Health", "HurtTime", "HurtByTimestamp",
            "DeathTime", "AbsorptionAmount", "attributes", "active_effects", "FallFlying", "SleepingX", "SleepingY", "SleepingZ", "Brain",
            "playerGameType", "previousPlayerGameType", "Inventory", "SelectedItemSlot", "SleepTimer", "XpP", "XpLevel", "XpTotal", "XpSeed",
            "Score", "foodLevel", "foodTickTimer", "foodSaturationLevel", "foodExhaustionLevel", "abilities", "EnderItems",
            "ShoulderEntityLeft", "ShoulderEntityRight", "LastDeathLocation", "current_explosion_impact_pos",
            "ignore_fall_damage_from_current_explosion", "current_impulse_context_reset_grace_time", "warden_spawn_tracker",
            "enteredNetherPosition", "seenCredits", "recipeBook", "Dimension", "SpawnX", "SpawnY", "SpawnZ", "SpawnForced", "SpawnAngle",
            "SpawnDimension", "spawn_extra_particles_on_fall", "raid_omen_position", "RootVehicle");
    private static final String PLAYER_STASH = "emergencyshelter:stash";

    private DataRescue() {
    }

    // ------------------------------------------------------------------ 模组存档数据（SavedData）

    /** 原版读取返回 null（文件存在却读不出来）时调用。 */
    @Nullable
    public static <T> T onSavedDataNull(File dataFolder, String name, Supplier<T> retry) {
        File file = new File(dataFolder, name + ".dat");
        if (!file.isFile() || !WorldGuard.enabled()) {
            return null;
        }
        Path path = file.toPath().toAbsolutePath().normalize();
        Path world = WorldSnapshot.findWorldRoot(path);
        if (world == null) {
            return null;
        }
        String rel = world.relativize(path).toString().replace('\\', '/');
        String badCopy = WorldGuard.quarantineCopy(world, path);
        Path good = WorldSnapshot.lastGood(world, path);
        try {
            if (good != null && Files.mismatch(good, path) != -1L) {
                Path held = path.resolveSibling(path.getFileName() + ".emergencyshelter-bad");
                Files.copy(path, held, StandardCopyOption.REPLACE_EXISTING);
                Files.copy(good, path, StandardCopyOption.REPLACE_EXISTING);
                T result = retry.get();
                if (result != null) {
                    Files.deleteIfExists(held);
                    EmergencyShelter.LOGGER.warn("[紧急避险] 存档数据 {} 读取出错（原版会清空它），已换回上次正常退出时的版本。出错的版本备份在 {}", rel, badCopy);
                    WorldGuard.record("DATA_ROLLBACK", rel, null, "已换回上次正常退出时的版本，出错的版本备份在 " + badCopy);
                    return result;
                }
                // 上次正常的版本也读不出来：放回原文件，交给原版处理
                Files.move(held, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            EmergencyShelter.LOGGER.warn("[紧急避险] 回滚 {} 时出错", rel, e);
        }
        EmergencyShelter.LOGGER.error("[紧急避险] 存档数据 {} 读取出错，没有可用的正常副本，原版会把它当成空数据。出错的文件备份在 {}", rel, badCopy);
        WorldGuard.record("DATA_LOST", rel, null, "没有可用的正常副本，出错的文件备份在 " + badCopy);
        return null;
    }

    // ------------------------------------------------------------------ 玩家数据

    /**
     * 读取玩家数据（在 Entity.load 外面包一层）。出错时依次尝试：
     * 去掉模组附加的数据再读（这些数据暂存在玩家身上，下次登录时放回去再试）→ 上次正常退出时的副本。
     */
    public static void loadPlayer(ServerPlayer player, CompoundTag tag, Operation<Void> original) {
        if (!WorldGuard.enabled()) {
            original.call(tag);
            return;
        }
        String name = player.getGameProfile().getName();
        CompoundTag stash = com.emergencyshelter.Defense.quietly("DataRescue.stash",
                () -> tag.getCompound("NeoForgeData").getCompound(PLAYER_STASH), new CompoundTag());
        if (!stash.isEmpty()) {
            // 上次暂存的模组数据：放回去再试
            CompoundTag merged = com.emergencyshelter.Defense.quietly("DataRescue.unstash", () -> unstash(tag, stash), null);
            if (merged != null && attempt(player, merged, original) == null) {
                EmergencyShelter.LOGGER.info("[紧急避险] 玩家 {} 之前暂存的模组数据已恢复", name);
                WorldGuard.record("PLAYER_RESTORED", name, null, "之前暂存的模组数据已恢复");
                return;
            }
        }
        Throwable error = attempt(player, tag, original);
        if (error == null) {
            return;
        }
        try {
            rescuePlayer(player, tag, stash, error, original);
        } catch (Throwable own) {
            throw com.emergencyshelter.Defense.fallback(error, own);
        }
    }

    private static void rescuePlayer(ServerPlayer player, CompoundTag tag, CompoundTag stash, Throwable error, Operation<Void> original) {
        String name = player.getGameProfile().getName();
        String saved = WorldGuard.quarantineNbt("players/" + player.getStringUUID() + ".dat", tag);
        EmergencyShelter.LOGGER.error("[紧急避险] 读取玩家 {} 的数据时出错（原版会崩溃），原始数据已保存到 {}", name, saved, error);

        CompoundTag stripped = tag.copy();
        CompoundTag newStash = stash.copy();
        CompoundTag keys = newStash.getCompound("keys");
        for (String key : new ArrayList<>(stripped.getAllKeys())) {
            if (!VANILLA_PLAYER_KEYS.contains(key) && !key.equals("neoforge:attachments")) {
                keys.put(key, stripped.get(key));
                stripped.remove(key);
            }
        }
        if (!keys.isEmpty()) {
            newStash.put("keys", keys);
        }
        CompoundTag attachments = stripped.getCompound("neoforge:attachments");
        if (!attachments.isEmpty()) {
            CompoundTag stashed = newStash.getCompound("attachments");
            for (String key : attachments.getAllKeys()) {
                if (!key.startsWith(EmergencyShelter.MODID + ":")) {
                    stashed.put(key, attachments.get(key));
                }
            }
            stashed.getAllKeys().forEach(attachments::remove);
            newStash.put("attachments", stashed);
            if (attachments.isEmpty()) {
                stripped.remove("neoforge:attachments");
            }
        }
        if (!newStash.isEmpty()) {
            CompoundTag neo = stripped.getCompound("NeoForgeData");
            neo.put(PLAYER_STASH, newStash);
            stripped.put("NeoForgeData", neo);
            if (attempt(player, stripped, original) == null) {
                EmergencyShelter.LOGGER.warn("[紧急避险] 去掉模组附加的数据后，玩家 {} 的数据读取成功；这些数据已暂存，下次登录时会再试", name);
                WorldGuard.record("PLAYER_STASHED", name, null, WorldGuard.message(error));
                return;
            }
        }
        CompoundTag snapshot = readSnapshot(player);
        if (snapshot != null && attempt(player, snapshot, original) == null) {
            EmergencyShelter.LOGGER.warn("[紧急避险] 玩家 {} 的数据已换回上次正常退出时的版本", name);
            WorldGuard.record("PLAYER_ROLLBACK", name, null, WorldGuard.message(error));
            return;
        }
        WorldGuard.record("PLAYER_FAILED", name, null, "原始数据保存在 " + saved);
    }

    @Nullable
    private static Throwable attempt(ServerPlayer player, CompoundTag tag, Operation<Void> original) {
        try {
            original.call(tag);
            return null;
        } catch (Throwable t) {
            if (t instanceof OutOfMemoryError) {
                throw WorldGuard.sneakyThrow(t);
            }
            return t;
        }
    }

    private static CompoundTag unstash(CompoundTag tag, CompoundTag stash) {
        CompoundTag merged = tag.copy();
        CompoundTag neo = merged.getCompound("NeoForgeData");
        neo.remove(PLAYER_STASH);
        merged.put("NeoForgeData", neo);
        CompoundTag keys = stash.getCompound("keys");
        for (String key : keys.getAllKeys()) {
            if (!merged.contains(key)) {
                merged.put(key, keys.get(key));
            }
        }
        CompoundTag stashed = stash.getCompound("attachments");
        if (!stashed.isEmpty()) {
            CompoundTag attachments = merged.getCompound("neoforge:attachments");
            for (String key : stashed.getAllKeys()) {
                if (!attachments.contains(key)) {
                    attachments.put(key, stashed.get(key));
                }
            }
            merged.put("neoforge:attachments", attachments);
        }
        return merged;
    }

    /** .dat 和 .dat_old 都读不出来时（原版会让玩家从零开始）：用上次正常退出时的副本。 */
    public static Optional<CompoundTag> onPlayerFilesUnreadable(File playerDir, Player player) {
        if (!WorldGuard.enabled() || !(player instanceof ServerPlayer serverPlayer)) {
            return Optional.empty();
        }
        File dat = new File(playerDir, player.getStringUUID() + ".dat");
        File old = new File(playerDir, player.getStringUUID() + ".dat_old");
        if (!dat.isFile() && !old.isFile()) {
            return Optional.empty(); // 新玩家
        }
        CompoundTag snapshot = readSnapshot(serverPlayer);
        if (snapshot == null) {
            WorldGuard.record("PLAYER_FAILED", player.getGameProfile().getName(), null, "玩家数据文件损坏，且没有正常副本");
            return Optional.empty();
        }
        player.load(snapshot);
        net.neoforged.neoforge.event.EventHooks.firePlayerLoadingEvent(player, playerDir, player.getStringUUID());
        EmergencyShelter.LOGGER.warn("[紧急避险] 玩家 {} 的数据文件损坏（原版会让他从零开始），已换回上次正常退出时的版本",
                player.getGameProfile().getName());
        WorldGuard.record("PLAYER_ROLLBACK", player.getGameProfile().getName(), null, "数据文件损坏，已换回上次正常退出时的版本");
        return Optional.of(snapshot);
    }

    @Nullable
    private static CompoundTag readSnapshot(ServerPlayer player) {
        Path world = WorldGuard.worldRoot();
        if (world == null) {
            return null;
        }
        Path file = WorldSnapshot.root(world).resolve("playerdata").resolve(player.getStringUUID() + ".dat");
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            CompoundTag tag = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
            int version = NbtUtils.getDataVersion(tag, -1);
            return DataFixTypes.PLAYER.updateToCurrentVersion(player.server.getFixerUpper(), tag, version);
        } catch (Throwable t) {
            EmergencyShelter.LOGGER.warn("[紧急避险] 玩家数据的正常副本也无法读取", t);
            return null;
        }
    }

    // ------------------------------------------------------------------ level.dat

    /** level.dat 和 level.dat_old 都读不出来：把上次正常退出时的 level.dat 放到 level.dat_old 的位置，让原版的备用读取流程用它。 */
    public static boolean prepareLevelDatFallback(Path worldDir) {
        Path snapshot = WorldSnapshot.root(worldDir).resolve("level.dat");
        if (!Files.isRegularFile(snapshot)) {
            return false;
        }
        Path old = worldDir.resolve("level.dat_old");
        try {
            if (Files.isRegularFile(old) && Files.mismatch(old, snapshot) == -1L) {
                return false; // 已经是它了，仍然读不出来
            }
            Path quarantine = worldDir.resolve("emergencyshelter").resolve("quarantine").resolve("level-dat-" + System.currentTimeMillis());
            Files.createDirectories(quarantine);
            for (String name : new String[]{"level.dat", "level.dat_old"}) {
                Path p = worldDir.resolve(name);
                if (Files.isRegularFile(p)) {
                    Files.copy(p, quarantine.resolve(name), StandardCopyOption.REPLACE_EXISTING);
                }
            }
            Files.copy(snapshot, old, StandardCopyOption.REPLACE_EXISTING);
            WorldGuard.recordBeforeStart("LEVEL_DAT_ROLLBACK", "level.dat", worldDir.relativize(quarantine).toString().replace('\\', '/'));
            EmergencyShelter.LOGGER.warn("[紧急避险] level.dat 和 level.dat_old 都无法读取，改用上次正常退出时的副本。损坏的文件备份在 {}", quarantine);
            return true;
        } catch (IOException e) {
            EmergencyShelter.LOGGER.warn("[紧急避险] 无法使用 level.dat 的正常副本", e);
            return false;
        }
    }
}
