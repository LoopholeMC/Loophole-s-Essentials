package com.loophole.essentials.module.modules;

import com.loophole.essentials.mixin.AccessorKeyMapping;
import com.loophole.essentials.module.LoopholeListenerModule;
import com.loophole.essentials.module.PersistentSettingProvider;
import com.loophole.essentials.module.settings.MouseButtonSetting;
import com.loophole.essentials.module.settings.RangeDoubleSetting;
import io.github.itzispyder.clickcrystals.events.EventHandler;
import io.github.itzispyder.clickcrystals.events.events.client.KeyPressEvent;
import io.github.itzispyder.clickcrystals.events.events.client.MouseClickEvent;
import io.github.itzispyder.clickcrystals.events.events.client.MouseScrollEvent;
import io.github.itzispyder.clickcrystals.events.events.networking.GameLeaveEvent;
import io.github.itzispyder.clickcrystals.events.events.world.ClientTickStartEvent;
import io.github.itzispyder.clickcrystals.events.listeners.UserInputListener;
import io.github.itzispyder.clickcrystals.gui.ClickType;
import io.github.itzispyder.clickcrystals.gui.screens.ModuleEditScreen;
import io.github.itzispyder.clickcrystals.modules.ModuleSetting;
import io.github.itzispyder.clickcrystals.modules.settings.SettingSection;
import io.github.itzispyder.clickcrystals.util.minecraft.InvUtils;
import io.github.itzispyder.clickcrystals.util.minecraft.PlayerUtils;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;

import java.util.Collection;
import java.util.List;

public class KeyPearlModule extends LoopholeListenerModule implements PersistentSettingProvider {

    private final SettingSection scGeneral = getGeneralSection();

    public final MouseButtonSetting activationButton = scGeneral.add(createMouseButtonSetting()
            .name("activation-button")
            .description("Key or mouse button that throws a pearl. Supports keyboard keys plus Right Click, Middle Click, Mouse 4, and Mouse 5.")
            .def(MouseButtonSetting.Button.NONE)
            .build()
    );

    public final RangeDoubleSetting useDelay = scGeneral.add(createRangeDoubleSetting()
            .name("use-delay")
            .description("Randomized delay before the queued pearl throw after swapping to a hotbar ender pearl.")
            .def(0.000, 0.100)
            .min(0.000)
            .max(0.100)
            .decimalPlaces(3)
            .build()
    );

    public final ModuleSetting<Boolean> switchBack = scGeneral.add(createBoolSetting()
            .name("switch-back")
            .description("Switch back to your original hotbar slot after the pearl sequence completes.")
            .def(true)
            .build()
    );

    public final RangeDoubleSetting switchBackDelay = scGeneral.add(createRangeDoubleSetting()
            .name("switch-back-delay")
            .description("Randomized delay that must finish after the pearl throw before your original slot can be restored.")
            .def(0.000, 0.050)
            .min(0.000)
            .max(0.100)
            .decimalPlaces(3)
            .build()
    );

    public final ModuleSetting<Boolean> cancelSwitchBackOnManualSlotChange = scGeneral.add(createBoolSetting()
            .name("cancel-switch-back-on-manual-slot-change")
            .description("Stay on your manually selected slot instead of switching back if you change hotbar slots before the delayed restore is ready.")
            .def(true)
            .build()
    );

    private ActiveSession activeSession = null;
    private int activeSessionToken = 0;
    private boolean activationHeld = false;
    private boolean activationConsumed = false;
    private boolean pendingChildSettingsSync = false;
    private boolean pendingSettingsScreenRefresh = false;

