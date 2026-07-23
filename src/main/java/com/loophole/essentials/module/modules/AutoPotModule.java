package com.loophole.essentials.module.modules;

import com.loophole.essentials.mixin.AccessorKeyMapping;
import com.loophole.essentials.module.LoopholeListenerModule;
import com.loophole.essentials.module.PotionMatcher;
import com.loophole.essentials.module.settings.MouseButtonSetting;
import com.loophole.essentials.module.settings.RangeDoubleSetting;
import io.github.itzispyder.clickcrystals.events.EventHandler;
import io.github.itzispyder.clickcrystals.events.events.client.KeyPressEvent;
import io.github.itzispyder.clickcrystals.events.events.client.MouseClickEvent;
import io.github.itzispyder.clickcrystals.events.events.networking.GameLeaveEvent;
import io.github.itzispyder.clickcrystals.events.events.world.ClientTickStartEvent;
import io.github.itzispyder.clickcrystals.events.listeners.UserInputListener;
import io.github.itzispyder.clickcrystals.gui.ClickType;
import io.github.itzispyder.clickcrystals.modules.ModuleSetting;
import io.github.itzispyder.clickcrystals.modules.settings.SettingSection;
import io.github.itzispyder.clickcrystals.util.minecraft.InvUtils;
import io.github.itzispyder.clickcrystals.util.minecraft.PlayerUtils;
import net.minecraft.client.KeyMapping;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

public class AutoPotModule extends LoopholeListenerModule {

    private final SettingSection scGeneral = getGeneralSection();

    public final MouseButtonSetting activationButton = scGeneral.add(createMouseButtonSetting()
            .name("activation-button")
            .description("Key or mouse button that throws a matching splash potion once per press. Supports keyboard keys plus Right Click, Middle Click, Mouse 4, and Mouse 5.")
            .def(MouseButtonSetting.Button.NONE)
            .build()
    );

    public final ModuleSetting<String> allowedPotions = scGeneral.add(createStringSetting()
            .name("allowed-potions")
            .description("Comma-separated splash potion names or effect names to use, for example healing,strength or instant_health,strength. Matching ignores vanilla long or strong potion variants, so one entry works across all levels and durations.")
            .def("instant_health")
            .build()
    );

    public final RangeDoubleSetting useDelay = scGeneral.add(createRangeDoubleSetting()
            .name("use-delay")
            .description("Randomized delay before the queued splash potion throw after a valid press.")
            .def(0.000, 0.010)
            .min(0.000)
            .max(0.100)
            .decimalPlaces(3)
            .build()
    );

    private ActiveSession activeSession = null;
    private int activeSessionToken = 0;
    private boolean activationHeld = false;
    private boolean activationConsumed = false;

    public AutoPotModule() {
        super("auto-pot", "Throws a matching splash potion on demand.");
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
        if (!activationButton.isMouseBinding() || !activationButton.matchesMouse(e.getButton())) {
            return;
        }

        if (e.getAction() == ClickType.CLICK) {
            handleActivationPressed();
            return;
        }
        if (e.getAction() == ClickType.RELEASE) {
            handleActivationReleased();
        }
    }

    @EventHandler
    private void onKeyPress(KeyPressEvent e) {
        if (!activationButton.isKeyboardBinding() || !activationButton.matchesKey(e.getKeycode())) {
            return;
        }

        if (e.getAction() == ClickType.CLICK) {
            handleActivationPressed();
            return;
        }
        if (e.getAction() == ClickType.RELEASE) {
            handleActivationReleased();
        }
    }

    @EventHandler
    private void onTickStart(ClientTickStartEvent e) {
        syncActivationState();
        if (activeSession == null) {
            return;
        }

        if (!canOperate()) {
            resetRuntimeState();
            return;
        }

        if (activeSession.requiresMainHandPotion()
                && activeSession.selectedSlot() >= 0
                && InvUtils.selected() != activeSession.selectedSlot()) {
            clearSession();
        }
    }

    private void tryStartSession() {
        if (activeSession != null || !canStartSession()) {
            return;
        }

        PotionUsePlan plan = findPotionUsePlan();
        if (plan == null) {
            return;
        }

        if (plan.requiresSlotSwap() && InvUtils.selected() != plan.selectedSlot()) {
            InvUtils.select(plan.selectedSlot());
        }

        int sessionToken = ++activeSessionToken;
        activeSession = new ActiveSession(sessionToken, plan.selectedSlot(), plan.requiresMainHandPotion());
        schedulePotionUse(sessionToken);
    }

    private boolean canStartSession() {
        return activationHeld
                && !activationConsumed
                && canOperate()
                && !activationButton.isNone()
                && !isAttackInputActive();
    }

    private boolean canOperate() {
        return isEnabled()
                && PlayerUtils.valid()
                && mc.player != null
                && mc.level != null
                && mc.options != null
                && mc.screen == null;
    }

