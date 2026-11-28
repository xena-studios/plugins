package co.xenastudios.core.fabric.mixin;

import co.xenastudios.core.common.world.SpawnReason;
import co.xenastudios.core.fabric.world.WorldPoints;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalBooleanRef;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A thrown egg landing: a chick that hatches spawns for {@link SpawnReason#EGG}. */
//? if projectile_packages {
@Mixin(net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEgg.class)
//?} else
//@Mixin(net.minecraft.world.entity.projectile.ThrownEgg.class)
public abstract class EggMixin {
    @Inject(method = "onHit", at = @At("HEAD"))
    private void xena$start(CallbackInfo ci, @Share("xena") LocalBooleanRef pushed) {
        pushed.set(WorldPoints.heard != 0 && WorldPoints.reason(SpawnReason.EGG));
    }

    @Inject(method = "onHit", at = @At("RETURN"))
    private void xena$end(CallbackInfo ci, @Share("xena") LocalBooleanRef pushed) {
        if (pushed.get()) WorldPoints.pop();
    }
}
