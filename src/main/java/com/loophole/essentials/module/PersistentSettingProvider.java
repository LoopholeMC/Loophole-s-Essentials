package com.loophole.essentials.module;

import io.github.itzispyder.clickcrystals.modules.ModuleSetting;

import java.util.Collection;

public interface PersistentSettingProvider {

    Collection<ModuleSetting<?>> getPersistentSettings();
}