    public KeyPearlModule() {
        super("key-pearl", "Throw pearl with the selected keybind.");
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
    private void onMouseScroll(MouseScrollEvent e) {
        if (activeSession != null && mc.screen == null && e.isVertical() && e.getDeltaY() != 0.0) {
            activeSession.setManualSlotChanged(true);
        }
    }

    @EventHandler
    private void onKeyPress(KeyPressEvent e) {
        if (activationButton.isKeyboardBinding() && activationButton.matchesKey(e.getKeycode())) {
            if (e.getAction() == ClickType.CLICK) {
                handleActivationPressed();
                return;
            }
            if (e.getAction() == ClickType.RELEASE) {
                handleActivationReleased();
                return;
            }
        }

        if (activeSession == null || mc.options == null || !e.getAction().isDown()) {
            return;
        }

        KeyEvent input = new KeyEvent(e.getKeycode(), e.getScancode(), 0);
        for (int slot = 0; slot < mc.options.keyHotbarSlots.length; slot++) {
            if (mc.options.keyHotbarSlots[slot].matches(input)) {
                activeSession.setManualSlotChanged(true);
                return;
            }
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

        if (InvUtils.selected() != activeSession.pearlSlot()) {
            activeSession.setManualSlotChanged(true);
            if (!activeSession.pearlThrown()) {
                clearSession();
                return;
            }
        }

        tryFinalizeSession(activeSession.token());
    }

    private void tryStartSession() {
        if (activeSession != null || !canStartSession()) {
            return;
        }

        int pearlSlot = findPearlHotbarSlot();
        if (pearlSlot < 0) {
            return;
        }

        int originalSlot = InvUtils.selected();
        if (originalSlot != pearlSlot) {
            InvUtils.select(pearlSlot);
        }

        int sessionToken = ++activeSessionToken;
        activeSession = new ActiveSession(sessionToken, originalSlot, pearlSlot);
        schedulePearlThrow(sessionToken);
    }

    private boolean canStartSession() {
        return activationHeld
                && !activationConsumed
                && canOperate()
                && !activationButton.isNone()
                && !isPearlOnCooldown();
    }

    private boolean canOperate() {
        return isEnabled()
                && PlayerUtils.valid()
                && mc.player != null
                && mc.level != null
                && mc.options != null
                && mc.screen == null;
    }

    private int findPearlHotbarSlot() {
        for (int slot = 0; slot <= 8; slot++) {
            if (mc.player.getInventory().getItem(slot).is(Items.ENDER_PEARL)) {
                return slot;
            }
        }
        return -1;
    }

    private long getUseDelayMs() {
        return Math.max(0L, Math.round(useDelay.getRandomizedValue() * 1000.0));
    }

    private long getSwitchBackDelayMs() {
        return Math.max(0L, Math.round(switchBackDelay.getRandomizedValue() * 1000.0));
    }

    private void schedulePearlThrow(int sessionToken) {
        long delayMs = getUseDelayMs();
        system.scheduler.runDelayedTask(() -> mc.execute(() -> tryThrowPearl(sessionToken)), delayMs);
    }

    private void tryThrowPearl(int sessionToken) {
        if (!isSessionValid(sessionToken)) {
            return;
        }
        if (!canOperate() || activeSession.manualSlotChanged()) {
            clearSession();
            return;
        }
        if (InvUtils.selected() != activeSession.pearlSlot()
                || !mc.player.getMainHandItem().is(Items.ENDER_PEARL)
                || isPearlOnCooldown()) {
            clearSession();
            return;
        }

        activeSession.setPearlThrown(true);
        queueUseClick();

        if (switchBack.getVal()) {
            scheduleSwitchBackReady(sessionToken);
        }
        else {
            clearSession();
        }
    }

    private void scheduleSwitchBackReady(int sessionToken) {
        long delayMs = getSwitchBackDelayMs();
        system.scheduler.runDelayedTask(() -> mc.execute(() -> {
            if (!isSessionValid(sessionToken)) {
                return;
            }
            activeSession.setSwitchBackDelayElapsed(true);
            tryFinalizeSession(sessionToken);
        }), delayMs);
    }

    private void tryFinalizeSession(int sessionToken) {
        if (!isSessionValid(sessionToken) || !activeSession.pearlThrown()) {
            return;
        }

        if (!switchBack.getVal()) {
            clearSession();
            return;
        }
        if (!activeSession.switchBackDelayElapsed()) {
            return;
        }
        if (activeSession.manualSlotChanged() && cancelSwitchBackOnManualSlotChange.getVal()) {
            clearSession();
            return;
        }
        if (activeSession.originalSlot() < 0 || activeSession.originalSlot() > 8) {
            clearSession();
            return;
        }

        InvUtils.select(activeSession.originalSlot());
        clearSession();
    }

    private boolean isSessionValid(int sessionToken) {
        return activeSession != null && activeSession.token() == sessionToken;
    }

    private boolean isPearlOnCooldown() {
        return mc.player != null && mc.player.getCooldowns().isOnCooldown(new ItemStack(Items.ENDER_PEARL));
    }

    private void queueUseClick() {
        mc.execute(() -> {
            AccessorKeyMapping keyUse = (AccessorKeyMapping) mc.options.keyUse;
            KeyMapping.click(keyUse.loopholeEssentials$getBoundKey());
        });
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
            settings.add(switchBackDelay);
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
                cancelSwitchBackOnManualSlotChange
        );
    }

    private static final class ActiveSession {

        private final int token;
        private final int originalSlot;
        private final int pearlSlot;
        private boolean pearlThrown;
        private boolean switchBackDelayElapsed;
        private boolean manualSlotChanged;

        private ActiveSession(int token, int originalSlot, int pearlSlot) {
            this.token = token;
            this.originalSlot = originalSlot;
            this.pearlSlot = pearlSlot;
            this.pearlThrown = false;
            this.switchBackDelayElapsed = false;
            this.manualSlotChanged = false;
        }

        public int token() {
            return token;
        }

        public int originalSlot() {
            return originalSlot;
        }

        public int pearlSlot() {
            return pearlSlot;
        }

        public boolean pearlThrown() {
            return pearlThrown;
        }

        public void setPearlThrown(boolean pearlThrown) {
            this.pearlThrown = pearlThrown;
        }

        public boolean switchBackDelayElapsed() {
            return switchBackDelayElapsed;
        }

        public void setSwitchBackDelayElapsed(boolean switchBackDelayElapsed) {
            this.switchBackDelayElapsed = switchBackDelayElapsed;
        }

        public boolean manualSlotChanged() {
            return manualSlotChanged;
        }

        public void setManualSlotChanged(boolean manualSlotChanged) {
            this.manualSlotChanged = manualSlotChanged;
        }
    }
}
