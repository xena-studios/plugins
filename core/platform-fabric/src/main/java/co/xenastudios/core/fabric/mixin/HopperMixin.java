package co.xenastudios.core.fabric.mixin;

import co.xenastudios.core.fabric.world.WorldPoints;
import net.minecraft.core.Direction;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A hopper (or a dropper) moving items from one container to another. */
@Mixin(HopperBlockEntity.class)
public abstract class HopperMixin {
    private static final String ADD = "addItem(Lnet/minecraft/world/Container;Lnet/minecraft/world/Container;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/core/Direction;)Lnet/minecraft/world/item/ItemStack;";

    @Inject(method = ADD, at = @At("HEAD"))
    private static void xena$moving(Container from, Container to, ItemStack stack, Direction side, CallbackInfoReturnable<ItemStack> cir) {
        if (WorldPoints.heard != 0) WorldPoints.moving(stack);
    }

    @Inject(method = ADD, at = @At("RETURN"))
    private static void xena$moved(Container from, Container to, ItemStack stack, Direction side, CallbackInfoReturnable<ItemStack> cir) {
        if (WorldPoints.heard != 0) WorldPoints.moved(from, to, cir.getReturnValue());
    }
}
