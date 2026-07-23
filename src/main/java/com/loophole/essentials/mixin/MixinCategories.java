package com.loophole.essentials.mixin;

import com.loophole.essentials.module.category.LoopholeCategory;
import io.github.itzispyder.clickcrystals.modules.Categories;
import io.github.itzispyder.clickcrystals.modules.Category;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;

@Mixin(value = Categories.class, remap = false, priority = 900)
public class MixinCategories {

    @Shadow
    @Final
    private static LinkedHashMap<String, Category> categories;

    @Inject(method = "getCategories", at = @At("HEAD"), cancellable = true)
    private static void addLoopholeCategory(CallbackInfoReturnable<LinkedHashMap<String, Category>> cir) {
        LinkedHashMap<String, Category> updatedCategories = new LinkedHashMap<>();
        Category addonCategory = getNoOneAddonCategory();
        boolean insertedLoophole = false;
        boolean insertedAddon = false;

        for (Map.Entry<String, Category> entry : categories.entrySet()) {
            if (addonCategory != null && !insertedAddon && (entry.getKey().equals("Custom Made") || entry.getKey().equals("SCRIPTED"))) {
                updatedCategories.put(addonCategory.name(), addonCategory);
                insertedAddon = true;
            }
            if (insertedAddon && !insertedLoophole) {
                updatedCategories.put("Loophole's Essentials", LoopholeCategory.LOOPHOLE_ESSENTIAL);
                insertedLoophole = true;
            }
            else if (!insertedLoophole && (entry.getKey().equals("Custom Made") || entry.getKey().equals("SCRIPTED"))) {
                updatedCategories.put("Loophole's Essentials", LoopholeCategory.LOOPHOLE_ESSENTIAL);
                insertedLoophole = true;
            }
            updatedCategories.put(entry.getKey(), entry.getValue());
        }

        if (!insertedLoophole) {
            updatedCategories.put("Loophole's Essentials", LoopholeCategory.LOOPHOLE_ESSENTIAL);
        }
        cir.setReturnValue(updatedCategories);
    }

    private static Category getNoOneAddonCategory() {
        try {
            Class<?> addonCategoryClass = Class.forName("net.i_no_am.clickcrystals.addon.module.category.AddonCategory");
            Field addonField = addonCategoryClass.getDeclaredField("ADDON");
            Object value = addonField.get(null);
            return value instanceof Category category ? category : null;
        }
        catch (ReflectiveOperationException ignored) {
            return null;
        }
    }
}
