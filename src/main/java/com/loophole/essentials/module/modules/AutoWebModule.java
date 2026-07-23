package com.loophole.essentials.module.modules;

import com.loophole.essentials.mixin.AccessorKeyMapping;
import com.loophole.essentials.module.LoopholeListenerModule;
import com.loophole.essentials.module.PersistentSettingProvider;
import com.loophole.essentials.module.settings.MouseButtonSetting;
import com.loophole.essentials.module.settings.RangeDoubleSetting;
import io.github.itzispyder.clickcrystals.events.EventHandler;
import io.github.itzispyder.clickcrystals.events.events.client.KeyPressEvent;
import io.github.itzispyder.clickcrystals.events.events.client.MouseClickEvent;
import io.github.itzispyder.clickcrystals.events.events.networking.GameLeaveEvent;
import io.github.itzispyder.clickcrystals.events.events.world.ClientTickStartEvent;
import io.github.itzispyder.clickcrystals.gui.ClickType;
import io.github.itzispyder.clickcrystals.gui.screens.ModuleEditScreen;
import io.github.itzispyder.clickcrystals.modules.ModuleSetting;
import io.github.itzispyder.clickcrystals.modules.settings.SettingSection;
import io.github.itzispyder.clickcrystals.events.listeners.UserInputListener;
import io.github.itzispyder.clickcrystals.util.minecraft.InvUtils;
import io.github.itzispyder.clickcrystals.util.minecraft.PlayerUtils;
import net.minecraft.client.KeyMapping;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.lwjgl.glfw.GLFW;

import java.util.Collection;
import java.util.List;

public class AutoWebModule extends LoopholeListenerModule implements PersistentSettingProvider {

    private final SettingSection scGeneral = getGeneralSection();

    public final MouseButtonSetting activationButton = scGeneral.add(createMouseButtonSetting()
            .name("activation-button")
            .description("Key or mouse button that activates Auto Web while held. Supports keyboard keys plus Right Click, Middle Click, Mouse 4, and Mouse 5.")
            .def(MouseButtonSetting.Button.NONE)
            .build()
    );

    public final RangeDoubleSetting useDelay = scGeneral.add(createRangeDoubleSetting()
            .name("use-delay")
            .description("Randomized delay between queued cobweb placements while the activation button stays held and a block is targeted.")
            .def(0.000, 0.050)
            .min(0.000)
            .max(0.500)
            .decimalPlaces(3)
            .build()
    );

    public final ModuleSetting<Boolean> switchBack = scGeneral.add(createBoolSetting()
            .name("switch-back")
            .description("Switch back to your original hotbar slot when you release the activation button.")
            .def(true)
            .build()
    );

    public final ModuleSetting<Boolean> cancelSwitchBackOnManualSlotChange = scGeneral.add(createBoolSetting()
            .name("cancel-switch-back-on-manual-slot-change")
            .description("Stay on your manually selected slot instead of switching back if you changed hotbar slots before releasing the activation button.")
            .def(true)
            .build()
    );

    private ActiveSession activeSession = null;
    private int activeSessionToken = 0;
    private int scheduledUseToken = 0;
    private boolean useTaskScheduled = false;
    private boolean activationHeld = false;
    private boolean pendingChildSettingsSync = false;
    private boolean pendingSettingsScreenRefresh = false;

    public AutoWebModule() {
        super("auto-web", "Switches to a hotbar cobweb while a selected key or supported mouse button is held, places webs with randomized right-click timing while a valid block is targeted, and optionally switches back on release.");
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
        if (!canOperate()) {
            resetRuntimeState();
            return;
        }
        if (activeSession == null) {
            if (activationHeld) {
                tryStartSession();
            }
            return;
        }

        if (InvUtils.selected() != activeSession.cobwebSlot()) {
            activeSession.setManualSlotChanged(true);
            cancelScheduledUse();
        }
        if (!activationHeld || activeSession.manualSlotChanged()) {
            return;
        }
        if (!shouldContinueUsingSession()) {
            cancelScheduledUse();
            return;
        }
        if (!useTaskScheduled) {
            scheduleNextUse(activeSession.token());
        }
    }

    private void tryStartSession() {
        if (!canStartSession()) {
            return;
        }

        int cobwebSlot = findCobwebHotbarSlot();
        if (cobwebSlot < 0) {
            return;
        }

        int originalSlot = InvUtils.selected();
        if (originalSlot != cobwebSlot) {
            InvUtils.select(cobwebSlot);
        }

        activeSession = new ActiveSession(++activeSessionToken, originalSlot, cobwebSlot);
        cancelScheduledUse();
        if (!shouldContinueUsingSession()) {
            return;
        }

        if (!isRightClickActivation()) {
            queueUseClick();
        }
        if (shouldContinueUsingSession()) {
            scheduleNextUse(activeSession.token());
        }
    }

    private boolean canStartSession() {
        return activationHeld
                && canOperate()
                && !activationButton.isNone()
                && isTargetingBlock();
    }

