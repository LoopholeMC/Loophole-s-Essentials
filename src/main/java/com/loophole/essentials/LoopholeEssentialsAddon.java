package com.loophole.essentials;

import com.loophole.essentials.module.modules.AutoClutchModule;
import com.loophole.essentials.module.modules.AutoDrainModule;
import com.loophole.essentials.module.modules.AutoHitCrystalModule;
import com.loophole.essentials.module.modules.AutoLungeModule;
import com.loophole.essentials.module.modules.AutoMaceModule;
import com.loophole.essentials.module.modules.AutoPotModule;
import com.loophole.essentials.module.modules.AutoSprintModule;
import com.loophole.essentials.module.modules.AutoWebModule;
import com.loophole.essentials.module.modules.AimAssistModule;
import com.loophole.essentials.module.modules.AnchorMacroModule;
import com.loophole.essentials.module.modules.BlockSpamModule;
import com.loophole.essentials.module.modules.ElytraSwitcherModule;
import com.loophole.essentials.module.modules.FastExpModule;
import com.loophole.essentials.module.modules.InventoryTotemModule;
import com.loophole.essentials.module.modules.JumpResetModule;
import com.loophole.essentials.module.modules.KeyBucketModule;
import com.loophole.essentials.module.modules.KeyPearlModule;
import com.loophole.essentials.module.modules.PotRefillModule;
import com.loophole.essentials.module.modules.ShieldBreakerModule;
import com.loophole.essentials.module.modules.TriggerBotModule;
import io.github.itzispyder.clickcrystals.ClickCrystals;
import io.github.itzispyder.clickcrystals.Global;
import io.github.itzispyder.clickcrystals.modules.Module;
import net.fabricmc.api.ClientModInitializer;

import java.util.List;

public final class LoopholeEssentialsAddon implements ClientModInitializer, Global {

    @Override
    public void onInitializeClient() {
        registerModules(List.of(
                new AimAssistModule(),
                new AutoClutchModule(),
                new AutoDrainModule(),
                new AutoHitCrystalModule(),
                new AutoLungeModule(),
                new AutoMaceModule(),
                new AutoPotModule(),
                new AnchorMacroModule(),
                new PotRefillModule(),
                new AutoSprintModule(),
                new AutoWebModule(),
                new BlockSpamModule(),
                new ElytraSwitcherModule(),
                new FastExpModule(),
                new InventoryTotemModule(),
                new JumpResetModule(),
                new KeyBucketModule(),
                new KeyPearlModule(),
                new ShieldBreakerModule(),
                new TriggerBotModule()
        ));

        if (!isNoOneAddonPresent()) {
            // Load after addon modules exist so saved state applies to all addon modules.
            ClickCrystals.config.loadEntireConfig();
            system.profiles.init();
        }
    }

    private void registerModules(List<Module> modules) {
        modules.forEach(system::addModule);
    }

    private boolean isNoOneAddonPresent() {
        try {
            Class.forName("net.i_no_am.clickcrystals.addon.AddonManager");
            return true;
        }
        catch (ClassNotFoundException ignored) {
            return false;
        }
    }
}
