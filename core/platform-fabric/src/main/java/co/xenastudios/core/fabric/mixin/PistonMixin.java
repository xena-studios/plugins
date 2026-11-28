package co.xenastudios.core.fabric.mixin;

import co.xenastudios.core.fabric.world.WorldPoints;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalBooleanRef;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A piston about to move blocks: the hooks hear where each goes, and may stop it; Minecraft's own sets meanwhile aren't heard again. */
@Mixin(PistonBaseBlock.class)
public abstract class PistonMixin {
    @Inject(method = "moveBlocks", at = @At("HEAD"), cancellable = true)
    private void xena$start(Level level, BlockPos pos, Direction direction, boolean extending, CallbackInfoReturnable<Boolean> cir,
                            @Share("xena") LocalBooleanRef pushed) {
        if (WorldPoints.heard == 0) return;
        if (WorldPoints.piston(level, pos, direction, extending)) {
            cir.setReturnValue(false);
            return;
        }
        pushed.set(WorldPoints.suppress());
    }

    @Inject(method = "moveBlocks", at = @At("RETURN"))
    private void xena$end(Level level, BlockPos pos, Direction direction, boolean extending, CallbackInfoReturnable<Boolean> cir,
                          @Share("xena") LocalBooleanRef pushed) {
        if (pushed.get()) WorldPoints.pop();
    }
}
