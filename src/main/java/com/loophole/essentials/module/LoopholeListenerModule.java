package com.loophole.essentials.module;

import com.loophole.essentials.module.category.LoopholeCategory;
import com.loophole.essentials.module.settings.MouseButtonSetting;
import com.loophole.essentials.module.settings.RangeDoubleSetting;
import io.github.itzispyder.clickcrystals.modules.modules.ListenerModule;

public class LoopholeListenerModule extends ListenerModule {

    public LoopholeListenerModule(String name, String description) {
        super(name, LoopholeCategory.LOOPHOLE_ESSENTIAL, description);
    }

    protected RangeDoubleSetting.Builder createRangeDoubleSetting() {
        return RangeDoubleSetting.create();
    }

    protected MouseButtonSetting.Builder createMouseButtonSetting() {
        return MouseButtonSetting.create();
    }
}
