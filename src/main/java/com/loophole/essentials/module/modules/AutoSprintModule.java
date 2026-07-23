package com.loophole.essentials.module.modules;

import com.loophole.essentials.module.LoopholeListenerModule;
import io.github.itzispyder.clickcrystals.events.EventHandler;
import io.github.itzispyder.clickcrystals.events.events.networking.GameLeaveEvent;
import io.github.itzispyder.clickcrystals.events.events.world.ClientTickStartEvent;
import io.github.itzispyder.clickcrystals.util.minecraft.InteractionUtils;
import io.github.itzispyder.clickcrystals.util.minecraft.PlayerUtils;

public class AutoSprintModule extends LoopholeListenerModule {

    private boolean sprintToggleLatched = false;

    public AutoSprintModule() {
        super("auto-sprint", "Automatically toggles sprint when you press forward and are not already sprinting, while avoiding repeated sprint presses every tick.");
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
    private void onTickStart(ClientTickStartEvent e) {
        if (!canOperate()) {
            resetRuntimeState();
            return;
        }

        boolean movingForward = mc.options.keyUp.isDown();
        boolean sprinting = mc.player.isSprinting();

        if (!movingForward) {
            sprintToggleLatched = false;
            return;
        }
        if (sprinting) {
            sprintToggleLatched = false;
            return;
        }
        if (sprintToggleLatched) {
            return;
        }

        InteractionUtils.inputToggleSprint();
        sprintToggleLatched = true;
    }

    private boolean canOperate() {
        return isEnabled()
                && PlayerUtils.valid()
                && mc.player != null
                && mc.level != null
                && mc.options != null
                && mc.screen == null;
    }

    private void resetRuntimeState() {
        sprintToggleLatched = false;
    }
}
