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
import io.github.itzispyder.clickcrystals.events.listeners.UserInputListener;
import io.github.itzispyder.clickcrystals.gui.ClickType;
import io.github.itzispyder.clickcrystals.gui.screens.ModuleEditScreen;
import io.github.itzispyder.clickcrystals.modules.ModuleSetting;
import io.github.itzispyder.clickcrystals.modules.settings.SettingSection;
import io.github.itzispyder.clickcrystals.util.minecraft.PlayerUtils;
import net.minecraft.client.KeyMapping;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.lwjgl.glfw.GLFW;

import java.util.Collection;
import java.util.List;
import java.util.Locale;

public class BlockSpamModule extends LoopholeListenerModule implements PersistentSettingProvider {

    private final SettingSection scGeneral = getGeneralSection();

    public final MouseButtonSetting activationButton = scGeneral.add(createMouseButtonSetting()
            .name("activation-button")
            .description("Key or mouse button that repeatedly places matching held blocks while a real block outline target is highlighted. Supports keyboard keys plus Right Click, Middle Click, Mouse 4, and Mouse 5.")
            .def(MouseButtonSetting.Button.NONE)
            .build()
    );

    public final ModuleSetting<String> allowedHeldItems = scGeneral.add(createStringSetting()
            .name("allowed-held-items")
            .description("Comma-separated held-item name fragments Block Spam can place with, for example planks,logs,cobblestone.")
            .def("plank,log,cobblestone")
            .build()
    );

    public final RangeDoubleSetting startDelay = scGeneral.add(createRangeDoubleSetting()
            .name("start-delay")
            .description("Randomized delay after pressing the activation button before the repeating use-delay cycle starts.")
            .def(0.000, 0.000)
            .min(0.000)
            .max(0.500)
            .decimalPlaces(3)
            .build()
    );

    public final ModuleSetting<Boolean> immediateFirstUse = scGeneral.add(createBoolSetting()
            .name("immediate-first-use")
            .description("Queue one immediate block placement when a non-Right-Click activation button is first pressed, then wait for the delayed repeat cycle.")
            .def(true)
            .build()
    );

    public final RangeDoubleSetting useDelay = scGeneral.add(createRangeDoubleSetting()
            .name("use-delay")
            .description("Randomized delay between queued right-click placements while the activation button stays held on a valid highlighted block target.")
            .def(0.000, 0.050)
            .min(0.000)
            .max(0.500)
            .decimalPlaces(3)
            .build()
    );

    private int activeSequenceToken = 0;
    private int scheduledUseToken = 0;
    private boolean activationHeld = false;
    private boolean useTaskScheduled = false;
    private boolean useLoopActive = false;
    private boolean pendingChildSettingsSync = false;
    private boolean pendingSettingsScreenRefresh = false;

    public BlockSpamModule() {
        super("block-spam", "Repeatedly places matching held blocks on highlighted block targets while the selected bind stays held.");
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
        if (!shouldContinueUsing()) {
            stopUseLoop();
            return;
        }
        if (!useLoopActive) {
            startUseLoop();
            return;
        }
        if (!useTaskScheduled) {
            scheduleNextUse(activeSequenceToken);
        }
    }

    private void startUseLoop() {
        if (!shouldContinueUsing()) {
            return;
        }

        useLoopActive = true;
        activeSequenceToken++;
        cancelScheduledUse();
        if (!isRightClickActivation() && immediateFirstUse.getVal()) {
            queueUseClick();
        }
        if (shouldContinueUsing()) {
            scheduleFirstRepeatedUse(activeSequenceToken);
        }
    }

    private boolean shouldContinueUsing() {
        return activationHeld
                && canOperate()
                && !activationButton.isNone()
                && isHoldingAllowedItem()
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

    private boolean isHoldingAllowedItem() {
        return matchesConfiguredHeldItem(mc.player.getMainHandItem());
    }

    private boolean matchesConfiguredHeldItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }

        String descriptionId = stack.getItem().getDescriptionId().toLowerCase(Locale.ROOT);
        for (String token : getAllowedHeldItemTokens()) {
            if (!token.isEmpty() && descriptionId.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private String[] getAllowedHeldItemTokens() {
        return allowedHeldItems.getVal().toLowerCase(Locale.ROOT).replace(" ", "").split(",");
    }

    private boolean isTargetingBlock() {
        if (!(mc.hitResult instanceof BlockHitResult hit) || mc.hitResult.getType() != HitResult.Type.BLOCK) {
            return false;
        }
        return !mc.level.getBlockState(hit.getBlockPos()).isAir();
    }

    private boolean isRightClickActivation() {
        return activationButton.matchesMouse(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
    }

    private long getRandomizedStartDelayMs() {
        return Math.max(0L, Math.round(startDelay.getRandomizedValue() * 1000.0));
    }

    private long getRandomizedDelayMs() {
        return Math.max(0L, Math.round(useDelay.getRandomizedValue() * 1000.0));
    }

    private void scheduleFirstRepeatedUse(int sequenceToken) {
        scheduleUse(sequenceToken, getRandomizedStartDelayMs() + getRandomizedDelayMs());
    }

    private void scheduleNextUse(int sequenceToken) {
        scheduleUse(sequenceToken, getRandomizedDelayMs());
    }

    private void scheduleUse(int sequenceToken, long delayMs) {
        if (!isSequenceValid(sequenceToken)) {
            return;
        }

        int useToken = ++scheduledUseToken;
        useTaskScheduled = true;
        system.scheduler.runDelayedTask(() -> mc.execute(() -> tryQueueUse(sequenceToken, useToken)), delayMs);
    }

    private void tryQueueUse(int sequenceToken, int useToken) {
        if (!isScheduledUseValid(sequenceToken, useToken)) {
            return;
        }

        useTaskScheduled = false;
        if (!shouldContinueUsing()) {
            stopUseLoop();
            return;
        }

        queueUseClick();
        if (shouldContinueUsing()) {
            scheduleNextUse(sequenceToken);
        }
    }

    private boolean isSequenceValid(int sequenceToken) {
        return useLoopActive && activeSequenceToken == sequenceToken;
    }

    private boolean isScheduledUseValid(int sequenceToken, int useToken) {
        return useTaskScheduled
                && scheduledUseToken == useToken
                && isSequenceValid(sequenceToken);
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

    private void stopUseLoop() {
        useLoopActive = false;
        activeSequenceToken++;
        cancelScheduledUse();
    }

    private void resetRuntimeState() {
        activationHeld = false;
        stopUseLoop();
    }

    private void handleActivationPressed() {
        if (activationHeld) {
            return;
        }
        activationHeld = true;
        if (!useLoopActive) {
            startUseLoop();
        }
    }

    private void handleActivationReleased() {
        if (!activationHeld) {
            return;
        }
        activationHeld = false;
        stopUseLoop();
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
        activationButton.setChangeAction(setting -> scheduleChildSettingsSync());
    }

    private void syncVisibleSettings() {
        List<ModuleSetting<?>> settings = scGeneral.getSettings();
        settings.clear();
        settings.add(activationButton);
        settings.add(allowedHeldItems);
        settings.add(startDelay);
        if (!isRightClickActivation()) {
            settings.add(immediateFirstUse);
        }
        settings.add(useDelay);
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
        return List.of(immediateFirstUse);
    }
}
