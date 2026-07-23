package com.loophole.essentials.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(AbstractContainerScreen.class)
public interface AccessorAbstractContainerScreen {

    @Accessor("leftPos")
    int loopholeEssentials$getLeftPos();

    @Accessor("topPos")
    int loopholeEssentials$getTopPos();
}
