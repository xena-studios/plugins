package co.xenastudios.core.neoforge.mixin;

import co.xenastudios.core.neoforge.world.WorldPoints;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalBooleanRef;
import net.minecraft.world.level.Explosion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
//? if server_explosion {
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
//?} else
//import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * An explosion destroying blocks: what it destroys is the explosion's, or the player's who lit
 * it. From 1.21.2 that's {@code ServerExplosion.explode}; before, {@code Explosion.finalizeExplosion}.
 */
//? if server_explosion {
@Mixin(net.minecraft.world.level.ServerExplosion.class)
//?} else
//@Mixin(Explosion.class)
public abstract class ExplosionMixin {
    //? if server_explosion {
    @Inject(method = "explode", at = @At("HEAD"))
    private void xena$start(CallbackInfoReturnable<Integer> ci, @Share("xena") LocalBooleanRef pushed) {
    //?} else {
    /*@Inject(method = "finalizeExplosion", at = @At("HEAD"))
    private void xena$start(boolean particles, CallbackInfo ci, @Share("xena") LocalBooleanRef pushed) {
    *///?}
        if (WorldPoints.heard == 0) return;
        Explosion self = (Explosion) (Object) this;
        pushed.set(WorldPoints.explosion(self.getDirectSourceEntity(), self.getIndirectSourceEntity()));
    }

    //? if server_explosion {
    @Inject(method = "explode", at = @At("RETURN"))
    private void xena$end(CallbackInfoReturnable<Integer> ci, @Share("xena") LocalBooleanRef pushed) {
    //?} else {
    /*@Inject(method = "finalizeExplosion", at = @At("RETURN"))
    private void xena$end(boolean particles, CallbackInfo ci, @Share("xena") LocalBooleanRef pushed) {
    *///?}
        if (pushed.get()) WorldPoints.pop();
    }
}
