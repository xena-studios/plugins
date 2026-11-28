package co.xenastudios.core.fabric.mixin;

import co.xenastudios.core.common.world.SpawnReason;
import co.xenastudios.core.fabric.world.WorldPoints;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalBooleanRef;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The world spawning mobs and animals. What spawns meanwhile spawns for {@link SpawnReason#NATURAL}. */
@Mixin(net.minecraft.world.level.NaturalSpawner.class)
public abstract class NaturalSpawnerMixin {
    @Inject(method = "spawnCategoryForPosition(Lnet/minecraft/world/entity/MobCategory;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/NaturalSpawner$SpawnPredicate;Lnet/minecraft/world/level/NaturalSpawner$AfterSpawnCallback;)V", at = @At("HEAD"))
    private static void xena$start(CallbackInfo ci, @Share("xena") LocalBooleanRef pushed) {
        pushed.set(WorldPoints.heard != 0 && WorldPoints.reason(SpawnReason.NATURAL));
    }

    @Inject(method = "spawnCategoryForPosition(Lnet/minecraft/world/entity/MobCategory;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/NaturalSpawner$SpawnPredicate;Lnet/minecraft/world/level/NaturalSpawner$AfterSpawnCallback;)V", at = @At("RETURN"))
    private static void xena$end(CallbackInfo ci, @Share("xena") LocalBooleanRef pushed) {
        if (pushed.get()) WorldPoints.pop();
    }
}
