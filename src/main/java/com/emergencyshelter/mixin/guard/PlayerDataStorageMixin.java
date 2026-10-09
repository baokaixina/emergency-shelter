package com.emergencyshelter.mixin.guard;

import com.emergencyshelter.world.DataRescue;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.io.File;
import java.util.Optional;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.PlayerDataStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** 玩家数据文件和它的备份都读不出来时（原版会让玩家从零开始），换回上次正常退出时的版本。 */
@Mixin(PlayerDataStorage.class)
public abstract class PlayerDataStorageMixin {
    @Shadow
    @Final
    private File playerDir;

    @WrapMethod(method = "load(Lnet/minecraft/world/entity/player/Player;)Ljava/util/Optional;")
    private Optional<CompoundTag> emergencyshelter$load(Player player, Operation<Optional<CompoundTag>> original) {
        Optional<CompoundTag> result = original.call(player);
        return result.isPresent() ? result : DataRescue.onPlayerFilesUnreadable(playerDir, player);
    }
}
