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

/** The summon command. What spawns meanwhile spawns for {@link SpawnReason#COMMAND}. */
@Mixin(net.minecraft.server.commands.SummonCommand.class)
public abstract class SummonMixin {
    @Inject(method = "createEntity", at = @At("HEAD"))
    private static void xena$start(CallbackInfoReturnable<?> ci, @Share("xena") LocalBooleanRef pushed) {
        pushed.set(WorldPoints.heard != 0 && WorldPoints.reason(SpawnReason.COMMAND));
    }

    @Inject(method = "createEntity", at = @At("RETURN"))
    private static void xena$end(CallbackInfoReturnable<?> ci, @Share("xena") LocalBooleanRef pushed) {
        if (pushed.get()) WorldPoints.pop();
    }
}
