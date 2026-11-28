package co.xenastudios.core.neoforge.mixin;

import co.xenastudios.core.neoforge.world.WorldPoints;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalBooleanRef;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** An entity's tick (what it changes is its own), and every entity added to a world (a spawn the hooks may stop). */
@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {
    @Inject(method = "tickNonPassenger", at = @At("HEAD"))
    private void xena$tickStart(Entity entity, CallbackInfo ci, @Share("xena") LocalBooleanRef pushed) {
        pushed.set(WorldPoints.heard != 0 && WorldPoints.entity(entity));
    }

    @Inject(method = "tickNonPassenger", at = @At("RETURN"))
    private void xena$tickEnd(Entity entity, CallbackInfo ci, @Share("xena") LocalBooleanRef pushed) {
        if (pushed.get()) WorldPoints.pop();
    }

    @Inject(method = "addFreshEntity", at = @At("HEAD"), cancellable = true)
    private void xena$add(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (WorldPoints.heard != 0 && WorldPoints.spawn((ServerLevel) (Object) this, entity)) cir.setReturnValue(false);
    }
}
