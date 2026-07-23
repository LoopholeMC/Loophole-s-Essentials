package com.loophole.essentials.module.modules;

import com.loophole.essentials.mixin.AccessorKeyMapping;
import com.loophole.essentials.module.LoopholeListenerModule;
import com.loophole.essentials.module.PersistentSettingProvider;
import com.loophole.essentials.module.settings.RangeDoubleSetting;
import io.github.itzispyder.clickcrystals.events.EventHandler;
import io.github.itzispyder.clickcrystals.events.events.client.KeyPressEvent;
import io.github.itzispyder.clickcrystals.events.events.client.MouseScrollEvent;
import io.github.itzispyder.clickcrystals.events.events.networking.GameLeaveEvent;
import io.github.itzispyder.clickcrystals.events.events.world.ClientTickStartEvent;
import io.github.itzispyder.clickcrystals.gui.ClickType;
import io.github.itzispyder.clickcrystals.gui.screens.ModuleEditScreen;
import io.github.itzispyder.clickcrystals.modules.ModuleSetting;
import io.github.itzispyder.clickcrystals.modules.settings.SettingSection;
import io.github.itzispyder.clickcrystals.util.minecraft.InvUtils;
import io.github.itzispyder.clickcrystals.util.minecraft.PlayerUtils;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;

import java.util.Collection;
import java.util.List;

public class ElytraSwitcherModule extends LoopholeListenerModule implements PersistentSettingProvider {

    private static final long ROCKET_BOOST_WINDOW_MS = 1500L;
    private static final long FIREWORK_ATTEMPT_WINDOW_MS = 100L;

    private final SettingSection scGeneral = getGeneralSection();

    public final RangeDoubleSetting fireworkDelay = scGeneral.add(createRangeDoubleSetting()
            .name("firework-delay")
            .description("Randomized delay after equipping a hotbar Elytra before switching to a hotbar firework and attempting the boost.")
            .def(0.000, 0.100)
            .min(0.000)
            .max(0.100)
            .decimalPlaces(3)
            .build()
    );

    public final ModuleSetting<Boolean> switchBack = scGeneral.add(createBoolSetting()
            .name("switch-back")
            .description("Switch back to the hotbar slot the Elytra came from after the queued rocket use finishes.")
            .def(true)
            .build()
    );

    public final RangeDoubleSetting switchBackDelay = scGeneral.add(createRangeDoubleSetting()
            .name("switch-back-delay")
            .description("Randomized delay after the rocket use before the Elytra's original hotbar slot can be restored.")
            .def(0.050, 0.050)
            .min(0.000)
            .max(0.100)
            .decimalPlaces(3)
            .build()
    );

    public final ModuleSetting<Boolean> cancelSwitchBackOnManualSlotChange = scGeneral.add(createBoolSetting()
            .name("cancel-switch-back-on-manual-slot-change")
            .description("Stay on the slot you manually changed to instead of restoring the Elytra's original hotbar slot.")
            .def(true)
            .build()
    );

    private ActiveSession activeSession = null;
    private int activeSessionToken = 0;
    private long predictedRocketBoostUntilMs = 0L;
    private boolean pendingChildSettingsSync = false;
    private boolean pendingSettingsScreenRefresh = false;

