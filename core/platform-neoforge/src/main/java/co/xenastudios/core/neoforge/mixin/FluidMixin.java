package co.xenastudios.core.neoforge.mixin;

import co.xenastudios.core.neoforge.world.WorldPoints;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalBooleanRef;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Water or lava spreading into a block: what it destroys is the fluid's. */
@Mixin(FlowingFluid.class)
public abstract class FluidMixin {
    @Inject(method = "spreadTo", at = @At("HEAD"))
    private void xena$start(LevelAccessor level, BlockPos pos, BlockState state, Direction direction, FluidState fluid, CallbackInfo ci,
                            @Share("xena") LocalBooleanRef pushed) {
        pushed.set(WorldPoints.heard != 0 && WorldPoints.fluid((FlowingFluid) (Object) this));
    }

    @Inject(method = "spreadTo", at = @At("RETURN"))
    private void xena$end(LevelAccessor level, BlockPos pos, BlockState state, Direction direction, FluidState fluid, CallbackInfo ci,
                          @Share("xena") LocalBooleanRef pushed) {
        if (pushed.get()) WorldPoints.pop();
    }
}
