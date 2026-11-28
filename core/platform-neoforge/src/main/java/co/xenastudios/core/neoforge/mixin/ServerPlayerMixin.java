package co.xenastudios.core.neoforge.mixin;

import co.xenastudios.core.neoforge.world.WorldPoints;
import java.util.OptionalInt;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A player opening and closing a container's menu: what it held, compared as they close it. */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin {
    @Inject(method = "openMenu", at = @At("RETURN"))
    private void xena$opened(MenuProvider provider, CallbackInfoReturnable<OptionalInt> cir) {
        if (WorldPoints.heard != 0 && cir.getReturnValue().isPresent()) WorldPoints.opened((ServerPlayer) (Object) this);
    }

    @Inject(method = "doCloseContainer", at = @At("HEAD"))
    private void xena$closing(CallbackInfo ci) {
        if (WorldPoints.heard != 0) WorldPoints.closing((ServerPlayer) (Object) this);
    }
}
