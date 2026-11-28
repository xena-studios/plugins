package co.xenastudios.core.fabric.mixin;

import co.xenastudios.core.fabric.world.WorldPoints;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalBooleanRef;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Fire's tick: the blocks it burns away are fire's. */
@Mixin(FireBlock.class)
public abstract class FireMixin {
    @Inject(method = "tick", at = @At("HEAD"))
    private void xena$start(BlockState state, ServerLevel level, BlockPos pos, RandomSource random, CallbackInfo ci, @Share("xena") LocalBooleanRef pushed) {
        pushed.set(WorldPoints.heard != 0 && WorldPoints.fire());
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void xena$end(BlockState state, ServerLevel level, BlockPos pos, RandomSource random, CallbackInfo ci, @Share("xena") LocalBooleanRef pushed) {
        if (pushed.get()) WorldPoints.pop();
    }
}
