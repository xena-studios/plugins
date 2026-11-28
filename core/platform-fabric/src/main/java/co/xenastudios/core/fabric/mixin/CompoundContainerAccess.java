package co.xenastudios.core.fabric.mixin;

import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** A double chest's first half, which a hopper's move is put down to. */
@Mixin(CompoundContainer.class)
public interface CompoundContainerAccess {
    @Accessor("container1")
    Container xena$first();
}
