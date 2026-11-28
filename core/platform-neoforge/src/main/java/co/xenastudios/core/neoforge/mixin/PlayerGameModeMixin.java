package co.xenastudios.core.neoforge.mixin;

import co.xenastudios.core.neoforge.world.WorldPoints;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalBooleanRef;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A player breaking a block, using an item on one, or using an item: what changes meanwhile is theirs. */
@Mixin(ServerPlayerGameMode.class)
public abstract class PlayerGameModeMixin {
    @Shadow
    @Final
    protected ServerPlayer player;

    @Inject(method = "destroyBlock", at = @At("HEAD"))
    private void xena$destroyStart(BlockPos pos, CallbackInfoReturnable<Boolean> cir, @Share("xena") LocalBooleanRef pushed) {
        pushed.set(WorldPoints.heard != 0 && WorldPoints.player(player, pos));
    }

    @Inject(method = "destroyBlock", at = @At("RETURN"))
    private void xena$destroyEnd(BlockPos pos, CallbackInfoReturnable<Boolean> cir, @Share("xena") LocalBooleanRef pushed) {
        if (pushed.get()) WorldPoints.pop();
    }

    @Inject(method = "useItemOn", at = @At("HEAD"))
    private void xena$useOnStart(ServerPlayer user, Level level, ItemStack stack, InteractionHand hand, BlockHitResult hit,
                                 CallbackInfoReturnable<InteractionResult> cir, @Share("xena") LocalBooleanRef pushed) {
        pushed.set(WorldPoints.heard != 0 && WorldPoints.player(user, hit.getBlockPos()));
    }

    @Inject(method = "useItemOn", at = @At("RETURN"))
    private void xena$useOnEnd(ServerPlayer user, Level level, ItemStack stack, InteractionHand hand, BlockHitResult hit,
                               CallbackInfoReturnable<InteractionResult> cir, @Share("xena") LocalBooleanRef pushed) {
        if (pushed.get()) WorldPoints.pop();
    }

    @Inject(method = "useItem", at = @At("HEAD"))
    private void xena$useStart(ServerPlayer user, Level level, ItemStack stack, InteractionHand hand,
                               CallbackInfoReturnable<InteractionResult> cir, @Share("xena") LocalBooleanRef pushed) {
        pushed.set(WorldPoints.heard != 0 && WorldPoints.player(user, null));
    }

    @Inject(method = "useItem", at = @At("RETURN"))
    private void xena$useEnd(ServerPlayer user, Level level, ItemStack stack, InteractionHand hand,
                             CallbackInfoReturnable<InteractionResult> cir, @Share("xena") LocalBooleanRef pushed) {
        if (pushed.get()) WorldPoints.pop();
    }
}
