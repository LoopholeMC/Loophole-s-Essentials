package com.loophole.essentials.module.category;

import io.github.itzispyder.clickcrystals.modules.Category;
import net.minecraft.resources.Identifier;

public final class LoopholeCategory {

    public static final Category LOOPHOLE_ESSENTIAL = new Category(
            "Loophole's Essentials",
            Identifier.parse("minecraft:textures/item/mace.png")
    );

    private LoopholeCategory() {
    }
}
