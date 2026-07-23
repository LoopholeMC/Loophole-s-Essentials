package com.loophole.essentials.module.modules;

import com.loophole.essentials.module.LoopholeListenerModule;
import com.loophole.essentials.module.PersistentSettingProvider;
import com.loophole.essentials.module.settings.RangeDoubleSetting;
import io.github.itzispyder.clickcrystals.events.EventHandler;
import io.github.itzispyder.clickcrystals.events.events.client.KeyPressEvent;
import io.github.itzispyder.clickcrystals.events.events.client.MouseClickEvent;
import io.github.itzispyder.clickcrystals.events.events.client.MouseScrollEvent;
import io.github.itzispyder.clickcrystals.events.events.networking.GameLeaveEvent;
import io.github.itzispyder.clickcrystals.events.events.world.ClientTickStartEvent;
import io.github.itzispyder.clickcrystals.gui.ClickType;
import io.github.itzispyder.clickcrystals.gui.screens.ModuleEditScreen;
import io.github.itzispyder.clickcrystals.modules.ModuleSetting;
import io.github.itzispyder.clickcrystals.modules.settings.SettingSection;
import io.github.itzispyder.clickcrystals.util.minecraft.EntityUtils;
import io.github.itzispyder.clickcrystals.util.minecraft.InvUtils;
import io.github.itzispyder.clickcrystals.util.minecraft.PlayerUtils;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.EntityHitResult;

import java.util.Collection;
import java.util.List;
import java.util.Locale;

public class AutoLungeModule extends LoopholeListenerModule implements PersistentSettingProvider {

    private static final MatcherSpec FIXED_LUNGE_MATCHER = MatcherSpec.parse("spear[lunge]");

    private final SettingSection scGeneral = getGeneralSection();

    public final ModuleSetting<String> blockedHeldItems = scGeneral.add(createStringSetting()
            .name("blocked-held-items")
            .description("Comma-separated held-item matchers that prevent Auto Lunge from activating, for example spear[lunge],trident[riptide].")
            .def("spear[lunge]")
            .build()
    );

    public final ModuleSetting<Boolean> switchBack = scGeneral.add(createBoolSetting()
            .name("switch-back")
            .description("Switch back to your original hotbar slot after lunging.")
            .def(true)
            .build()
    );

    public final RangeDoubleSetting switchBackDelay = scGeneral.add(createRangeDoubleSetting()
            .name("switch-back-delay")
            .description("Randomized delay before switching back after a lunge swap when restore conditions stay valid.")
            .def(0.030, 0.050)
            .min(0.000)
            .max(0.100)
            .decimalPlaces(3)
            .build()
    );

    public final ModuleSetting<Boolean> waitForNoUseBeforeSwitchBack = scGeneral.add(createBoolSetting()
            .name("wait-for-no-use-before-switch-back")
            .description("Delay switch-back while right-click use input is active.")
            .def(true)
            .build()
    );

    public final ModuleSetting<Boolean> cancelSwitchBackOnManualSlotChange = scGeneral.add(createBoolSetting()
            .name("cancel-switch-back-on-manual-slot-change")
            .description("Cancel switch-back if you manually change hotbar slots before the delayed restore happens.")
            .def(true)
            .build()
    );

    private SwitchSequence activeSequence = null;
    private int activeSequenceToken = 0;
    private boolean leftClickHeld = false;
    private boolean pendingChildSettingsSync = false;
    private boolean pendingSettingsScreenRefresh = false;

    public AutoLungeModule() {
        super("auto-lunge", "Switches to a hotbar lunge spear when you left-click into empty space, then swaps back after a short random delay unless you keep using or manually change slots.");
        configureChildSettings();
        syncVisibleSettings();
    }

    @Override
    protected void onDisable() {
        super.onDisable();
        resetRuntimeState();
    }

    @EventHandler
    private void onGameLeave(GameLeaveEvent e) {
        resetRuntimeState();
    }

    @EventHandler
    private void onMouseClick(MouseClickEvent e) {
        if (e.getButton() != 0) {
            return;
        }

        if (e.getAction() == ClickType.CLICK) {
            leftClickHeld = true;
            tryStartLungeSequence();
            return;
        }
        if (e.getAction() == ClickType.RELEASE) {
            leftClickHeld = false;
            tryFinalizeSequence(activeSequenceToken);
        }
    }

    @EventHandler
    private void onMouseScroll(MouseScrollEvent e) {
        if (shouldCancelOnManualSlotChange() && mc.screen == null && e.isVertical() && e.getDeltaY() != 0.0) {
            invalidateSequence();
        }
    }

