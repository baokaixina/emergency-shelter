package com.emergencyshelter.mixin.guard;

import org.spongepowered.asm.mixin.Unique;
import com.emergencyshelter.world.WorldgenGuard;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** 生成区块时某个地物或结构出错：跳过它，而不是让游戏崩溃。 */
@Mixin(ChunkGenerator.class)
public abstract class ChunkGeneratorMixin {
    @WrapOperation(method = "applyBiomeDecoration", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/levelgen/placement/PlacedFeature;placeWithBiomeCheck(Lnet/minecraft/world/level/WorldGenLevel;Lnet/minecraft/world/level/chunk/ChunkGenerator;Lnet/minecraft/util/RandomSource;Lnet/minecraft/core/BlockPos;)Z"))
    private boolean emergencyshelter$guardFeature(PlacedFeature feature, WorldGenLevel level, ChunkGenerator generator, RandomSource random, BlockPos pos,
                                                   Operation<Boolean> original) {
        if (!WorldgenGuard.isVanillaRegion(level)) {
            return original.call(feature, level, generator, random, pos);
        }
        return WorldgenGuard.placeFeature(feature, level.getLevel(), pos, () -> original.call(feature, level, generator, random, pos));
    }

    @WrapOperation(method = "applyBiomeDecoration", at = @At(value = "INVOKE", target = "Ljava/util/List;forEach(Ljava/util/function/Consumer;)V"))
    private void emergencyshelter$guardStructures(List<StructureStart> starts, Consumer<StructureStart> action, Operation<Void> original,
                                                  @Local(argsOnly = true) WorldGenLevel level, @Local(argsOnly = true) ChunkAccess chunk) {
        if (!WorldgenGuard.isVanillaRegion(level)) {
            original.call(starts, action);
            return;
        }
        original.call(starts, WorldgenGuard.guardStructurePlacement(action, level.getLevel(), chunk.getPos()));
    }

    @WrapOperation(method = "createStructures", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/chunk/ChunkGenerator;tryGenerateStructure(Lnet/minecraft/world/level/levelgen/structure/StructureSet$StructureSelectionEntry;Lnet/minecraft/world/level/StructureManager;Lnet/minecraft/core/RegistryAccess;Lnet/minecraft/world/level/levelgen/RandomState;Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplateManager;JLnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/core/SectionPos;)Z"))
    private boolean emergencyshelter$guardStructureStart(ChunkGenerator self, StructureSet.StructureSelectionEntry entry, StructureManager structureManager,
                                                         RegistryAccess registryAccess, RandomState random, StructureTemplateManager templates, long seed,
                                                         ChunkAccess chunk, ChunkPos chunkPos, SectionPos sectionPos, Operation<Boolean> original) {
        ServerLevel level = emergencyshelter$levelOf(structureManager);
        return WorldgenGuard.tryGenerateStructure(entry.structure().value(), level, chunkPos,
                () -> original.call(self, entry, structureManager, registryAccess, random, templates, seed, chunk, chunkPos, sectionPos));
    }

    /** 访问器没有生效（版本变化、和其它模组冲突）时返回 null，只是报告里少了维度信息。 */
    @Unique
    private static ServerLevel emergencyshelter$levelOf(StructureManager structureManager) {
        try {
            return ((StructureManagerAccessor) structureManager).emergencyshelter$level() instanceof ServerLevel s ? s : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