    private PotionUsePlan findPotionUsePlan() {
        if (matchesConfiguredPotion(mc.player.getMainHandItem())) {
            return PotionUsePlan.mainHandCurrent(InvUtils.selected());
        }
        if (matchesConfiguredPotion(mc.player.getOffhandItem())) {
            return PotionUsePlan.offhand();
        }

        int hotbarSlot = findMatchingHotbarSlot();
        if (hotbarSlot >= 0) {
            return PotionUsePlan.mainHandSwap(hotbarSlot);
        }
        return null;
    }

    private int findMatchingHotbarSlot() {
        for (int slot = 0; slot <= 8; slot++) {
            if (matchesConfiguredPotion(mc.player.getInventory().getItem(slot))) {
                return slot;
            }
        }
        return -1;
    }

    private boolean matchesConfiguredPotion(ItemStack stack) {
        return PotionMatcher.matchesConfiguredSplashPotion(stack, allowedPotions.getVal());
    }

    private boolean isAttackInputActive() {
        if (mc.options == null || mc.options.keyAttack == null) {
            return false;
        }

        AccessorKeyMapping keyAttack = (AccessorKeyMapping) mc.options.keyAttack;
        return mc.options.keyAttack.isDown() || keyAttack.loopholeEssentials$getClickCount() > 0;
    }

    private long getUseDelayMs() {
        return Math.max(0L, Math.round(useDelay.getRandomizedValue() * 1000.0));
    }

    private void schedulePotionUse(int sessionToken) {
        long delayMs = getUseDelayMs();
        system.scheduler.runDelayedTask(() -> mc.execute(() -> tryUsePotion(sessionToken)), delayMs);
    }

    private void tryUsePotion(int sessionToken) {
        if (!isSessionValid(sessionToken)) {
            return;
        }
        if (!canOperate() || isAttackInputActive()) {
            clearSession();
            return;
        }

        if (activeSession.requiresMainHandPotion()) {
            if (activeSession.selectedSlot() < 0
                    || InvUtils.selected() != activeSession.selectedSlot()
                    || !matchesConfiguredPotion(mc.player.getMainHandItem())) {
                clearSession();
                return;
            }
        }
        else if (!matchesConfiguredPotion(mc.player.getOffhandItem())) {
            clearSession();
            return;
        }

        queueUseClick();
        clearSession();
    }

    private void queueUseClick() {
        mc.execute(() -> {
            AccessorKeyMapping keyUse = (AccessorKeyMapping) mc.options.keyUse;
            KeyMapping.click(keyUse.loopholeEssentials$getBoundKey());
        });
    }

    private boolean isSessionValid(int sessionToken) {
        return activeSession != null && activeSession.token() == sessionToken;
    }

    private void clearSession() {
        activeSession = null;
        activeSessionToken++;
    }

    private void resetRuntimeState() {
        activationHeld = false;
        activationConsumed = false;
        clearSession();
    }

    private void handleActivationPressed() {
        if (activationHeld || activationConsumed) {
            return;
        }
        activationHeld = true;
        tryStartSession();
        if (activeSession != null) {
            activationConsumed = true;
        }
    }

    private void handleActivationReleased() {
        if (!activationHeld) {
            return;
        }
        activationHeld = false;
        activationConsumed = false;
    }

    private void syncActivationState() {
        boolean heldNow = isActivationHeldNow();
        if (heldNow == activationHeld) {
            return;
        }
        if (heldNow) {
            handleActivationPressed();
        }
        else {
            handleActivationReleased();
        }
    }

    private boolean isActivationHeldNow() {
        if (!isEnabled()
                || mc == null
                || mc.getWindow() == null
                || mc.screen != null
                || activationButton.isNone()) {
            return false;
        }

        if (activationButton.isKeyboardBinding()) {
            return UserInputListener.isKeyPressed(activationButton.getKey());
        }
        if (activationButton.isMouseBinding()) {
            return GLFW.glfwGetMouseButton(mc.getWindow().handle(), activationButton.getButton()) == GLFW.GLFW_PRESS;
        }
        return false;
    }

    private record PotionUsePlan(int selectedSlot, boolean requiresMainHandPotion) {

        public static PotionUsePlan mainHandCurrent(int selectedSlot) {
            return new PotionUsePlan(selectedSlot, true);
        }

        public static PotionUsePlan mainHandSwap(int selectedSlot) {
            return new PotionUsePlan(selectedSlot, true);
        }

        public static PotionUsePlan offhand() {
            return new PotionUsePlan(-1, false);
        }

        public boolean requiresSlotSwap() {
            return requiresMainHandPotion && selectedSlot >= 0 && InvUtils.selected() != selectedSlot;
        }
    }

    private record ActiveSession(int token, int selectedSlot, boolean requiresMainHandPotion) {
    }
}