    @EventHandler
    private void onKeyPress(KeyPressEvent e) {
        if (!shouldCancelOnManualSlotChange() || mc.options == null || !e.getAction().isDown()) {
            return;
        }

        KeyEvent input = new KeyEvent(e.getKeycode(), e.getScancode(), 0);
        for (int slot = 0; slot < mc.options.keyHotbarSlots.length; slot++) {
            if (mc.options.keyHotbarSlots[slot].matches(input)) {
                invalidateSequence();
                return;
            }
        }
    }

    @EventHandler
    private void onTickStart(ClientTickStartEvent e) {
        if (activeSequence == null) {
            return;
        }

        if (!canOperate()) {
            resetRuntimeState();
            return;
        }
        if (shouldCancelOnManualSlotChange() && InvUtils.selected() != activeSequence.lungeSlot()) {
            invalidateSequence();
            return;
        }
        if (shouldWaitForNoUseBeforeSwitchBack() && isUseInputActive()) {
            activeSequence.setUseSeen(true);
        }

        tryFinalizeSequence(activeSequence.token());
    }

    private void tryStartLungeSequence() {
        if (!canTriggerLunge()) {
            return;
        }

        int lungeSlot = findLungeHotbarSlot();
        if (lungeSlot < 0) {
            return;
        }

        int originalSlot = InvUtils.selected();
        if (originalSlot == lungeSlot) {
            return;
        }

        InvUtils.select(lungeSlot);

        if (!switchBack.getVal()) {
            clearSequence();
            return;
        }

        int sequenceToken = ++activeSequenceToken;
        activeSequence = new SwitchSequence(sequenceToken, originalSlot, lungeSlot);
        if (shouldWaitForNoUseBeforeSwitchBack() && isUseInputActive()) {
            activeSequence.setUseSeen(true);
        }

        long delayMs = Math.max(0L, Math.round(switchBackDelay.getRandomizedValue() * 1000.0));
        system.scheduler.runDelayedTask(() -> mc.execute(() -> {
            if (isSequenceValid(sequenceToken)) {
                activeSequence.setDelayElapsed(true);
                tryFinalizeSequence(sequenceToken);
            }
        }), delayMs);
    }

    private boolean canTriggerLunge() {
        return canOperate()
                && mc.player != null
                && mc.player.getAttackStrengthScale(1.0F) >= 1.0F
                && !mc.player.isFallFlying()
                && !isHoldingBlockedItem()
                && !hasValidTriggerBotTarget();
    }

    private boolean canOperate() {
        return isEnabled()
                && PlayerUtils.valid()
                && mc.player != null
                && mc.level != null
                && mc.options != null
                && mc.screen == null;
    }

    private boolean hasValidTriggerBotTarget() {
        EntityHitResult hit = mc.hitResult instanceof EntityHitResult result ? result : null;
        if (hit == null || !(hit.getEntity() instanceof Player target)) {
            return false;
        }

        return target != mc.player
                && target.isAlive()
                && !target.isSpectator()
                && !EntityUtils.shouldCancelCcsAttack(target);
    }

    private int findLungeHotbarSlot() {
        for (int slot = 0; slot <= 8; slot++) {
            if (matchesFixedLungeItem(mc.player.getInventory().getItem(slot))) {
                return slot;
            }
        }
        return -1;
    }

    private boolean isHoldingBlockedItem() {
        return matchesConfiguredHeldItem(mc.player.getMainHandItem());
    }

