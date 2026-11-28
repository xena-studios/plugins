package co.xenastudios.core.neoforge.mixin;

import co.xenastudios.core.neoforge.world.WorldPoints;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A player taming a wolf, a cat or a parrot: the hooks may stop it. */
@Mixin(TamableAnimal.class)
public abstract class TameMixin {
    @Inject(method = "tame", at = @At("HEAD"), cancellable = true)
    private void xena$tame(Player player, CallbackInfo ci) {
        if (WorldPoints.heard != 0 && WorldPoints.tame((Entity) (Object) this, player)) ci.cancel();
    }
}
