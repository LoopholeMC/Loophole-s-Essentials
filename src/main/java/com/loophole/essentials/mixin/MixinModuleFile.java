package com.loophole.essentials.mixin;

import com.loophole.essentials.module.PersistentSettingProvider;
import com.loophole.essentials.module.settings.MouseButtonSetting;
import com.loophole.essentials.module.settings.RangeDoubleSetting;
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

@Mixin(value = ModuleFile.class, remap = false)
public abstract class MixinModuleFile {

    @Shadow
    @Final
    private Map<String, Integer> integerEntries;

    @Shadow
    @Final
    private Map<String, String> stringEntries;

    @Shadow
    public abstract <T> void add(ModuleSetting<T> setting);

    @Inject(method = "<init>", at = @At("TAIL"))
    private void loopholeEssentials$savePersistentHiddenSettings(Module module, CallbackInfo ci) {
        if (!(module instanceof PersistentSettingProvider provider)) {
            return;
        }

        for (ModuleSetting<?> setting : provider.getPersistentSettings()) {
            add(setting);
        }
    }

    @Inject(method = "revert", at = @At("HEAD"), cancellable = true)
    private void loopholeEssentials$restoreRangeStringSettings(ModuleSetting<?> setting, CallbackInfo ci) {
        if (setting instanceof MouseButtonSetting mouseButtonSetting) {
            int restoredValue = integerEntries.getOrDefault(mouseButtonSetting.getId(), mouseButtonSetting.getDef());
            mouseButtonSetting.setVal(restoredValue);
            ci.cancel();
            return;
        }
        if (!(setting instanceof RangeDoubleSetting rangeSetting)) {
            return;
        }

        String restoredValue = stringEntries.getOrDefault(rangeSetting.getId(), rangeSetting.getDef());
        rangeSetting.setVal(restoredValue);
        ci.cancel();
    }
}