    public ElytraSwitcherModule() {
        super("elytra-switcher", "Press Space in midair to equip a hotbar Elytra with a normal right-click, then after a short random delay switch to a hotbar firework and boost unless a recent rocket boost is still active.");
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
    private void onMouseScroll(MouseScrollEvent e) {
        if (activeSession == null || mc.screen != null || !e.isVertical() || e.getDeltaY() == 0.0) {
            return;
        }
        activeSession.setManualSlotChanged(true);
    }

    @EventHandler
    private void onTickStart(ClientTickStartEvent e) {
        if (activeSession == null) {
            return;
        }
        if (!canOperate()) {
            resetRuntimeState();
            return;
        }
        if (System.currentTimeMillis() > activeSession.expiresAtMs()) {
            clearSession();
            return;
        }

        if (!activeSession.rocketUsed()) {
            tryLaunchFirework(activeSession.token());
            return;
        }

        trySwitchBackToOriginalSlot(activeSession.token());
    }

    @EventHandler
    private void onKeyPress(KeyPressEvent e) {
        if (e.getKeycode() == GLFW.GLFW_KEY_SPACE && e.getAction() == ClickType.CLICK) {
            tryStartSession();
            return;
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

    private void tryStartSession() {
        if (activeSession != null || !canStartSession()) {
            return;
        }

        int elytraSlot = findHotbarSlot(Items.ELYTRA);
        int fireworkSlot = findHotbarSlot(Items.FIREWORK_ROCKET);
        if (elytraSlot < 0 || fireworkSlot < 0) {
            return;
        }

        InvUtils.select(elytraSlot);
        queueUseClick();

        int sessionToken = ++activeSessionToken;
        long readyAtMs = System.currentTimeMillis() + getFireworkDelayMs();
        activeSession = new ActiveSession(sessionToken, elytraSlot, fireworkSlot, readyAtMs, readyAtMs + FIREWORK_ATTEMPT_WINDOW_MS);
        scheduleFireworkAttempt(sessionToken);
    }

    private boolean canStartSession() {
        return canOperate()
                && mc.player != null
                && !mc.player.onGround();
    }

    private boolean canOperate() {
        return isEnabled()
                && PlayerUtils.valid()
                && mc.player != null
                && mc.level != null
                && mc.options != null
                && mc.screen == null;
    }

    private int findHotbarSlot(net.minecraft.world.item.Item item) {
        for (int slot = 0; slot <= 8; slot++) {
            if (mc.player.getInventory().getItem(slot).is(item)) {
                return slot;
            }
        }
        return -1;
    }

    private long getFireworkDelayMs() {
        return Math.max(0L, Math.round(fireworkDelay.getRandomizedValue() * 1000.0));
    }

    private void scheduleFireworkAttempt(int sessionToken) {
        long delayMs = Math.max(0L, activeSession == null || activeSession.token() != sessionToken
                ? getFireworkDelayMs()
                : activeSession.readyAtMs() - System.currentTimeMillis());
        system.scheduler.runDelayedTask(() -> mc.execute(() -> tryLaunchFirework(sessionToken)), delayMs);
    }

    private void tryLaunchFirework(int sessionToken) {
        if (!isSessionValid(sessionToken) || !canOperate()) {
            clearSession();
            return;
        }
        if (System.currentTimeMillis() < activeSession.readyAtMs()) {
            return;
        }
        if (isRocketBoostActive()) {
            clearSession();
            return;
        }
        if (!isReadyForFireworkBoost()) {
            return;
        }

        int fireworkSlot = activeSession.fireworkSlot();
        if (fireworkSlot < 0
                || fireworkSlot > 8
                || !mc.player.getInventory().getItem(fireworkSlot).is(Items.FIREWORK_ROCKET)) {
            clearSession();
            return;
        }

        InvUtils.select(fireworkSlot);
        predictedRocketBoostUntilMs = System.currentTimeMillis() + ROCKET_BOOST_WINDOW_MS;
        activeSession.setRocketUsed(true);
        queueUseClick();

        if (!switchBack.getVal()) {
            clearSession();
            return;
        }

        long switchBackReadyAtMs = System.currentTimeMillis() + Math.max(50L, getSwitchBackDelayMs());
        activeSession.setSwitchBackReadyAtMs(switchBackReadyAtMs);
        activeSession.setExpiresAtMs(Math.max(activeSession.expiresAtMs(), switchBackReadyAtMs + FIREWORK_ATTEMPT_WINDOW_MS));
    }

    private long getSwitchBackDelayMs() {
        return Math.max(0L, Math.round(switchBackDelay.getRandomizedValue() * 1000.0));
    }

    private void trySwitchBackToOriginalSlot(int sessionToken) {
        if (!isSessionValid(sessionToken) || !activeSession.rocketUsed()) {
            return;
        }
        if (!switchBack.getVal()) {
            clearSession();
            return;
        }
        if (activeSession.manualSlotChanged() && cancelSwitchBackOnManualSlotChange.getVal()) {
            clearSession();
            return;
        }
        if (System.currentTimeMillis() < activeSession.switchBackReadyAtMs()) {
            return;
        }

        int originalElytraSlot = activeSession.originalElytraSlot();
        if (originalElytraSlot < 0 || originalElytraSlot > 8) {
            clearSession();
            return;
        }

        InvUtils.select(originalElytraSlot);
        clearSession();
    }

    private boolean isReadyForFireworkBoost() {
        return mc.player != null
                && mc.player.isFallFlying()
                && mc.player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA);
    }

    private boolean isRocketBoostActive() {
        return mc.player != null
                && mc.player.isFallFlying()
                && System.currentTimeMillis() < predictedRocketBoostUntilMs;
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

    private void configureChildSettings() {
        switchBack.setChangeAction(setting -> scheduleChildSettingsSync());
    }

    private void syncVisibleSettings() {
        List<ModuleSetting<?>> settings = scGeneral.getSettings();
        settings.clear();
        settings.add(fireworkDelay);
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

    private void clearSession() {
        activeSession = null;
        activeSessionToken++;
    }

    private void resetRuntimeState() {
        predictedRocketBoostUntilMs = 0L;
        clearSession();
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
        private final int originalElytraSlot;
        private final int fireworkSlot;
        private final long readyAtMs;
        private long expiresAtMs;
        private boolean rocketUsed;
        private long switchBackReadyAtMs;
        private boolean manualSlotChanged;

        private ActiveSession(int token, int originalElytraSlot, int fireworkSlot, long readyAtMs, long expiresAtMs) {
            this.token = token;
            this.originalElytraSlot = originalElytraSlot;
            this.fireworkSlot = fireworkSlot;
            this.readyAtMs = readyAtMs;
            this.expiresAtMs = expiresAtMs;
            this.rocketUsed = false;
            this.switchBackReadyAtMs = Long.MAX_VALUE;
            this.manualSlotChanged = false;
        }

        public int token() {
            return token;
        }

        public int fireworkSlot() {
            return fireworkSlot;
        }

        public long readyAtMs() {
            return readyAtMs;
        }

        public int originalElytraSlot() {
            return originalElytraSlot;
        }

        public long expiresAtMs() {
            return expiresAtMs;
        }

        public void setExpiresAtMs(long expiresAtMs) {
            this.expiresAtMs = expiresAtMs;
        }

        public boolean rocketUsed() {
            return rocketUsed;
        }

        public void setRocketUsed(boolean rocketUsed) {
            this.rocketUsed = rocketUsed;
        }

        public long switchBackReadyAtMs() {
            return switchBackReadyAtMs;
        }

        public void setSwitchBackReadyAtMs(long switchBackReadyAtMs) {
            this.switchBackReadyAtMs = switchBackReadyAtMs;
        }

        public boolean manualSlotChanged() {
            return manualSlotChanged;
        }

        public void setManualSlotChanged(boolean manualSlotChanged) {
            this.manualSlotChanged = manualSlotChanged;
        }
    }
}
