package com.loophole.essentials.module.modules;

import com.loophole.essentials.mixin.AccessorKeyMapping;
import com.loophole.essentials.module.LoopholeListenerModule;
import com.loophole.essentials.module.settings.RangeDoubleSetting;
import io.github.itzispyder.clickcrystals.events.EventHandler;
import io.github.itzispyder.clickcrystals.events.events.networking.GameLeaveEvent;
import io.github.itzispyder.clickcrystals.events.events.world.ClientTickStartEvent;
import io.github.itzispyder.clickcrystals.util.minecraft.PlayerUtils;
import net.minecraft.client.KeyMapping;
import net.minecraft.world.item.Items;

public class FastExpModule extends LoopholeListenerModule {

    private final RangeDoubleSetting startDelay = getGeneralSection().add(createRangeDoubleSetting()
            .name("start-delay")
            .description("Randomized delay after pressing Right Click before the repeating use-delay cycle starts.")
            .def(0.000, 0.000)
            .min(0.000)
            .max(0.100)
            .decimalPlaces(3)
            .build()
    );

    private final RangeDoubleSetting useDelay = getGeneralSection().add(createRangeDoubleSetting()
            .name("use-delay")
            .description("Randomized delay between queued experience-bottle uses while the use key stays held.")
            .def(0.000, 0.050)
            .min(0.000)
            .max(0.100)
            .decimalPlaces(3)
            .build()
    );

    private int scheduledUseToken = 0;
    private boolean useTaskScheduled = false;
    private boolean useLoopActive = false;

    public FastExpModule() {
        super("fast-exp", "Uses experience bottles quickly while holding right-click.");
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
        if (!shouldContinueUsing()) {
            resetRuntimeState();
            return;
        }
        if (!useLoopActive) {
            useLoopActive = true;
            scheduleFirstRepeatedUse();
            return;
        }
        if (!useTaskScheduled) {
            scheduleNextUse();
        }
    }

    private boolean shouldContinueUsing() {
        return canOperate()
                && mc.player.getMainHandItem().is(Items.EXPERIENCE_BOTTLE)
                && mc.options.keyUse.isDown();
    }

    private boolean canOperate() {
        return isEnabled()
                && PlayerUtils.valid()
                && mc.player != null
                && mc.level != null
                && mc.options != null
                && mc.screen == null;
    }

    private long getRandomizedStartDelayMs() {
        return Math.max(0L, Math.round(startDelay.getRandomizedValue() * 1000.0));
    }

    private long getRandomizedDelayMs() {
        return Math.max(0L, Math.round(useDelay.getRandomizedValue() * 1000.0));
    }

    private void scheduleFirstRepeatedUse() {
        scheduleUse(getRandomizedStartDelayMs() + getRandomizedDelayMs());
    }

    private void scheduleNextUse() {
        scheduleUse(getRandomizedDelayMs());
    }

    private void scheduleUse(long delayMs) {
        int useToken = ++scheduledUseToken;
        useTaskScheduled = true;
        system.scheduler.runDelayedTask(() -> mc.execute(() -> tryQueueUse(useToken)), delayMs);
    }

    private void tryQueueUse(int useToken) {
        if (!isScheduledUseValid(useToken)) {
            return;
        }

        useTaskScheduled = false;
        if (!shouldContinueUsing()) {
            return;
        }

        queueUseClick();
        if (shouldContinueUsing()) {
            scheduleNextUse();
        }
    }

    private boolean isScheduledUseValid(int useToken) {
        return useTaskScheduled && scheduledUseToken == useToken;
    }

    private void queueUseClick() {
        mc.execute(() -> {
            AccessorKeyMapping keyUse = (AccessorKeyMapping) mc.options.keyUse;
            KeyMapping.click(keyUse.loopholeEssentials$getBoundKey());
        });
    }

    private void resetRuntimeState() {
        useTaskScheduled = false;
        useLoopActive = false;
        scheduledUseToken++;
    }
}
