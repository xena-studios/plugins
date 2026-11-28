package co.xenastudios.core.neoforge.mixin;

import co.xenastudios.core.common.world.SpawnReason;
import co.xenastudios.core.neoforge.world.WorldPoints;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalBooleanRef;
import net.minecraft.world.item.SpawnEggItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A player using a spawn egg, on a block or in the air (into water): it spawns for {@link SpawnReason#SPAWN_EGG}. */
@Mixin(SpawnEggItem.class)
public abstract class SpawnEggMixin {
    @Inject(method = {"useOn", "use"}, at = @At("HEAD"))
    private void xena$start(CallbackInfoReturnable<?> cir, @Share("xena") LocalBooleanRef pushed) {
        pushed.set(WorldPoints.heard != 0 && WorldPoints.reason(SpawnReason.SPAWN_EGG));
    }

    @Inject(method = {"useOn", "use"}, at = @At("RETURN"))
    private void xena$end(CallbackInfoReturnable<?> cir, @Share("xena") LocalBooleanRef pushed) {
        if (pushed.get()) WorldPoints.pop();
    }
}
