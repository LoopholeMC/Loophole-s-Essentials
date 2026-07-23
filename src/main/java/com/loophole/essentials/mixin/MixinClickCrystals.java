package com.loophole.essentials.mixin;

import io.github.itzispyder.clickcrystals.ClickCrystals;
import io.github.itzispyder.clickcrystals.client.system.Config;
import io.github.itzispyder.clickcrystals.client.system.ProfileManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = ClickCrystals.class, remap = false)
public class MixinClickCrystals {

    @Redirect(
            method = "onInitialize",
            at = @At(
                    value = "INVOKE",
                    target = "Lio/github/itzispyder/clickcrystals/client/system/Config;loadEntireConfig()V"
            )
    )
    private void loopholeEssentials$delayConfigLoadUntilAddonModulesExist(Config config) {
    }

    @Redirect(
            method = "onInitialize",
            at = @At(
                    value = "INVOKE",
                    target = "Lio/github/itzispyder/clickcrystals/client/system/ProfileManager;init()V"
            )
    )
    private void loopholeEssentials$delayProfileLoadUntilAddonModulesExist(ProfileManager profileManager) {
    }
}
