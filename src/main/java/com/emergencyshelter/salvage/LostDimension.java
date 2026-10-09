package com.emergencyshelter.salvage;

import com.emergencyshelter.EmergencyShelter;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * 玩家下线时所在的维度被删除（例如删掉了暮色森林）：
 * 把玩家安全送到主世界出生点，记下原来的位置；维度回来以后可以用 /emergencyshelter return 回去。
 */
@EventBusSubscriber(modid = EmergencyShelter.MODID)
public final class LostDimension {
    private static final String KEY = "emergencyshelter:lost_position";
    private static final Map<UUID, Pending> PENDING = new ConcurrentHashMap<>();

    private record Pending(ResourceKey<Level> dimension, Vec3 position, float yRot, float xRot) {
    }

    private LostDimension() {
    }

    public static void markLost(ServerPlayer player, ResourceKey<Level> dimension) {
        PENDING.put(player.getUUID(), new Pending(dimension, player.position(), player.getYRot(), player.getXRot()));
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        // 事件处理出错不能让游戏崩溃
        com.emergencyshelter.Defense.quietly("LostDimension.onLogin", () -> onLoginUnsafe(event));
    }

    private static void onLoginUnsafe(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        Pending pending = PENDING.remove(player.getUUID());
        if (pending != null) {
            CompoundTag lost = new CompoundTag();
            lost.putString("dimension", pending.dimension().location().toString());
            lost.putDouble("x", pending.position().x);
            lost.putDouble("y", pending.position().y);
            lost.putDouble("z", pending.position().z);
            lost.putFloat("yRot", pending.yRot());
            lost.putFloat("xRot", pending.xRot());
            persisted(player).put(KEY, lost);
            ServerLevel overworld = player.server.overworld();
            BlockPos spawn = overworld.getSharedSpawnPos();
            int y = overworld.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, spawn.getX(), spawn.getZ());
            player.teleportTo(overworld, spawn.getX() + 0.5, y, spawn.getZ() + 0.5, player.getYRot(), player.getXRot());
            player.sendSystemMessage(Component.translatable("emergencyshelter.dimension.lost", pending.dimension().location().toString())
                    .withStyle(ChatFormatting.GOLD));
            EmergencyShelter.LOGGER.warn("[紧急避险] 玩家 {} 所在的维度 {} 不存在，已送回主世界出生点并记录原位置",
                    player.getGameProfile().getName(), pending.dimension().location());
            return;
        }
        CompoundTag lost = persisted(player).getCompound(KEY);
        if (!lost.isEmpty() && targetLevel(player, lost) != null) {
            player.sendSystemMessage(Component.translatable("emergencyshelter.dimension.back", lost.getString("dimension"))
                    .withStyle(ChatFormatting.GREEN).append(" ").append(Component.translatable("emergencyshelter.dimension.return")
                            .withStyle(Style.EMPTY.withColor(ChatFormatting.AQUA).withUnderlined(true)
                                    .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/emergencyshelter return")))));
        }
    }

    /** /emergencyshelter return */
    public static void returnPlayer(ServerPlayer player) {
        CompoundTag lost = persisted(player).getCompound(KEY);
        if (lost.isEmpty()) {
            player.sendSystemMessage(Component.translatable("emergencyshelter.dimension.nothing"));
            return;
        }
        ServerLevel level = targetLevel(player, lost);
        if (level == null) {
            player.sendSystemMessage(Component.translatable("emergencyshelter.dimension.still_missing", lost.getString("dimension"))
                    .withStyle(ChatFormatting.YELLOW));
            return;
        }
        player.teleportTo(level, lost.getDouble("x"), lost.getDouble("y"), lost.getDouble("z"), lost.getFloat("yRot"), lost.getFloat("xRot"));
        persisted(player).remove(KEY);
    }

    /** 放在 PlayerPersisted 里，死亡重生后也不会丢。 */
    private static CompoundTag persisted(ServerPlayer player) {
        CompoundTag data = player.getPersistentData();
        if (!data.contains(net.minecraft.world.entity.player.Player.PERSISTED_NBT_TAG, net.minecraft.nbt.Tag.TAG_COMPOUND)) {
            data.put(net.minecraft.world.entity.player.Player.PERSISTED_NBT_TAG, new CompoundTag());
        }
        return data.getCompound(net.minecraft.world.entity.player.Player.PERSISTED_NBT_TAG);
    }

    private static ServerLevel targetLevel(ServerPlayer player, CompoundTag lost) {
        ResourceLocation id = ResourceLocation.tryParse(lost.getString("dimension"));
        return id == null ? null : player.server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
    }
}
