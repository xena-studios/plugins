package co.xenastudios.core.fabric.mixin;

import co.xenastudios.core.fabric.world.WorldPoints;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A player taming a horse, a donkey, a mule or a llama: the hooks may stop it. */
//? if equine {
@Mixin(net.minecraft.world.entity.animal.equine.AbstractHorse.class)
//?} else
//@Mixin(net.minecraft.world.entity.animal.horse.AbstractHorse.class)
public abstract class HorseMixin {
    @Inject(method = "tameWithName", at = @At("HEAD"), cancellable = true)
    private void xena$tame(Player player, CallbackInfoReturnable<Boolean> cir) {
        if (WorldPoints.heard != 0 && WorldPoints.tame((Entity) (Object) this, player)) cir.setReturnValue(false);
    }
}