    private boolean matchesFixedLungeItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }

        String descriptionId = stack.getItem().getDescriptionId().toLowerCase(Locale.ROOT);
        return FIXED_LUNGE_MATCHER.matches(stack, descriptionId);
    }

    private boolean matchesConfiguredHeldItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }

        String descriptionId = stack.getItem().getDescriptionId().toLowerCase(Locale.ROOT);
        for (String rawToken : getBlockedHeldMatchers()) {
            MatcherSpec spec = MatcherSpec.parse(rawToken);
            if (spec.matches(stack, descriptionId)) {
                return true;
            }
        }
        return false;
    }

    private String[] getBlockedHeldMatchers() {
        return blockedHeldItems.getVal().split(",");
    }

    private boolean isUseInputActive() {
        return mc.options != null && mc.options.keyUse != null && mc.options.keyUse.isDown();
    }

    private void tryFinalizeSequence(int sequenceToken) {
        if (!isSequenceValid(sequenceToken)) {
            return;
        }
        if (shouldCancelOnManualSlotChange() && InvUtils.selected() != activeSequence.lungeSlot()) {
            invalidateSequence();
            return;
        }
        if (!activeSequence.delayElapsed()) {
            return;
        }
        if (shouldWaitForNoUseBeforeSwitchBack() && isUseInputActive()) {
            activeSequence.setUseSeen(true);
            return;
        }
        if (shouldWaitForNoUseBeforeSwitchBack() && activeSequence.useSeen() && leftClickHeld) {
            return;
        }
        if (activeSequence.originalSlot() < 0 || activeSequence.originalSlot() > 8) {
            invalidateSequence();
            return;
        }

        InvUtils.select(activeSequence.originalSlot());
        clearSequence();
    }

    private boolean isSequenceValid(int sequenceToken) {
        return activeSequence != null && activeSequence.token() == sequenceToken;
    }

    private void invalidateSequence() {
        clearSequence();
    }

    private void clearSequence() {
        activeSequence = null;
        activeSequenceToken++;
    }

    private void resetRuntimeState() {
        leftClickHeld = false;
        clearSequence();
    }

    private boolean shouldWaitForNoUseBeforeSwitchBack() {
        return waitForNoUseBeforeSwitchBack.getVal();
    }

    private boolean shouldCancelOnManualSlotChange() {
        return cancelSwitchBackOnManualSlotChange.getVal();
    }

    private void configureChildSettings() {
        switchBack.setChangeAction(setting -> scheduleChildSettingsSync());
    }

    private void syncVisibleSettings() {
        List<ModuleSetting<?>> settings = scGeneral.getSettings();
        settings.clear();
        settings.add(blockedHeldItems);
        settings.add(switchBack);
        if (switchBack.getVal()) {
            settings.add(switchBackDelay);
            settings.add(waitForNoUseBeforeSwitchBack);
            settings.add(cancelSwitchBackOnManualSlotChange);
        }
    }

    private void refreshSettingsScreen() {
        if (pendingSettingsScreenRefresh) {
            return;
        }

        pendingSettingsScreenRefresh = true;
        system.scheduler.runDelayedTask(() -> mc.execute(() -> {
            pendingSettingsScreenRefresh = false;
            if (mc.screen instanceof ModuleEditScreen screen && screen.getModule() == this) {
                mc.setScreen(new ModuleEditScreen(this));
            }
        }), 1L);
    }

    private void scheduleChildSettingsSync() {
        if (pendingChildSettingsSync) {
            return;
        }

        pendingChildSettingsSync = true;
        system.scheduler.runDelayedTask(() -> mc.execute(() -> {
            pendingChildSettingsSync = false;
            syncVisibleSettings();
            refreshSettingsScreen();
        }), 1L);
    }

    @Override
    public Collection<ModuleSetting<?>> getPersistentSettings() {
        return List.of(
                switchBackDelay,
                waitForNoUseBeforeSwitchBack,
                cancelSwitchBackOnManualSlotChange
        );
    }

    private static final class MatcherSpec {

        private final String itemFragment;
        private final String enchantFragment;

        private MatcherSpec(String itemFragment, String enchantFragment) {
            this.itemFragment = itemFragment;
            this.enchantFragment = enchantFragment;
        }

        public static MatcherSpec parse(String raw) {
            String cleaned = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
            int open = cleaned.indexOf('[');
            int close = cleaned.lastIndexOf(']');
            if (open >= 0 && close > open) {
                String item = cleaned.substring(0, open).trim();
                String enchant = cleaned.substring(open + 1, close).trim();
                return new MatcherSpec(item, enchant);
            }
            return new MatcherSpec(cleaned, "");
        }

        public boolean matches(ItemStack stack, String descriptionId) {
            if (!itemFragment.isEmpty() && !descriptionId.contains(itemFragment)) {
                return false;
            }
            if (enchantFragment.isEmpty()) {
                return !itemFragment.isEmpty();
            }

            for (Holder<?> enchantment : stack.getEnchantments().keySet()) {
                if (enchantment.getRegisteredName().toLowerCase(Locale.ROOT).contains(enchantFragment)) {
                    return true;
                }
            }
            return false;
        }
    }

    private static final class SwitchSequence {

        private final int token;
        private final int originalSlot;
        private final int lungeSlot;
        private boolean delayElapsed;
        private boolean useSeen;

        private SwitchSequence(int token, int originalSlot, int lungeSlot) {
            this.token = token;
            this.originalSlot = originalSlot;
            this.lungeSlot = lungeSlot;
            this.delayElapsed = false;
            this.useSeen = false;
        }

        public int token() {
            return token;
        }

        public int originalSlot() {
            return originalSlot;
        }

        public int lungeSlot() {
            return lungeSlot;
        }

        public boolean delayElapsed() {
            return delayElapsed;
        }

        public void setDelayElapsed(boolean delayElapsed) {
            this.delayElapsed = delayElapsed;
        }

        public boolean useSeen() {
            return useSeen;
        }

        public void setUseSeen(boolean useSeen) {
            this.useSeen = useSeen;
        }
    }
}
