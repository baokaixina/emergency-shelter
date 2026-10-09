package com.emergencyshelter.registry;

import com.emergencyshelter.EmergencyShelter;
import com.emergencyshelter.salvage.PlaceholderBlock;
import com.emergencyshelter.salvage.PlaceholderEntity;
import com.emergencyshelter.salvage.PlaceholderItem;
import java.util.function.Supplier;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

public final class ShelterRegistries {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(EmergencyShelter.MODID);
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(EmergencyShelter.MODID);
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES = DeferredRegister.create(Registries.ENTITY_TYPE, EmergencyShelter.MODID);
    public static final DeferredRegister.DataComponents COMPONENTS =
            DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, EmergencyShelter.MODID);
    public static final DeferredRegister<AttachmentType<?>> ATTACHMENTS =
            DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, EmergencyShelter.MODID);

    /** 占位物品里保存的原物品完整数据（id、数量、组件），原样保留以便以后还原。 */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<CompoundTag>> ORIGINAL_ITEM =
            COMPONENTS.registerComponentType("original_item", b -> b
                    .persistent(CompoundTag.CODEC)
                    .networkSynchronized(ByteBufCodecs.COMPOUND_TAG));

    /** 物品本身还在、但部分组件（附魔、数据）来自缺失模组时，暂存那些读不出来的部分。 */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<CompoundTag>> STASHED_COMPONENTS =
            COMPONENTS.registerComponentType("stashed_components", b -> b
                    .persistent(CompoundTag.CODEC)
                    .networkSynchronized(ByteBufCodecs.COMPOUND_TAG));

    public static final DeferredItem<PlaceholderItem> PLACEHOLDER =
            ITEMS.registerItem("placeholder", PlaceholderItem::new, new Item.Properties().stacksTo(99).fireResistant());

    public static final DeferredBlock<PlaceholderBlock> PLACEHOLDER_BLOCK = BLOCKS.registerBlock("placeholder_block", PlaceholderBlock::new,
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_PURPLE)
                    .strength(-1.0F, 3600000.0F)
                    .noLootTable()
                    .pushReaction(PushReaction.BLOCK)
                    .isValidSpawn((state, level, pos, type) -> false)
                    .sound(SoundType.STONE));

    public static final DeferredHolder<EntityType<?>, EntityType<PlaceholderEntity>> PLACEHOLDER_ENTITY =
            ENTITY_TYPES.register("placeholder_entity", () -> EntityType.Builder.<PlaceholderEntity>of(PlaceholderEntity::new, MobCategory.MISC)
                    .sized(0.6F, 0.6F)
                    .clientTrackingRange(8)
                    .fireImmune()
                    .build("placeholder_entity"));

    /** 区块附加数据：这个区块里所有占位方块的原始数据。 */
    public static final Supplier<AttachmentType<CompoundTag>> PLACEHOLDER_BLOCKS = ATTACHMENTS.register("placeholder_blocks",
            () -> AttachmentType.builder(() -> new CompoundTag()).serialize(CompoundTag.CODEC, tag -> !tag.isEmpty()).build());

    /** 区块附加数据：区块读取出错时暂时去掉的模组数据，等模组有变化时放回去再试。 */
    public static final Supplier<AttachmentType<CompoundTag>> STASHED_CHUNK_DATA = ATTACHMENTS.register("stashed_chunk_data",
            () -> AttachmentType.builder(() -> new CompoundTag()).serialize(CompoundTag.CODEC, tag -> !tag.isEmpty()).build());

    /** 任意持有者上的附加数据：某项读取出错、模组又重新创建了它时，原始数据备份在这里。 */
    public static final Supplier<AttachmentType<CompoundTag>> ATTACHMENT_BACKUP = ATTACHMENTS.register("attachment_backup",
            () -> AttachmentType.builder(() -> new CompoundTag()).serialize(CompoundTag.CODEC, tag -> !tag.isEmpty()).build());

    private ShelterRegistries() {
    }

    public static void register(IEventBus modBus) {
        ITEMS.register(modBus);
        BLOCKS.register(modBus);
        ENTITY_TYPES.register(modBus);
        COMPONENTS.register(modBus);
        ATTACHMENTS.register(modBus);
    }
}
