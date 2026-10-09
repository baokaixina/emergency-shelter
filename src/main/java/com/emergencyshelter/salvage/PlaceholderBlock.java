package com.emergencyshelter.salvage;

import com.emergencyshelter.registry.ShelterRegistries;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 占位方块：原方块所属的模组不在了，或者它的数据读取出错被隔离。原方块状态和方块实体数据保存在区块里，模组回来后自动还原。
 * 机器、爆炸都破坏不了它；玩家潜行时可以拆除，拆下来得到一个带着全部数据的占位物品。
 */
public final class PlaceholderBlock extends Block {
    public PlaceholderBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected float getDestroyProgress(BlockState state, Player player, BlockGetter level, BlockPos pos) {
        return player.isShiftKeyDown() ? 1.0F / 30.0F : 0.0F;
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide && player.isShiftKeyDown()) {
            dropPacked(level, pos);
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide && level.getChunk(pos) instanceof LevelChunk chunk) {
            CompoundTag original = ChunkSalvage.peekState(chunk, pos);
            String id = original == null ? "?" : original.getString("Name");
            CompoundTag fault = ChunkSalvage.peekFault(chunk, pos);
            if (fault != null) {
                player.sendSystemMessage(Component.translatable("block.emergencyshelter.placeholder_block.fault_info", id, fault.getString("error"))
                        .withStyle(ChatFormatting.GOLD));
            } else {
                player.sendSystemMessage(Component.translatable("block.emergencyshelter.placeholder_block.info", id)
                        .withStyle(ChatFormatting.GOLD));
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    /** 把这个位置的原方块打包成占位物品掉落：模组回来后它会变成原方块的物品，并带着方块实体数据。 */
    private static void dropPacked(Level level, BlockPos pos) {
        if (!(level.getChunk(pos) instanceof LevelChunk chunk)) {
            return;
        }
        CompoundTag[] data = ChunkSalvage.take(chunk, pos);
        if (data == null || data[0] == null) {
            return;
        }
        CompoundTag original = new CompoundTag();
        original.putString("id", data[0].getString("Name"));
        original.putInt("count", 1);
        CompoundTag components = new CompoundTag();
        CompoundTag packed = new CompoundTag();
        packed.put("state", data[0]);
        if (data[1] != null) {
            CompoundTag be = data[1].copy();
            be.remove("x");
            be.remove("y");
            be.remove("z");
            // 方块实体数据只存一份（在物品组件里），不在下面重复保存，避免物品数据翻倍
            components.put("minecraft:block_entity_data", be);
        }
        original.put("components", components);
        // 完整的方块状态也一并保存（物品编解码器会忽略这个额外字段）
        original.put("emergencyshelter:block", packed);
        ItemStack stack = new ItemStack(ShelterRegistries.PLACEHOLDER.get());
        stack.set(ShelterRegistries.ORIGINAL_ITEM.get(), original);
        Block.popResource(level, pos, stack);
    }
}
