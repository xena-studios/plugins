package co.xenastudios.core.neoforge.mixin;

import co.xenastudios.core.common.world.SpawnReason;
import co.xenastudios.core.neoforge.world.WorldPoints;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalBooleanRef;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Two animals breeding. What spawns meanwhile spawns for {@link SpawnReason#BREEDING}. */
@Mixin(net.minecraft.world.entity.animal.Animal.class)
public abstract class BreedingMixin {
    @Inject(method = "spawnChildFromBreeding", at = @At("HEAD"))
    private void xena$start(CallbackInfo ci, @Share("xena") LocalBooleanRef pushed) {
        pushed.set(WorldPoints.heard != 0 && WorldPoints.reason(SpawnReason.BREEDING));
    }

    @Inject(method = "spawnChildFromBreeding", at = @At("RETURN"))
    private void xena$end(CallbackInfo ci, @Share("xena") LocalBooleanRef pushed) {
        if (pushed.get()) WorldPoints.pop();
    }
}