    private boolean shouldContinueUsingSession() {
        return activeSession != null
                && activationHeld
                && canOperate()
                && !activeSession.manualSlotChanged()
                && InvUtils.selected() == activeSession.cobwebSlot()
                && mc.player.getMainHandItem().is(Items.COBWEB)
                && isTargetingBlock();
    }

    private boolean canOperate() {
        return isEnabled()
                && PlayerUtils.valid()
                && mc.player != null
                && mc.level != null
                && mc.options != null
                && mc.screen == null;
    }

    private boolean isTargetingBlock() {
        if (!(mc.hitResult instanceof BlockHitResult hit) || mc.hitResult.getType() != HitResult.Type.BLOCK) {
            return false;
        }
        return !mc.level.getBlockState(hit.getBlockPos()).isAir();
    }

    private boolean isActivationButton(int button) {
        return activationButton.matches(button);
    }

    private boolean isRightClickActivation() {
        return activationButton.matchesMouse(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
    }

    private int findCobwebHotbarSlot() {
        for (int slot = 0; slot <= 8; slot++) {
            if (mc.player.getInventory().getItem(slot).is(Items.COBWEB)) {
                return slot;
            }
        }
        return -1;
    }

    private long getRandomizedDelayMs() {
        return Math.max(0L, Math.round(useDelay.getRandomizedValue() * 1000.0));
    }

    private void scheduleNextUse(int sessionToken) {
        if (!isSessionValid(sessionToken)) {
            return;
        }

        int useToken = ++scheduledUseToken;
        useTaskScheduled = true;
        long delayMs = getRandomizedDelayMs();
        system.scheduler.runDelayedTask(() -> mc.execute(() -> tryQueueUse(sessionToken, useToken)), delayMs);
    }

    private void tryQueueUse(int sessionToken, int useToken) {
        if (!isScheduledUseValid(sessionToken, useToken)) {
            return;
        }

        useTaskScheduled = false;
        if (!shouldContinueUsingSession()) {
            return;
        }

        queueUseClick();
        if (shouldContinueUsingSession()) {
            scheduleNextUse(sessionToken);
        }
    }

    private boolean isSessionValid(int sessionToken) {
        return activeSession != null && activeSession.token() == sessionToken;
    }

    private boolean isScheduledUseValid(int sessionToken, int useToken) {
        return useTaskScheduled
                && scheduledUseToken == useToken
                && isSessionValid(sessionToken);
    }

    private void queueUseClick() {
        mc.execute(() -> {
            AccessorKeyMapping keyUse = (AccessorKeyMapping) mc.options.keyUse;
            KeyMapping.click(keyUse.loopholeEssentials$getBoundKey());
        });
    }

    private void cancelScheduledUse() {
        useTaskScheduled = false;
        scheduledUseToken++;
    }

    private void finalizeSessionOnRelease() {
        if (activeSession == null) {
            return;
        }

        if (switchBack.getVal()
                && !(activeSession.manualSlotChanged() && shouldCancelSwitchBackOnManualSlotChange())
                && activeSession.originalSlot() >= 0
                && activeSession.originalSlot() <= 8) {
            InvUtils.select(activeSession.originalSlot());
        }

        clearSession();
    }

    private void clearSession() {
        activeSession = null;
        activeSessionToken++;
        cancelScheduledUse();
    }

    private void resetRuntimeState() {
        activationHeld = false;
        clearSession();
    }

    private void handleActivationPressed() {
        if (activationHeld) {
            return;
        }
        activationHeld = true;
        tryStartSession();
    }

    private void handleActivationReleased() {
        if (!activationHeld) {
            return;
        }
        activationHeld = false;
        cancelScheduledUse();
        finalizeSessionOnRelease();
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

    private boolean shouldCancelSwitchBackOnManualSlotChange() {
        return cancelSwitchBackOnManualSlotChange.getVal();
    }

    private void configureChildSettings() {
        switchBack.setChangeAction(setting -> scheduleChildSettingsSync());
    }

    private void syncVisibleSettings() {
        List<ModuleSetting<?>> settings = scGeneral.getSettings();
        settings.clear();
        settings.add(activationButton);
        settings.add(useDelay);
        settings.add(switchBack);
        if (switchBack.getVal()) {
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
        return List.of(cancelSwitchBackOnManualSlotChange);
    }

    private static final class ActiveSession {

        private final int token;
        private final int originalSlot;
        private final int cobwebSlot;
        private boolean manualSlotChanged;

        private ActiveSession(int token, int originalSlot, int cobwebSlot) {
            this.token = token;
            this.originalSlot = originalSlot;
            this.cobwebSlot = cobwebSlot;
            this.manualSlotChanged = false;
        }

        public int token() {
            return token;
        }

        public int originalSlot() {
            return originalSlot;
        }

        public int cobwebSlot() {
            return cobwebSlot;
        }

        public boolean manualSlotChanged() {
            return manualSlotChanged;
        }

        public void setManualSlotChanged(boolean manualSlotChanged) {
            this.manualSlotChanged = manualSlotChanged;
        }
    }
}
