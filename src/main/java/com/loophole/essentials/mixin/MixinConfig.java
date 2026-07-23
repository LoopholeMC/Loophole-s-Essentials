package com.loophole.essentials.mixin;

import com.loophole.essentials.module.PersistentSettingProvider;
import io.github.itzispyder.clickcrystals.client.system.Config;
import io.github.itzispyder.clickcrystals.modules.Module;
import io.github.itzispyder.clickcrystals.modules.ModuleFile;
import io.github.itzispyder.clickcrystals.modules.ModuleSetting;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

@Mixin(value = Config.class, remap = false)
public class MixinConfig {

    @Shadow
    @Final
    private Map<String, ModuleFile> moduleEntries;

    @Inject(method = "loadModule", at = @At("TAIL"))
    private void loopholeEssentials$loadPersistentHiddenSettings(Module module, CallbackInfo ci) {
        if (!(module instanceof PersistentSettingProvider provider)) {
            return;
        }

        ModuleFile moduleFile = moduleEntries.getOrDefault(module.getId(), new ModuleFile(module));
        for (ModuleSetting<?> setting : provider.getPersistentSettings()) {
            moduleFile.revert(setting);
        }
    }
}
