package com.loophole.essentials.module;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;

import java.util.Locale;

public final class PotionMatcher {

    private PotionMatcher() {
    }

    public static boolean matchesConfiguredSplashPotion(ItemStack stack, String allowedPotionList) {
        if (stack == null || stack.isEmpty() || !stack.is(Items.SPLASH_POTION)) {
            return false;
        }

        String[] tokens = tokenizeAllowedPotions(allowedPotionList);
        if (tokens.length == 0) {
            return false;
        }

        PotionContents contents = stack.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY);
        for (String token : tokens) {
            if (token.isEmpty()) {
                continue;
            }
            if (matchesPotionContentsToken(contents, token)) {
                return true;
            }
        }
        return false;
    }

    public static String[] tokenizeAllowedPotions(String allowedPotionList) {
        if (allowedPotionList == null) {
            return new String[0];
        }
        return allowedPotionList.split(",");
    }

    private static boolean matchesPotionContentsToken(PotionContents contents, String token) {
        if (contents == null) {
            return false;
        }
        if (contents.customName().isPresent() && matchesPotionName(contents.customName().get(), token)) {
            return true;
        }
        if (contents.potion().isPresent() && matchesPotionName(contents.potion().get().getRegisteredName(), token)) {
            return true;
        }

        for (MobEffectInstance effect : contents.getAllEffects()) {
            if (matchesPotionName(effect.getEffect().getRegisteredName(), token)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesPotionName(String rawName, String token) {
        String normalizedToken = normalizePotionSearchName(token);
        String normalizedName = normalizePotionSearchName(rawName);
        String baseName = stripVariantPrefixes(normalizedName);
        String canonicalToken = canonicalPotionName(normalizedToken);
        String canonicalName = canonicalPotionName(baseName);

        return containsPotionTerm(normalizedName, normalizedToken)
                || containsPotionTerm(baseName, normalizedToken)
                || containsPotionTerm(canonicalName, normalizedToken)
                || containsPotionTerm(normalizedName, canonicalToken)
                || containsPotionTerm(baseName, canonicalToken)
                || containsPotionTerm(canonicalName, canonicalToken);
    }

    private static boolean containsPotionTerm(String candidate, String token) {
        return !candidate.isEmpty() && !token.isEmpty() && candidate.contains(token);
    }

    private static String normalizePotionSearchName(String raw) {
        if (raw == null) {
            return "";
        }

        String normalized = raw.toLowerCase(Locale.ROOT).trim();
        normalized = normalized.replace("minecraft:", "");
        normalized = normalized.replace("item.minecraft.", "");
        normalized = normalized.replace("effect.minecraft.", "");
        normalized = normalized.replace('-', '_');
        normalized = normalized.replace(' ', '_');
        return normalized;
    }

    private static String stripVariantPrefixes(String normalizedName) {
        String stripped = normalizedName;
        while (stripped.startsWith("long_") || stripped.startsWith("strong_")) {
            if (stripped.startsWith("long_")) {
                stripped = stripped.substring("long_".length());
                continue;
            }
            stripped = stripped.substring("strong_".length());
        }
        return stripped;
    }

    private static String canonicalPotionName(String normalizedName) {
        return switch (normalizedName) {
            case "instant_health", "healing" -> "healing";
            case "instant_damage", "harming" -> "harming";
            case "speed", "swiftness" -> "swiftness";
            case "jump_boost", "leaping" -> "leaping";
            default -> normalizedName;
        };
    }
}
